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

## 按版本容量治理

每个模型版本拥有**独立的**批量调度器、承载参数快照、健康状态机与运行计数，
版本之间完全隔离：某个版本变慢或积压，不会连累其他版本的正常请求被拒绝或超时。

承载参数（未单独配置的版本继承 `gateway.batch.*` 全局默认）：

| 参数 | 含义 |
| --- | --- |
| `maxBatchSize` | 单批最大请求数 |
| `batchWindowMs` | 合批窗口（毫秒） |
| `queueCapacity` | 排队上限 |
| `maxQueueWaitMs` | 排队等待上限（毫秒） |
| `executionTimeoutMs` | 单批执行超时（毫秒） |
| `failureThreshold` | 连续失败/过载达到该次数判定为不健康 |
| `recoveryMs` | 不健康后经过该毫秒数放行恢复探针 |

配置方式：

```properties
# 健康策略默认值
gateway.capacity.failure-threshold=5
gateway.capacity.recovery-ms=5000
# 按版本覆盖（只覆盖列出的项，其余继承默认）
gateway.capacity.versions.v2.queue-capacity=16
gateway.capacity.versions.v2.execution-timeout-ms=300
```

**运行中调参**：`PUT /api/v1/versions/{version}/capacity` 整体替换该版本的
参数快照，无需重启、不打断在途请求。调度器在形成每个批次时取一次快照，
因此**同一批的判定看到同一套参数**，不会出现一半按旧值一半按新值；
在途批次仍按原参数执行完成。参数必须全部为正数，否则 400。
`GET /api/v1/versions/{version}/capacity` 可查询当前生效参数。

## 版本健康受控与自动恢复

每个版本独立维护健康状态机（全部迁移确定、可复现）：

```
HEALTHY --连续失败/过载达 failureThreshold--> UNHEALTHY
UNHEALTHY --经过 recoveryMs--> PROBING（放行单个探针，其余快速拒绝）
PROBING --探针成功--> HEALTHY
PROBING --探针失败--> UNHEALTHY（重新计时）
```

- 失败信号：执行超时降级、内部异常降级、结果不可用降级、QUEUE_FULL、WAIT_TIMEOUT。
- UNHEALTHY 状态下请求**不再入队堆积**，立即以 `reason = VERSION_UNHEALTHY`
  拒绝（HTTP 503）——与普通过载拒绝（QUEUE_FULL/WAIT_TIMEOUT，HTTP 429）
  和普通超时/异常降级（EXECUTION_TIMEOUT/INTERNAL_ERROR 等，DEGRADED）严格区分。
- 恢复无需人工重启：到达重试时点后自动放行探针，探针成功即重新接纳；
  探针失败则重新计时，恢复判定确定、可复现。
- 状态只作用于本版本，不影响其他版本。

## 运行观测

- `GET /api/v1/versions/stats`：全部已治理版本的运行状态。
- `GET /api/v1/versions/{version}/stats`：单版本运行状态。
- `POST /api/v1/versions/{version}/stats/reset`：重置单版本计数并恢复健康。

单版本状态示例：

```json
{
  "version": "v1",
  "queued": 0,
  "inFlight": 0,
  "okCount": 12,
  "rejectedQueueFull": 0,
  "rejectedWaitTimeout": 0,
  "rejectedUnhealthy": 3,
  "degradedExecutionTimeout": 0,
  "degradedInternalError": 2,
  "degradedResultUnavailable": 0,
  "health": "HEALTHY",
  "retryAfterMs": 0
}
```

- `queued`/`inFlight`：当前排队深度与在途处理数（实时值）。
- 各类拒绝/降级分别计数，不健康快速拒绝（`rejectedUnhealthy`）与普通过载、
  普通降级可区分。
- `retryAfterMs`：UNHEALTHY 状态下距下次放行探针的剩余毫秒（其余状态为 0）。
- 重置清零计数并将健康状态恢复为 HEALTHY（压测场景）；
  查询与重置为只读/计数操作，不干扰正常调度，也不改变任何已有请求的判定结果。

## 兼容范围

- 请求/响应协议不变：`status`/`reason` 语义与既有取值保持原义；
  仅新增一个拒绝原因枚举值 `VERSION_UNHEALTHY`（HTTP 503），
  调用方按既有 `status = REJECTED` + `reason` 的方式即可区分。
- 既有拒绝（VERSION_*、CONTRACT_INCOMPATIBLE、QUEUE_FULL、WAIT_TIMEOUT）
  与降级（EXECUTION_TIMEOUT、INTERNAL_ERROR、RESULT_UNAVAILABLE）行为不变。
- `gateway.batch.*` 配置继续作为全部版本的全局默认值，行为与之前一致。

## 本地验证

```bash
# 全部测试（并发批量、过载拒绝、版本兼容、降级、可复现性、HTTP 协议、
# 版本隔离、运行中调参、健康受控与自动恢复、运行观测与重置）
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

# 按版本观测与治理
curl -s localhost:8080/api/v1/versions/v1/stats
curl -s -X PUT localhost:8080/api/v1/versions/v1/capacity \
  -H 'Content-Type: application/json' \
  -d '{"maxBatchSize":8,"batchWindowMs":20,"queueCapacity":32,
       "maxQueueWaitMs":200,"executionTimeoutMs":500,
       "failureThreshold":5,"recoveryMs":5000}'
curl -s -X POST localhost:8080/api/v1/versions/v1/stats/reset
```

测试日志会打印请求、批次分组（batchId/batchSize）与判定依据
（status/score/label/reason），便于人工核对行为。
