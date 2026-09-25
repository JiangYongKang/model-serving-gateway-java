# 本地推理网关（Model Serving Gateway）

在 Spring Boot 后端服务内实现的一套**完全本地**的推理评分网关：统一入口接收评分请求，
按模型版本路由到本地确定性模型实现；在模型不可用、结果不可用或系统过载时，仍然返回
**可解释、可复现、且与正常结论严格可区分**的结论。不调用任何外部服务，不使用真实凭据。

## 1. 架构与判定流

```
HTTP /api/v1/inference/score
        │
        ▼
  InferenceGateway（版本路由 / 契约校验 / 策略分流 / 判定审计）
        │ 精确版本注册表（无默认版本兜底）
        ▼
  BatchScheduler（每版本一个：窗口合批 + 有界队列 + 等待上限 + 执行超时）
        │
        ▼
  LocalModel（本地确定性实现：SHA-256 哈希评分）
```

- 合批：并发到达的请求在 `gateway.batch.window-ms` 时间窗内，或凑满 `max-size` 时合并为一个批次执行。
- 执行槽：每个版本同时只有一个批次在模型线程上执行，模型繁忙时新批次保持排队，
  使“排队深度/等待时间”上限真实生效（不会在执行器内部无界堆积）。
- 每条结论都写入进程内有界审计日志（`DecisionAuditLog`，容量 1024 条），可按 requestId 查询判定依据。

## 2. 推理协议

### 2.1 评分请求

`POST /api/v1/inference/score`，`Content-Type: application/json`

```json
{
  "requestId": "req-001",
  "modelVersion": "v1",
  "contractVersion": "1.0",
  "inputs": { "text": "hello" }
}
```

| 字段 | 含义 |
| --- | --- |
| requestId | 调用方指定的请求标识（可空，自动生成）；用于结论与判定依据查询 |
| modelVersion | **必填**，精确版本号（如 `v1`/`v2`）；为空或未注册直接拒绝 |
| contractVersion | 输入契约版本，必须与该模型声明支持的契约**精确相等** |
| inputs | 输入对象；v2 契约要求包含 `features` 字段 |

### 2.2 响应（三类可区分结论）

| status | HTTP | score | 含义 |
| --- | --- | --- | --- |
| `NORMAL` | 200 | 模型分数 | 批量执行成功 |
| `DEGRADED` | 200 | 确定性兜底分数 | 执行超时 / 内部异常 / 结果不可用，走降级 |
| `REJECTED` | 422 | `null` | 路由错误（缺失/下线/契约不兼容）或过载 |

正常示例：

```json
{
  "requestId": "req-001",
  "status": "NORMAL",
  "modelVersion": "v1",
  "score": 0.6046,
  "batchId": "v1-4-0f4b08db",
  "reasonCode": null,
  "reason": null
}
```

拒绝示例：

```json
{
  "requestId": "req-002",
  "status": "REJECTED",
  "modelVersion": "v0",
  "score": null,
  "batchId": null,
  "reasonCode": "VERSION_RETIRED",
  "reason": "model version 'v0' has been retired"
}
```

### 2.3 可区分原因码（reasonCode）

| 原因码 | 结论 | 触发条件 |
| --- | --- | --- |
| `VERSION_NOT_FOUND` | REJECTED | 版本为空或注册表中不存在（不静默使用默认版本） |
| `VERSION_RETIRED` | REJECTED | 版本已下线 |
| `CONTRACT_INCOMPATIBLE` | REJECTED | 契约版本不匹配，或输入缺少契约必需字段 |
| `QUEUE_FULL` | REJECTED | 提交时排队深度已达 `queue-depth` 上限（立即拒绝，不等待） |
| `WAIT_TIMEOUT` | REJECTED | 入队后排队时间超过 `queue-wait-ms` 仍未执行 |
| `MODEL_TIMEOUT` | DEGRADED | 单次模型执行超过 `model-timeout-ms` |
| `MODEL_ERROR` | DEGRADED | 模型抛出内部异常 |
| `RESULT_INVALID` | DEGRADED | 模型返回 null / 长度与批次不符 / NaN / Infinity |

### 2.4 判定依据查询

- `GET /api/v1/inference/decisions/{requestId}` → 单条判定记录（含 status、reasonCode、batchId、basis、时间戳）
- `GET /api/v1/inference/decisions` → 最近的判定记录列表

## 3. 版本兼容规则

- **精确版本路由**：注册表只按精确版本号查找，系统内不存在“默认版本”。
  版本缺失或为空必须返回 `VERSION_NOT_FOUND`，绝不静默替换。
- **生命周期**：版本状态为 `RETIRED` 时一律 `VERSION_RETIRED`。
- **契约精确匹配**：`supportsContract` 要求契约字符串完全相等；此外每个模型可声明必需输入字段
  （如 v2 的 `features`），缺失即 `CONTRACT_INCOMPATIBLE`。不做隐式升降级。
- **本地版本清单**（见 `LocalModelBootstrap`）：
  - `v1`：ACTIVE，契约 `1.0`，输入仅需 `text`
  - `v2`：ACTIVE，契约 `2.0`，输入必须含 `features`
  - `v0`：RETIRED
- **可复现性**：模型分数 = `SHA-256(版本盐值 + 规范化输入)` 映射到 `[0,1)`。
  Map 的 key 排序后序列化，字段顺序不影响结果；同一版本、同一输入跨调用、跨批次结论完全一致。

## 4. 降级与过载策略

- **过载是拒绝，不是降级**：`QUEUE_FULL` / `WAIT_TIMEOUT` 返回 REJECTED 且无分数，
  调用方可明确感知并重试，系统不无限等待、不静默丢弃。
- **执行期故障是降级**：`MODEL_TIMEOUT` / `MODEL_ERROR` / `RESULT_INVALID` 返回 DEGRADED，
  携带确定性兜底分数：
  `fallback = SHA-256("FALLBACK|" + 版本 + "|" + 规范化输入)` 映射到 `[0,1)`。
  - 兜底分数与正常分数使用不同盐值前缀，且 status=DEGRADED + reasonCode 明示，**绝不混入正常结论**；
  - 兜底同样只依赖版本与输入，同版本同输入的降级结论也可复现。
- **可追溯**：所有结论（正常/降级/拒绝）都带 batchId（排队阶段拒绝除外）并写入审计日志，
  降级/拒绝原因随时可通过决策查询接口获取。

## 5. 关键配置（`src/main/resources/application.properties`）

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| gateway.batch.max-size | 8 | 单批最大请求数，凑满立即执行 |
| gateway.batch.window-ms | 20 | 合批等待窗口 |
| gateway.batch.queue-depth | 16 | 排队深度上限，超过立即 QUEUE_FULL |
| gateway.batch.queue-wait-ms | 500 | 排队等待上限，超过 WAIT_TIMEOUT |
| gateway.batch.model-timeout-ms | 200 | 单次模型执行上限，超过 MODEL_TIMEOUT |

## 6. 本地验证方法

要求 JDK 21+、Maven 3.6+；全程离线运行，无外部依赖与凭据。

```bash
# 编译
mvn compile

# 全量测试（单测日志会打印请求 REQUEST/ENQUEUE、批次分组 BATCH_GROUP、
# 判定依据 DECISION/DEGRADE/REJECT 等关键信息）
mvn test

# 仅运行某一场景
mvn test -Dtest=BatchConcurrencyTest     # 并发合批 + 同版本可复现
mvn test -Dtest=OverloadRejectionTest    # QUEUE_FULL / WAIT_TIMEOUT 可区分拒绝
mvn test -Dtest=VersionRoutingTest       # 缺失/下线/契约不兼容可区分错误
mvn test -Dtest=DegradationTest          # 超时/异常/坏结果降级、不污染正常结论
mvn test -Dtest=InferenceApiIntegrationTest  # HTTP 端到端与判定查询

# 启动后手动验证（端口默认随机；可改为固定 server.port）
mvn spring-boot:run
curl -s -X POST localhost:${PORT}/api/v1/inference/score \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"q1","modelVersion":"v1","contractVersion":"1.0","inputs":{"text":"hello"}}'
curl -s localhost:${PORT}/api/v1/inference/decisions/q1
```

测试报告位于 `target/surefire-reports/`。

### 测试覆盖矩阵

| 测试类 | 场景 | 关键断言 |
| --- | --- | --- |
| BatchConcurrencyTest | 并发批量 | 24 并发全部 NORMAL 且带 batchId；同输入跨批次同分；key 顺序不影响结论 |
| OverloadRejectionTest | 过载拒绝 | 排队深度满 → QUEUE_FULL；等待超期 → WAIT_TIMEOUT；两类原因互不混淆、均非降级 |
| VersionRoutingTest | 版本与契约 | 缺失/空版本 → VERSION_NOT_FOUND；v0 → VERSION_RETIRED；错误契约/缺字段 → CONTRACT_INCOMPATIBLE；同版本可复现 |
| DegradationTest | 降级链路 | 超时/异常/坏结果分别得到 MODEL_TIMEOUT/MODEL_ERROR/RESULT_INVALID 的 DEGRADED；兜底可复现、与 NORMAL 可区分；原因可查询 |
| InferenceApiIntegrationTest | HTTP 端到端 | 状态码/响应字段、复现性、422 可区分原因、决策查询接口 |
