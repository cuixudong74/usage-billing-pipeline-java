# 用量计量与出账系统（Usage Billing Pipeline）

一套用量计量与出账能力：上游持续上报带时间戳的用量事件，系统按账户与计量维度归集，
支持重复事件去重、乱序/迟到/超期补报、按时间窗口出账、计价方案版本化、阶梯价/最低消费/
封顶/折扣、出账与更正并发、中断恢复、权限校验与全流程审计。

## 一、计量语义

### 1.1 事件模型
- 用量事件 `UsageEvent`：`(accountId, dimension, quantity, eventTime, eventId)`。
- **幂等去重**：以 `(accountId, eventId)` 为唯一键。同一账户重复上报同一 `eventId`
  不会重复入账；不同账户的相同 `eventId` 视为不同事件。
- 事件只追加、不修改、不删除。

### 1.2 窗口归属（确定规则）
- 窗口为**自然月**，按配置时区对齐（默认 UTC），`windowId` 形如 `2026-09`。
- 区间**左闭右开**：`[当月1日 00:00, 下月1日 00:00)`。
- 事件**按 `eventTime`（实际发生时间）归属窗口**，与上报时间/摄入时间无关。
  乱序到达、迟到很久、超期补报都不改变其归属窗口。
- 跨窗口事件各归各窗：9 月的事件即使 10 月才上报，仍计入 9 月账单。

### 1.3 窗口生命周期与封账
- 窗口状态：`OPEN`（出账中）→ `SEALED`（已封账）。
- **宽限期**：窗口结束后 `billing.grace-period-days`（默认 3）天内为 OPEN，可直接出账；
  超期后由自动封账巡检或手动封账置为 SEALED。
- **封账不可逆**：封账后账单**不得被后续事件悄悄改写**。新事件仍会被接收并归属到该窗口，
  但已封账单保持原样；需要更正时必须走**显式调整**（`/api/billing/adjust`），
  调整生成新版本账单并保留调整前后差异（`Adjustment` 记录）。

### 1.4 确定性（可复现）
- 账单由窗口内**全量事件集合**计算得出，不是增量累加。
- 同一份输入在任何并发度、任何处理顺序下，算出的账单逐项一致。
  （测试覆盖：乱序摄入、并发提交、重复上报、跨账户并行。）

## 二、计价规则

### 2.1 方案版本化
- 方案 `PricingPlan`：`planCode + version` 唯一，状态 `DRAFT` / `COMMITTED`。
- 只有 **COMMITTED** 方案可被分配、可用于出账；DRAFT 不可出账。
- 账户通过 `PlanAssignment` 在 `[effectiveFrom, effectiveTo)` 区间采用某方案版本。
- 出账时按**窗口起点**匹配覆盖该时刻的分配记录；**一个窗口只采用一个方案版本**，
  不做跨版本字段级混合。
- 账单在提交时把**规则快照**写入账单（`rulesSnapshot`），历史账单在方案更新后保持原样。

### 2.2 计价公式（应用顺序）
1. 各维度按**阶梯价**（slab 分段，左闭右开）计算阶梯小计；
2. 小计之和 × `discount`（折扣，1 = 无折扣，0.9 = 九折）；
3. 与 `minCharge`（最低消费）取大；
4. 与 `cap`（封顶）取小；
5. 最终金额保留 2 位小数。

阶梯价示例：`tiers=[(100, 1.00), (500, 0.80), (null, 0.60)]`，数量 600：
`100×1.00 + 400×0.80 + 100×0.60 = 480.00`。

### 2.3 精度与取整（无一分钱漂移）
- 全系统金额运算使用 `BigDecimal`，禁止 `double`/`float`。
- 中间运算（阶梯单价×数量、折扣）保留 `billing.amount-scale`（默认 4）位；
- 最终金额保留 `billing.final-scale`（默认 2）位；
- 取整方式统一为 `HALF_UP`（四舍五入）。
- 相同输入永远得到相同结果（纯函数计价引擎）。

## 三、出账、更正与并发

### 3.1 出账
- **预览** `POST /api/billing/preview`：只读计算，不持久化，返回的账单标记 `preview=true`，
  不是已提交版本；预览不改变任何状态。
- **提交** `POST /api/billing/commit`：幂等。同一 `(accountId, windowId)` 重复提交只产生一份账单。
- 账单状态：`COMMITTED`（首次出账）/ `ADJUSTED`（更正后版本）。

### 3.2 更正
- `POST /api/billing/adjust`：基于窗口内**全量事件**（含迟到/补报）重新计算，
  生成 `billVersion + 1` 的新账单，原账单不被修改。
- 每次更正记录 `Adjustment`：`beforeAmount` / `afterAmount` / `diffAmount` / `reason`，
  可通过 `GET /api/billing/adjustments` 查询。

### 3.3 并发
- **账户级锁**：出账/更正按账户串行，不同账户互不阻塞（慢任务不影响其他账户推进）。
- 数据库唯一约束 `(accountId, windowId, billVersion)` 与乐观锁作为跨实例兜底。
- 任一时刻读取到的账单都是某个已提交的完整版本（预览除外）。

## 四、中断恢复

- 出账任务 `BillingTask` 持久化，状态 `PENDING` / `RUNNING` / `COMMITTED` / `FAILED`。
- 检查点 `cursorEventTime` / `cursorEventCount` 记录最近已处理位置。
- 重启后 `RecoveryService` 扫描中断任务：
  - 若已存在已提交账单 → 任务直接置 `COMMITTED`（**不重复出账**）；
  - 否则重置为 `PENDING` 并重跑提交（幂等，从全量事件重新计算，**不漏计**）。
- 所有写操作在**同一事务**内（账单 + 行项目 + 事件关联 + 任务 + 审计），无半写状态。
- 已封账窗口（`WindowState.status=SEALED`）与未完成出账（`BillingTask.status`）在持久层可区分。

## 五、权限与审计

### 5.1 权限
- 通过请求头传递操作人与角色：
  - `X-Operator`：操作人名；
  - `X-Operator-Roles`：逗号分隔角色（`BILLER` / `ADMIN`）。
- 出账/预览需 `BILLER`；更正/封账/方案管理需 `ADMIN`。
- 越权操作返回 `403` 并写审计（`ACCESS_DENIED`）。

### 5.2 审计
- `AuditLog` 记录所有关键操作：出账、更正、封账、方案创建/提交/分配、事件摄入、权限拒绝、恢复。
- 每次金额变动可通过 `accountId + windowId` 追溯，并关联到：
  - 来源事件 ID 集合（`Bill.eventIds`）；
  - 所用方案版本（`Bill.planCode/planVersion`）与规则快照（`Bill.rulesSnapshot`）。
- 查询：`GET /api/audit`。

## 六、配置项（application.properties）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `billing.grace-period-days` | `3` | 窗口宽限期（天），超期自动封账 |
| `billing.window-zone` | `UTC` | 窗口时区（月窗口对齐时区） |
| `billing.amount-scale` | `4` | 金额中间运算精度 |
| `billing.final-scale` | `2` | 最终金额精度 |
| `billing.rounding-mode` | `HALF_UP` | 取整方式 |
| `billing.auto-seal-enabled` | `true` | 是否启用自动封账巡检 |
| `billing.auto-seal-cron` | `0 0 * * * *` | 自动封账巡检 cron |

持久化：主应用使用 H2 文件库（`./data/billing`），重启后已提交状态保留；
测试使用内存 H2（`create-drop`）。

## 七、API 一览

| 方法 | 路径 | 角色 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/events` | BILLER | 上报用量事件（幂等） |
| POST | `/api/plans` | ADMIN | 创建方案草稿 |
| POST | `/api/plans/{code}/versions/{v}/commit` | ADMIN | 提交方案版本 |
| POST | `/api/plans/assignments` | ADMIN | 分配方案版本给账户 |
| POST | `/api/billing/preview` | BILLER | 预览出账（不持久化） |
| POST | `/api/billing/commit` | BILLER | 提交出账（幂等） |
| POST | `/api/billing/adjust` | ADMIN | 更正（保留差异） |
| POST | `/api/billing/seal` | ADMIN | 封账 |
| GET | `/api/billing/bills/{id}` | BILLER | 查询账单 |
| GET | `/api/billing/bills/current` | BILLER | 查询当前版本账单 |
| GET | `/api/billing/bills` | BILLER | 查询历史版本链 |
| GET | `/api/billing/adjustments` | BILLER | 查询调整记录 |
| GET | `/api/billing/window` | BILLER | 查询窗口状态 |
| GET | `/api/audit` | BILLER | 查询审计日志 |

## 八、测试覆盖

- `PricingEngineTest`：阶梯价边界、折扣、最低消费、封顶、取整、可复现；
- `DeterminismTest`：重复事件、乱序到达、并发出账、任意处理顺序逐项一致；
- `LateEventAdjustmentTest`：跨窗口归属、迟到事件、封账不被改写、更正差异、预览语义、窗口边界；
- `PlanVersioningTest`：草稿不可出账、方案切换、历史账单不变、不跨版本混合；
- `RecoveryTest`：中断恢复（有账单/无账单）、幂等恢复；
- `PermissionTest`：越权拒绝、角色隔离；
- `ConcurrencyAndAuditTest`：跨账户并行、审计追溯；
- `WebApiTest`：REST 全流程、403、重复事件幂等。
