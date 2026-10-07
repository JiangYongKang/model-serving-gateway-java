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
  "batchId": "lane-v1-batch-3",
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

## 批量调度与过载控制（按版本车道）

**每个模型版本一条独立调度车道**：独立队列、独立调度线程、独立批次。
一个版本变慢或积压，不会占用其他版本的队列与调度资源，
健康版本的请求不会被连累拒绝或超时。

并发请求在有限窗口内合并为批量执行。全局默认参数（`application.properties`）：

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

## 按版本容量治理（运行中可调）

每个版本可在全局默认之上单独配置自己的承载参数，且**运行中调整即生效**：
不重启服务、不打断在途请求。调整以整体快照原子替换：
每次提交、每一批的形成各自读取一次快照，**同一批判定看到同一套参数**，
不会出现一半按旧值一半按新值的结果。

- `GET /api/v1/versions/{version}/capacity`：查询该版本当前生效的承载参数。
- `PUT /api/v1/versions/{version}/capacity`：整体替换该版本承载参数，立即对新提交/新批次生效。

```bash
curl -s -X PUT localhost:8080/api/v1/versions/v1/capacity \
  -H 'Content-Type: application/json' \
  -d '{"maxBatchSize":4,"batchWindowMs":10,"queueCapacity":32,
       "maxQueueWaitMs":100,"executionTimeoutMs":300}'
```

参数必须全部为正数，否则返回 400 且不生效。未单独配置的版本使用全局默认。

## 版本健康状态（受控与自动恢复）

每个版本独立运行一个健康状态机（熔断式），互不影响：

| 状态 | 行为 |
| --- | --- |
| `HEALTHY` | 正常接纳；连续失败/过载（执行超时、内部异常、结果不可用、队列溢出、排队超时）达到阈值 → `UNHEALTHY` |
| `UNHEALTHY` | 受控状态：不再无意义堆积排队，新请求立即以 `VERSION_UNHEALTHY` 拒绝（HTTP 503）；冷却期从进入时刻起算、固定不变 |
| `HALF_OPEN` | 冷却到期自动进入：放行有限个试探请求；试探成功 → `HEALTHY`（计数清零，自动恢复，无需人工重启）；试探失败 → 回到 `UNHEALTHY` 并重新起算冷却期 |

`VERSION_UNHEALTHY` 是独立的拒绝原因，与普通过载拒绝（`QUEUE_FULL`/`WAIT_TIMEOUT`）、
普通超时/异常降级（`EXECUTION_TIMEOUT`/`INTERNAL_ERROR`）严格区分。
状态迁移只依赖计数器与单调时钟，判定确定、可复现。

健康判定配置（`application.properties`）：

| 配置 | 默认 | 含义 |
| --- | --- | --- |
| `gateway.health.failure-threshold` | 3 | 连续失败/过载达到该次数即判定不健康 |
| `gateway.health.cooldown-ms` | 5000 | 不健康冷却期（毫秒），到期进入半开试探 |
| `gateway.health.half-open-probe-count` | 1 | 半开状态允许的并发试探请求数 |

## 运行观测（按版本）

- `GET /api/v1/versions/{version}/stats`：单版本运行状态。
- `GET /api/v1/versions/stats`：全部已观测版本。
- `POST /api/v1/versions/{version}/stats/reset` / `POST /api/v1/versions/stats/reset`：
  重置累计计数（压测用）。

```json
{
  "modelVersion": "v1",
  "healthState": "UNHEALTHY",
  "retryAfterMs": 3200,
  "queueDepth": 0,
  "inFlight": 0,
  "okCount": 41,
  "rejected": {"QUEUE_FULL": 5, "VERSION_UNHEALTHY": 12},
  "degraded": {"EXECUTION_TIMEOUT": 3}
}
```

- `queueDepth`/`inFlight`：实时 gauge（当前排队深度、正在处理数量）。
- `okCount`/`rejected`/`degraded`：累计计数，可按原因区分，可重置。
- `healthState` + `retryAfterMs`：健康状态；不健康时给出距重新尝试接纳的剩余毫秒数。

查询与重置只读/只清计数器，**不干扰正常调度，不改变任何在途请求的判定结果**；
重置只清累计计数，不影响实时 gauge。

## 版本路由与兼容规则

严格按 `modelVersion` 路由到对应本地实现，**绝不静默回退默认版本**：

| 场景 | reason | HTTP |
| --- | --- | --- |
| 未指定版本 | `VERSION_MISSING` | 400 |
| 版本不存在 | `VERSION_UNKNOWN` | 404 |
| 版本已下线 | `VERSION_RETIRED` | 410 |
| 契约版本不匹配 / 特征缺失 | `CONTRACT_INCOMPATIBLE` | 400 |
| 队列已满 / 排队超时 | `QUEUE_FULL` / `WAIT_TIMEOUT` | 429 |
| 版本不健康（受控快速失败） | `VERSION_UNHEALTHY` | 503 |

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

## 兼容范围

- 请求/响应协议字段保持不变；`reason` 仅**新增**枚举值 `VERSION_UNHEALTHY`，
  既有取值与含义不变，调用方按既有 reason 分支处理的行为不受影响。
- 既有拒绝（路由类、过载类）与降级（超时、异常、结果不可用）的触发条件与结论格式不变。
- 新增端点（容量治理、运行观测）全部为只读查询或整体替换式更新，
  不改变评分入口的判定语义。

## 本地验证

```bash
# 全部测试（并发批量、过载拒绝、版本兼容、降级、版本隔离、
# 运行中调参、健康进入与自动恢复、状态查询与重置、HTTP 协议）
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

# 容量治理与观测
curl -s localhost:8080/api/v1/versions/v1/capacity
curl -s -X PUT localhost:8080/api/v1/versions/v1/capacity \
  -H 'Content-Type: application/json' \
  -d '{"maxBatchSize":4,"batchWindowMs":10,"queueCapacity":32,
       "maxQueueWaitMs":100,"executionTimeoutMs":300}'
curl -s localhost:8080/api/v1/versions/v1/stats
curl -s -X POST localhost:8080/api/v1/versions/v1/stats/reset
```

测试日志会打印请求、批次分组（batchId/batchSize）、判定依据
（status/score/label/reason）、健康状态迁移与各用例覆盖的业务点，
便于人工核对行为。
