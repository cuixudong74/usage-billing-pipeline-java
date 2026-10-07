# Usage Billing Pipeline

基于 Java 17 与 Spring Boot 3.4 的用量计量与出账示例服务。服务使用本地 JSON 文件做原子持久化，重点保证输入去重、乱序/迟到事件、方案版本快照、显式更正、并发幂等和中断恢复语义。

## 1. 核心语义

### 1.1 用量归集

- 归集键为 `accountId + metric + 有序 dimensions`。
- 事件以 `(accountId, eventId)` 做账户内去重；同一账户重复收到相同事件号只计一次，不同账户可以各自使用相同事件号且互不影响。
- 同一账户内重复事件的业务字段必须一致：指标、事件时间、数量、维度一致即视为重放；`receivedAt` 不参与冲突判定。业务字段不一致时返回 `409 Conflict`，保留首次接收的事件并明确拒绝后续冲突载荷。
- 归集结果不依赖到达顺序：事件先按窗口过滤，再按 `eventTime/eventId` 排序，计量维度按账户、指标、维度键和维度值的字典序稳定排列。
- 数量必须非负，使用 `BigDecimal` 保存，避免二进制浮点误差。

### 1.2 时间窗口

- 所有账单窗口均为半开区间 `[windowStart, windowEnd)`。
- 边界规则：`eventTime == windowStart` 属于本窗口；`eventTime == windowEnd` 属于下一窗口。
- 出账时只读取事件的 `eventTime`，不使用 `receivedAt` 决定归属。
- 已 `COMMITTED` 或 `ADJUSTED` 的账单不会被后续入库事件悄悄改写。
- 迟到或补报事件如果落在未封账窗口，可直接出账；如果落在已封账窗口，必须调用显式更正接口。
- 更正会重算该窗口：旧账单状态改为 `SUPERSEDED`，新账单状态为 `ADJUSTED`，并保存差额、原因、来源事件、原账单和新账单 ID。
- 如果更正重算后金额和用量均无变化，则不生成新版本。

### 1.3 方案版本

- 方案必须作为完整版本提交，包含该账户所需的完整计价规则集合。
- 方案版本号必须为正数且同账户唯一；生效区间不允许重叠。
- 首次账单选择窗口起点 `windowStart` 已生效的方案。
- 一个账单窗口不能跨越方案版本边界；跨边界预览或提交会被拒绝。账单应按版本切换点切分。
- 账单记录 `planId` 与 `planVersion`，每个账单行只来自同一个完整方案快照。
- 历史窗口显式更正时固定沿用原账单的 `planVersion`；新方案只影响其生效后的新窗口，不会向历史账单字段级混入。

### 1.4 计价与取整

支持每条计量行配置：

- 阶梯价：按数量分段计算，阶梯区间为 `[lower, upper)`；最后一层 `upperBoundExclusive = null` 表示无上限。
- 折扣：`discountRate` 范围为 `[0,1]`，例如 `0.10` 表示 9 折。
- 最低消费：折后金额低于最低消费时补足。
- 封顶：折后且应用最低消费后若超过封顶，扣减到封顶。
- 计算顺序固定为：阶梯原价 → 折扣 → 最低消费 → 封顶。
- 中间计算最多保留 10 位小数；对外金额统一保留 2 位小数，即按分取整，舍入模式为 `RoundingMode.HALF_UP`。
- 账单总额只对最终行金额求和；行上的 `listAmount - discountAmount + minimumAdjustment + capAdjustment = netAmount`，避免逐段四舍五入造成一分钱漂移。
- 账单行顺序只由业务字段决定：先按 `accountId`，再按 `metric`，再按逐项排序后的维度键值对排序。预览、提交、并发重复计算或进程重启后的重新计算均产生相同行序、行内容和总金额；`createdAt`、`idempotencyKey` 等运行字段不参与排序。

## 2. 一致性、并发与恢复

- 同一账户的事件上报、预览、提交、更正由账户级锁串行执行；不同账户使用不同锁，一个账户的慢出账、慢更正或慢恢复不会阻塞其他账户的上报、预览、提交与更正。
- 提交账单时先记录 `IN_PROGRESS` 恢复标记，再原子写入“账单 + 检查点 + 审计”，成功后记录 `COMMITTED`。
- 账单状态文件通过同目录临时文件和原子 rename 替换，避免半写 JSON。
- 同一账户、同一窗口的重复提交返回当前已提交账单，不生成第二份。
- 推荐在请求头传同一个 `Idempotency-Key`；窗口级唯一性由服务端强制保证。
- 应用启动后在后台按账户并发执行恢复对账，不阻塞其他账户接收请求：
  - `IN_PROGRESS` 且账单和检查点已存在：确认该结果并标记为 `COMMITTED`。
  - 首次出账的 `IN_PROGRESS`/历史 `INTERRUPTED` 标记且尚无账单：自动按当时窗口、事件和方案继续完成出账。
  - 更正过程的 `IN_PROGRESS`/历史 `INTERRUPTED` 标记且只存在原账单：自动继续完成更正；原账单标记为 `SUPERSEDED`，新账单为 `ADJUSTED`。
  - 已 `COMMITTED`/`ADJUSTED` 的窗口保持原样；再次恢复只收口恢复标记，不会生成第二份当前账单或重复计费。
  - 单个账户恢复失败只记录错误并保留该任务的未完成状态，不影响其他账户，可在下一次启动继续恢复。

## 3. 权限与审计

请求通过请求头传递调用主体（示例实现，生产环境应替换为网关/OIDC 下发的认证主体）：

- `X-Actor`：操作者或账户主体。
- `X-Roles`：逗号分隔角色。
- `Idempotency-Key`：幂等键。

角色：

- `ADMIN`：全部操作。
- `EVENT_WRITER`：上报事件。
- `BILLER`：预览、提交、更正、读取账单。
- `AUDITOR`：读取账单和审计。
- `ACCOUNT_OWNER`：仅当 `X-Actor` 等于 `accountId` 时可操作或读取本账户。
- 方案提交仅允许 `ADMIN`。

权限不足与参数非法使用不同 HTTP 语义：

- 参数缺失、格式错误或窗口非法：`400 Bad Request`。
- 已认证但角色/账户主体不满足操作要求：`403 Forbidden`，响应字段仍为 `timestamp/status/error/message`，其中 `error` 为 `Forbidden`。
- 鉴权在事件入库、方案写入、账单生成、检查点更新和审计写入之前执行；被 `403` 拒绝的请求不会产生任何计费结果或状态变更。

所有事件入库、方案提交、账单预览/提交、更正均写审计。金额变动记录包含：账单 ID、新旧账单 ID、方案 ID/版本、总金额、差额、原因和来源事件 ID。

## 4. HTTP 接口

### 上报事件

`POST /api/events`

```json
{
  "eventId": "evt-1",
  "accountId": "acct-a",
  "metric": "api_calls",
  "eventTime": "2026-01-02T00:00:00Z",
  "quantity": 120.5,
  "dimensions": {"region": "cn"}
}
```

### 提交完整方案版本

`POST /api/plans`

```json
{
  "planId": "plan-a",
  "accountId": "acct-a",
  "version": 1,
  "effectiveFrom": "2026-01-01T00:00:00Z",
  "effectiveTo": "2026-02-01T00:00:00Z",
  "rules": [
    {
      "metric": "api_calls",
      "dimensions": {"region": "cn"},
      "tiers": [
        {"upperBoundExclusive": 100, "unitPrice": 0.10},
        {"upperBoundExclusive": null, "unitPrice": 0.20}
      ],
      "minimumAmount": 10.00,
      "maximumAmount": 100.00,
      "discountRate": 0.10
    }
  ]
}
```

### 预览账单

`POST /api/bills/preview`

```json
{"accountId": "acct-a", "start": "2026-01-01T00:00:00Z", "end": "2026-02-01T00:00:00Z"}
```

预览不落账单版本，用于对外展示当前计算结果。

### 提交账单

`POST /api/bills/commit`

请求体同预览。成功后账单封账并写检查点。

### 查询当前账单

`GET /api/bills/current?accountId=acct-a&start=2026-01-01T00:00:00Z&end=2026-02-01T00:00:00Z`

### 显式更正

`POST /api/bills/{billId}/adjustments`

```json
{"reason": "late usage events arrived after close"}
```

无金额或用量差异时返回 404，不制造无意义调整版本。

### 查询审计

`GET /api/audit?accountId=acct-a`

## 5. 配置项

在 `src/main/resources/application.properties` 中配置：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `0` | 示例测试使用随机端口；生产可改为固定端口。 |
| `billing.data-dir` | `./data/billing` | 本地 JSON 状态目录。 |

状态文件：

- `events.json`：已接收且按账户去重的事件。旧版本保存的裸 `eventId` 键仍可直接读取，并在该事件再次重放时自动迁移为 `(accountId,eventId)` 复合键。
- `plans.json`：已提交方案版本。旧版本保存的 `planId#version` 键仍可直接读取，并在该方案再次提交时自动迁移为账户级复合键。
- `billing-state.json`：账单、更正、检查点、账单审计的原子聚合状态。
- `recovery-state.json`：任务恢复标记。

现有方案、事件、账单和配置项无需迁移即可继续使用；历史 `INTERRUPTED` 恢复标记会在新版本启动时自动继续执行。

## 6. 构建与测试

```bash
mvn test
mvn package
```

测试覆盖：

- 不同账户使用相同事件号互不影响；同账户重放只计一次、业务字段不一致明确冲突。
- 重复事件、乱序、迟到/补报后的稳定归集。
- 阶梯价、折扣、最低消费、封顶和 2 位金额取整。
- 多维账单在预览、提交、并发/重启重算下的确定性行序与逐项一致。
- 已封账窗口不被悄悄改写、显式更正保留新旧版本和差额。
- 方案版本切换与跨版本窗口拒绝。
- 同账户并发出账只生成一份账单，不同账户慢操作互不阻塞。
- 越权请求返回可识别的 `403 Forbidden`，且不写事件、审计或账单；参数错误仍返回 `400 Bad Request`。
- 首次出账与更正中断后均可在恢复时自动完成，重复恢复不生成第二份账单、不重复计费，已封账结果保持原样。
