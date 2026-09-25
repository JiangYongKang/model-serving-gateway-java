# model-serving-gateway-java

本地推理网关：来自统一入口的评分请求被路由到本地模型实现。全部判定在本地完成，
不依赖外部服务或真实凭据；模型不可用或系统过载时仍返回可解释、可复现的结论。

## 推理协议

统一入口：`POST /api/v1/score`

请求体：

```json
{
  "requestId": "r1",
  "modelVersion": "v1",
  "contractVersion": 1,
  "features": {"income": 2.0, "debt": 0.5}
}
```

| 字段 | 说明 |
| --- | --- |
| `requestId` | 调用方请求标识，用于日志与降级记录关联 |
| `modelVersion` | 目标模型版本，必填；缺失返回 `VERSION_MISSING` |
| `contractVersion` | 可选；提供时必须与模型契约版本一致，否则拒绝 |
| `features` | 特征向量，必填；缺失按契约不兼容拒绝 |

响应体（判定语义以响应体为准，HTTP 状态码仅为传输层提示）：

```json
{
  "requestId": "r1",
  "modelVersion": "v1",
  "status": "OK",
  "score": 0.7941,
  "label": "PASS",
  "reason": "NONE",
  "batchId": "batch-3",
  "batchSize": 4
}
```

- `status = OK`：正常结论，`label ∈ {PASS, FAIL}`（阈值 0.5）。
- `status = DEGRADED`：降级结论，`score = 0.5`（确定性中性先验）、`label = FALLBACK`，
  与正常结论严格区分，绝不混入。
- `status = REJECTED`：拒绝，`score/label` 为空，`reason` 给出可区分原因。

辅助端点：

- `GET /api/v1/models`：当前在线模型版本列表。
- `GET /api/v1/degradations`：降级事件查询（requestId、版本、原因、明细、时间）。

## 批量调度与过载控制

并发请求在有限窗口内合并为批量执行，降低单次调用开销。参数（`application.properties`）：

| 配置 | 默认 | 含义 |
| --- | --- | --- |
| `gateway.batch.max-batch-size` | 8 | 单批最大请求数 |
| `gateway.batch.batch-window-ms` | 20 | 合批窗口（毫秒） |
| `gateway.batch.queue-capacity` | 64 | 排队队列容量上限 |
| `gateway.batch.max-queue-wait-ms` | 200 | 排队等待上限（毫秒） |
| `gateway.batch.execution-timeout-ms` | 500 | 单批执行超时（毫秒） |

过载时明确拒绝，不无限等待、不静默丢弃，且拒绝原因可区分：

- 队列已满 → `QUEUE_FULL`（HTTP 429）
- 排队等待超过上限 → `WAIT_TIMEOUT`（HTTP 429）

## 版本路由与兼容规则

严格按 `modelVersion` 路由到对应本地实现，**绝不静默回退默认版本**：

| 场景 | reason | HTTP |
| --- | --- | --- |
| 未指定版本 | `VERSION_MISSING` | 400 |
| 版本不存在 | `VERSION_UNKNOWN` | 404 |
| 版本已下线 | `VERSION_RETIRED` | 410 |
| 契约版本不匹配 / 特征缺失 | `CONTRACT_INCOMPATIBLE` | 400 |

内置版本：`v1`（契约 1）、`v2`（契约 2，与 v1 不兼容）、`v0`（已下线）。
模型为确定性线性打分（`score = sigmoid(bias + Σ wᵢxᵢ)`），权重固定、无随机性，
因此**同一输入在同一版本下的结论完全可复现**。

## 降级策略

执行链路异常时按确定策略降级，降级结果与正常结果可区分、原因可查询：

| 触发 | reason | 处理 |
| --- | --- | --- |
| 单批执行超时 | `EXECUTION_TIMEOUT` | 降级 |
| 模型内部异常 | `INTERNAL_ERROR` | 降级 |
| 结果不可用（NaN/Inf） | `RESULT_UNAVAILABLE` | 降级 |

降级输出固定为 `score = 0.5`、`label = FALLBACK`、`status = DEGRADED`：
可解释（中性先验，不偏向任何结论）、可复现（确定性常量）、
不污染正常结论（同批/同时段其他请求的正常结果不受影响）。
每次降级写入 `DegradationLog`，可通过 `GET /api/v1/degradations` 查询原因。

## 本地验证

```bash
# 全部测试（并发批量、过载拒绝、版本兼容、降级、可复现性、HTTP 协议）
mvn test

# 启动服务（server.port=0 表示随机端口，可改为固定端口）
mvn spring-boot:run

# 手工验证
curl -s -X POST localhost:8080/api/v1/score \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"r1","modelVersion":"v1","contractVersion":1,
       "features":{"income":2.0,"debt":0.5}}'
curl -s localhost:8080/api/v1/models
curl -s localhost:8080/api/v1/degradations
```

测试日志会打印请求、批次分组（batchId/batchSize）与判定依据
（status/score/label/reason），便于人工核对行为。
