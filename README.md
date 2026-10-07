# Usage Billing Pipeline

基于 Java 17 与 Spring Boot 3.4 的用量计量与出账示例服务。服务使用本地 JSON 文件做原子持久化，重点保证输入去重、乱序/迟到事件、方案版本快照、显式更正、并发幂等和中断恢复语义。

## 1. 核心语义

### 1.1 用量归集

- 归集键为 `accountId + metric + 有序 dimensions`。
- **事件去重以账户为作用域**：同一账户内同一 `eventId` 重复到达只计一次；不同账户上报相同 `eventId` 互不影响，各自独立存储、各自完整计入。存储内部使用 `accountId|eventId` 复合键。
- 同一账户内重复事件的业务字段必须一致：账户、指标、事件时间、数量、维度一致即视为重放（幂等返回已存事件）；`receivedAt` 不参与冲突判定。
- 同一账户内重复到达但业务字段不一致（指标/事件时间/数量/维度任一不同）时返回 `409 Conflict`，拒绝原因会明确指出事件编号、账户与“业务负载不一致”，事件不入库、不计量。
- 归集结果不依赖到达顺序：事件先按窗口过滤，再按 `eventTime/eventId` 排序，输出键也按稳定顺序排列。
- 数量必须非负，使用 `BigDecimal` 保存，避免二进制浮点误差。
- 历史数据兼容：早期版本 `events.json` 仅以 `eventId` 为键，服务启动时会透明归一化为账户级复合键，无需手工迁移，旧数据可直接继续使用。

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

### 1.5 账单行的确定性排序

- 账单行的顺序与内容只由业务字段决定：先按 `metric`，再按维度键值对的字典序（维度本身用有序 `SortedMap` 展开为 `key=value` 序列），最后以 `accountId` 兜底。
- 该顺序与事件到达顺序、归集时的 Map 迭代顺序、并发度、进程重启均无关；相同账户、相同窗口、相同事件与方案下，多次出账（预览或提交）得到逐项一致的行、数量、金额与事件 ID 列表。
- 预览与提交共用同一条计算路径，因此 `POST /api/bills/preview` 与随后的 `POST /api/bills/commit` 结果逐项一致（预览不落任何账单版本）。

## 2. 一致性、并发与恢复

- 事件上报、方案提交、预览、提交、更正在**同一账户内部**由账户级锁串行（保证同账户去重与出账的竞态安全）；**不同账户使用不同锁**，一个账户的慢出账、慢更正或慢恢复都不会阻塞其他账户的上报、预览、提交与更正，各账户进度相互独立。
- 提交账单时先记录 `IN_PROGRESS` 恢复标记（携带任务类型 `COMMIT/ADJUST`、幂等键与更正原因），再原子写入“账单 + 检查点 + 审计”，成功后记录 `COMMITTED`。
- 账单状态文件通过同目录临时文件和原子 rename 替换，避免半写 JSON；账单聚合状态常驻内存并在短临界区内原子落盘，不同账户并发提交不会互相覆盖。
- 同一账户、同一窗口的重复提交返回当前已提交账单，不生成第二份。
- 推荐在请求头传同一个 `Idempotency-Key`；窗口级唯一性由服务端强制保证。
- 应用启动时执行**自动恢复对账**，恢复任务按账户并发执行：
  - `IN_PROGRESS` 且账单/检查点已存在（崩溃发生在标记翻转之前）：直接补记为 `COMMITTED`，不重复计费。
  - `IN_PROGRESS` 且账单/检查点尚不存在（首次出账中途崩溃）：使用标记中的原始参数**自动把任务继续执行到完成**，生成账单、检查点与审计，无需人工重新提交。
  - `IN_PROGRESS` 的更正任务：使用标记中的原因自动重放更正流程；若替换账单已生成则幂等跳过，若未生成则补齐；已封账窗口保持原样。
  - 恢复后同一账户同一窗口至多存在一份当前账单（`COMMITTED` 或 `ADJUSTED`），绝不会出现两份账单或重复计费。
  - 个别窗口恢复仍失败时只保留该窗口为 `IN_PROGRESS`（owner 记录失败原因）以便下次重启重试，不影响其他账户与其他窗口。
  - `COMMITTED` 保持封账状态，恢复过程中绝不重算。

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

**拒绝语义（与参数错误严格区分）：**

- 请求已认证但权限不足时返回 `403 Forbidden`，响应体中 `status=403`、`error="Forbidden"`、`message` 以 `Access denied:` 开头并说明被拒绝的主体、动作与账户，便于调用方分支处理与安全告警识别。
- 请求本身非法（缺少已认证主体、窗口非法、参数缺失或类型错误、JSON 不可读等）返回 `400 Bad Request`，`error="Bad Request"`。
- 权限校验在任何状态变更之前完成；被 `403` 拒绝的请求不产生事件入库、账单、检查点、审计等任何计费结果或状态变更。

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

- `events.json`：已接收且按账户去重的事件（复合键 `accountId|eventId`）。
- `plans.json`：已提交方案版本（复合键 `accountId|planId#version`）。
- `billing-state.json`：账单、更正、检查点、账单审计的原子聚合状态。
- `recovery-state.json`：任务恢复标记，包含任务类型（`COMMIT/ADJUST`）、幂等键与更正原因。

上述 JSON 在升级后首次启动时会透明归一化旧键，旧的方案、事件、账单与配置无需迁移即可继续使用。

## 6. 构建与测试

```bash
mvn test
mvn package
```

测试覆盖：

- 重复事件、乱序、迟到/补报后的稳定归集。
- 阶梯价、折扣、最低消费、封顶和 2 位金额取整。
- 已封账窗口不被悄悄改写、显式更正保留新旧版本和差额。
- 方案版本切换与跨版本窗口拒绝。
- 同账户并发出账只生成一份账单，不同账户互不阻塞。
- **不同账户复用相同 `eventId` 各自完整计量；同账户真重复只计一次、业务字段不一致被明确拒绝（409）。**
- **多维度账单在不同到达顺序下行序与内容稳定，预览与提交逐项一致，重启后重算逐项一致。**
- **出账/更正中断后重启自动续作完成；已写入账单的崩溃不产生第二份账单、不重复计费；已封账窗口保持原样；多账户恢复并发进行。**
- **慢账户不阻塞快账户的上报与出账。**
- **越权请求返回 403（可与 400 参数错误区分），且不产生任何计费副作用。**
- **旧格式 `events.json`/`plans.json` 无需迁移即可读取。**
