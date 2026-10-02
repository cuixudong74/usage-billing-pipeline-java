package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.domain.Adjustment;
import com.github.cuixudong74.usagebillingpipelinejava.domain.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.domain.BillLineItem;
import com.github.cuixudong74.usagebillingpipelinejava.domain.BillingTask;
import com.github.cuixudong74.usagebillingpipelinejava.domain.PricingPlan;
import com.github.cuixudong74.usagebillingpipelinejava.domain.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.domain.WindowState;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.NotFoundException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.WindowSealedException;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.PricingEngine;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.PricingRules;
import com.github.cuixudong74.usagebillingpipelinejava.repository.AdjustmentRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.BillRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.BillingTaskRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.UsageEventRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.WindowStateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 出账服务：提交、预览、更正、封账。
 *
 * 核心不变量：
 * 1. 账单由窗口内全量事件集合计算得出，与事件到达顺序/并发度无关（确定性）；
 * 2. 已提交账单不可变；封账后不得悄悄改写，更正走显式调整并保留差异；
 * 3. 同一 (account, window) 重复提交幂等，只产生一份当前账单；
 * 4. 所有写操作在同一事务内（账单+行项目+任务+审计），无半写状态；
 * 5. 账户级锁串行化同账户出账，不同账户互不阻塞。
 */
@Service
public class BillingService {

    private final BillRepository billRepository;
    private final BillingTaskRepository taskRepository;
    private final UsageEventRepository eventRepository;
    private final WindowStateRepository windowStateRepository;
    private final AdjustmentRepository adjustmentRepository;
    private final WindowService windowService;
    private final PlanService planService;
    private final PricingEngine pricingEngine;
    private final PermissionChecker permissionChecker;
    private final AuditService auditService;
    private final AccountLockManager lockManager;
    private final TransactionTemplate transactionTemplate;

    public BillingService(BillRepository billRepository,
                          BillingTaskRepository taskRepository,
                          UsageEventRepository eventRepository,
                          WindowStateRepository windowStateRepository,
                          AdjustmentRepository adjustmentRepository,
                          WindowService windowService,
                          PlanService planService,
                          PricingEngine pricingEngine,
                          PermissionChecker permissionChecker,
                          AuditService auditService,
                          AccountLockManager lockManager,
                          TransactionTemplate transactionTemplate) {
        this.billRepository = billRepository;
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.windowStateRepository = windowStateRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.windowService = windowService;
        this.planService = planService;
        this.pricingEngine = pricingEngine;
        this.permissionChecker = permissionChecker;
        this.auditService = auditService;
        this.lockManager = lockManager;
        this.transactionTemplate = transactionTemplate;
    }

    // ============================= 提交出账 =============================

    /**
     * 提交出账（幂等）。窗口已封账则拒绝（需走调整）。
     */
    public Bill commit(String accountId, String windowId, String operator) {
        permissionChecker.requireRole("BILLER", AuditService.ACTION_COMMIT_BILL, accountId, windowId);
        return lockManager.callLocked(accountId, () ->
                transactionTemplate.execute(status -> doCommit(accountId, windowId, operator)));
    }

    private Bill doCommit(String accountId, String windowId, String operator) {
        WindowState windowState = getOrCreateWindowState(accountId, windowId);
        if (windowState.getStatus() == WindowState.Status.SEALED) {
            throw new WindowSealedException("窗口 " + accountId + ":" + windowId + " 已封账，请走调整");
        }

        // 幂等：已有当前版本直接返回
        Bill existing = billRepository.findTopByAccountIdAndWindowIdOrderByBillVersionDesc(accountId, windowId)
                .orElse(null);
        if (existing != null) {
            markTaskCommitted(accountId, windowId, operator, existing);
            return existing;
        }

        BillingTask task = getOrCreateTask(accountId, windowId, BillingTask.TaskType.COMMIT, operator);
        task.setStatus(BillingTask.Status.RUNNING);
        task.setStartedAt(Instant.now());
        task = taskRepository.save(task);

        Computation comp = compute(accountId, windowId);
        Bill bill = buildBill(accountId, windowId, operator, 1, Bill.Status.COMMITTED, null, null, comp);
        bill = billRepository.save(bill);

        task.setStatus(BillingTask.Status.COMMITTED);
        task.setFinishedAt(Instant.now());
        task.setCursorEventTime(comp.maxEventTime());
        task.setCursorEventCount((long) comp.events().size());
        taskRepository.save(task);

        // 超过宽限期自动封账（同事务）
        if (windowService.isPastGrace(windowId, Instant.now())) {
            windowState.setStatus(WindowState.Status.SEALED);
            windowState.setSealedAt(Instant.now());
            windowState.setSealedBy("system:auto");
            windowStateRepository.save(windowState);
        }

        auditService.recordSuccess(AuditService.ACTION_COMMIT_BILL, "BILL", bill.getBillNo(),
                accountId, windowId,
                Map.of("billVersion", 1, "totalAmount", bill.getTotalAmount().toPlainString(),
                        "planCode", bill.getPlanCode(), "planVersion", bill.getPlanVersion(),
                        "eventCount", bill.getEventCount()));
        return bill;
    }

    // ============================= 预览 =============================

    /**
     * 预览：只读计算，不持久化。返回的账单标记 preview=true，不是已提交版本。
     */
    public Bill preview(String accountId, String windowId) {
        permissionChecker.requireRole("BILLER", "PREVIEW_BILL", accountId, windowId);
        Computation comp = compute(accountId, windowId);
        Bill bill = buildBill(accountId, windowId, "preview", 0, Bill.Status.COMMITTED, null, null, comp);
        bill.setPreview(true);
        WindowState ws = windowStateRepository.findByAccountIdAndWindowId(accountId, windowId).orElse(null);
        bill.setSealed(ws != null && ws.getStatus() == WindowState.Status.SEALED);
        return bill;
    }

    // ============================= 更正 =============================

    /**
     * 更正：基于窗口内全量事件（含迟到/补报）重新计算，生成新版本账单，
     * 保留调整前后差异。原账单不被修改。
     */
    public Bill adjust(String accountId, String windowId, String reason, String operator) {
        permissionChecker.requireRole("ADMIN", AuditService.ACTION_ADJUST_BILL, accountId, windowId);
        return lockManager.callLocked(accountId, () ->
                transactionTemplate.execute(status -> doAdjust(accountId, windowId, reason, operator)));
    }

    private Bill doAdjust(String accountId, String windowId, String reason, String operator) {
        Bill previous = billRepository.findTopByAccountIdAndWindowIdOrderByBillVersionDesc(accountId, windowId)
                .orElseThrow(() -> new NotFoundException("账户 " + accountId + " 窗口 " + windowId
                        + " 无已提交账单，请先出账"));

        BillingTask task = getOrCreateTask(accountId, windowId, BillingTask.TaskType.ADJUST, operator);
        task.setStatus(BillingTask.Status.RUNNING);
        task.setStartedAt(Instant.now());
        taskRepository.save(task);

        Computation comp = compute(accountId, windowId);
        int newVersion = previous.getBillVersion() + 1;
        Bill newBill = buildBill(accountId, windowId, operator, newVersion, Bill.Status.ADJUSTED,
                previous.getId(), reason, comp);
        newBill = billRepository.save(newBill);

        BigDecimal diff = newBill.getTotalAmount().subtract(previous.getTotalAmount());

        Adjustment adjustment = new Adjustment();
        adjustment.setBillId(newBill.getId());
        adjustment.setPreviousBillId(previous.getId());
        adjustment.setAccountId(accountId);
        adjustment.setWindowId(windowId);
        adjustment.setBeforeAmount(previous.getTotalAmount());
        adjustment.setAfterAmount(newBill.getTotalAmount());
        adjustment.setDiffAmount(diff);
        adjustment.setReason(reason);
        adjustment.setOperator(operator);
        adjustment.setCreatedAt(Instant.now());
        adjustment.setBeforeSnapshot(planService.writeSnapshot(previous.getLineItems().stream()
                .map(li -> Map.of("dimension", li.getDimension(), "amount", li.getAmount().toPlainString(),
                        "quantity", li.getQuantity().toPlainString()))
                .toList()));
        adjustmentRepository.save(adjustment);

        task.setStatus(BillingTask.Status.COMMITTED);
        task.setFinishedAt(Instant.now());
        task.setCursorEventTime(comp.maxEventTime());
        task.setCursorEventCount((long) comp.events().size());
        taskRepository.save(task);

        // 调整后若仍超期，保持封账状态
        WindowState ws = getOrCreateWindowState(accountId, windowId);
        if (windowService.isPastGrace(windowId, Instant.now())) {
            ws.setStatus(WindowState.Status.SEALED);
            ws.setSealedAt(Instant.now());
            ws.setSealedBy(ws.getSealedBy() != null ? ws.getSealedBy() : operator);
            windowStateRepository.save(ws);
        }

        auditService.recordSuccess(AuditService.ACTION_ADJUST_BILL, "BILL", newBill.getBillNo(),
                accountId, windowId,
                Map.of("billVersion", newVersion,
                        "beforeAmount", previous.getTotalAmount().toPlainString(),
                        "afterAmount", newBill.getTotalAmount().toPlainString(),
                        "diffAmount", diff.toPlainString(),
                        "reason", reason == null ? "" : reason));
        return newBill;
    }

    // ============================= 封账 =============================

    /**
     * 封账：窗口进入 SEALED 状态。若尚无账单，先按当前事件出账。
     */
    public WindowState seal(String accountId, String windowId, String operator) {
        permissionChecker.requireRole("ADMIN", AuditService.ACTION_SEAL_WINDOW, accountId, windowId);
        return lockManager.callLocked(accountId, () ->
                transactionTemplate.execute(status -> doSeal(accountId, windowId, operator)));
    }

    private WindowState doSeal(String accountId, String windowId, String operator) {
        WindowState ws = getOrCreateWindowState(accountId, windowId);
        if (ws.getStatus() == WindowState.Status.SEALED) {
            return ws;
        }
        // 封账前确保存在已提交账单
        Bill existing = billRepository.findTopByAccountIdAndWindowIdOrderByBillVersionDesc(accountId, windowId)
                .orElse(null);
        if (existing == null) {
            doCommit(accountId, windowId, operator);
            ws = getOrCreateWindowState(accountId, windowId);
        }
        ws.setStatus(WindowState.Status.SEALED);
        ws.setSealedAt(Instant.now());
        ws.setSealedBy(operator);
        ws = windowStateRepository.save(ws);
        auditService.recordSuccess(AuditService.ACTION_SEAL_WINDOW, "WINDOW_STATE",
                accountId + ":" + windowId, accountId, windowId,
                Map.of("sealedBy", operator, "sealedAt", ws.getSealedAt().toString()));
        return ws;
    }

    // ============================= 查询 =============================

    public Bill getBill(Long billId) {
        return billRepository.findById(billId)
                .orElseThrow(() -> new NotFoundException("账单不存在: id=" + billId));
    }

    public Bill getCurrentBill(String accountId, String windowId) {
        return billRepository.findTopByAccountIdAndWindowIdOrderByBillVersionDesc(accountId, windowId)
                .orElseThrow(() -> new NotFoundException("账户 " + accountId + " 窗口 " + windowId + " 无已提交账单"));
    }

    public List<Bill> listHistory(String accountId, String windowId) {
        return billRepository.findByAccountIdAndWindowIdOrderByBillVersionAsc(accountId, windowId);
    }

    public List<Adjustment> listAdjustments(String accountId, String windowId) {
        return adjustmentRepository.findByAccountIdAndWindowIdOrderByIdAsc(accountId, windowId);
    }

    public WindowState getWindowState(String accountId, String windowId) {
        return windowStateRepository.findByAccountIdAndWindowId(accountId, windowId).orElse(null);
    }

    // ============================= 内部 =============================

    private record Computation(List<UsageEvent> events, Map<String, BigDecimal> quantities,
                               PricingPlan plan, PricingEngine.PricingResult result,
                               Instant maxEventTime) {
    }

    private Computation compute(String accountId, String windowId) {
        Instant start = windowService.windowStart(windowId);
        Instant end = windowService.windowEnd(windowId);
        List<UsageEvent> events = eventRepository.findInWindow(accountId, start, end);

        Map<String, BigDecimal> quantities = new TreeMap<>();
        Instant maxTime = null;
        for (UsageEvent e : events) {
            quantities.merge(e.getDimension(), e.getQuantity(), BigDecimal::add);
            if (maxTime == null || e.getEventTime().isAfter(maxTime)) {
                maxTime = e.getEventTime();
            }
        }

        PricingPlan plan = planService.resolvePlan(accountId, start);
        PricingEngine.PricingResult result = pricingEngine.calculate(plan.getRules(), quantities);
        return new Computation(events, quantities, plan, result, maxTime);
    }

    private Bill buildBill(String accountId, String windowId, String operator, int billVersion,
                           Bill.Status status, Long previousBillId, String reason, Computation comp) {
        Bill bill = new Bill();
        bill.setBillNo("BILL-" + accountId + "-" + windowId + "-v" + billVersion);
        bill.setAccountId(accountId);
        bill.setWindowId(windowId);
        bill.setWindowStart(windowService.windowStart(windowId));
        bill.setWindowEnd(windowService.windowEnd(windowId));
        bill.setPlanCode(comp.plan().getPlanCode());
        bill.setPlanVersion(comp.plan().getVersion());
        bill.setStatus(status);
        bill.setBillVersion(billVersion);
        bill.setTotalAmount(comp.result().totalAmount());
        bill.setCurrency(comp.result().currency());
        bill.setEventCount((long) comp.events().size());
        BigDecimal totalQty = comp.quantities().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        bill.setTotalQuantity(totalQty);

        List<BillLineItem> lineItems = new ArrayList<>();
        for (PricingEngine.LineCharge lc : comp.result().lines()) {
            BillLineItem item = new BillLineItem();
            item.setDimension(lc.dimension());
            item.setQuantity(lc.quantity());
            item.setAmount(lc.amount());
            item.setDescription(lc.description());
            lineItems.add(item);
        }
        bill.setLineItems(lineItems);

        bill.setEventIds(comp.events().stream().map(UsageEvent::getEventId).toList());
        bill.setRulesSnapshot(planService.writeRulesSnapshot(comp.plan().getRules()));
        bill.setCommittedAt(Instant.now());
        bill.setOperator(operator);
        bill.setPreviousBillId(previousBillId);
        bill.setAdjustmentReason(reason);
        return bill;
    }

    private WindowState getOrCreateWindowState(String accountId, String windowId) {
        return windowStateRepository.findByAccountIdAndWindowId(accountId, windowId).orElseGet(() -> {
            WindowState ws = new WindowState();
            ws.setAccountId(accountId);
            ws.setWindowId(windowId);
            ws.setStatus(WindowState.Status.OPEN);
            return windowStateRepository.save(ws);
        });
    }

    private BillingTask getOrCreateTask(String accountId, String windowId,
                                        BillingTask.TaskType type, String operator) {
        String key = type.name() + ":" + accountId + ":" + windowId;
        return taskRepository.findByIdempotencyKey(key).orElseGet(() -> {
            BillingTask task = new BillingTask();
            task.setIdempotencyKey(key);
            task.setAccountId(accountId);
            task.setWindowId(windowId);
            task.setTaskType(type);
            task.setStatus(BillingTask.Status.PENDING);
            task.setOperator(operator);
            return taskRepository.save(task);
        });
    }

    /** 幂等返回已有账单时，确保任务状态一致（已提交）。 */
    private void markTaskCommitted(String accountId, String windowId, String operator, Bill bill) {
        String key = BillingTask.TaskType.COMMIT.name() + ":" + accountId + ":" + windowId;
        BillingTask task = taskRepository.findByIdempotencyKey(key).orElseGet(() -> {
            BillingTask t = new BillingTask();
            t.setIdempotencyKey(key);
            t.setAccountId(accountId);
            t.setWindowId(windowId);
            t.setTaskType(BillingTask.TaskType.COMMIT);
            t.setStatus(BillingTask.Status.PENDING);
            t.setOperator(operator);
            return t;
        });
        if (task.getStatus() != BillingTask.Status.COMMITTED) {
            task.setStatus(BillingTask.Status.COMMITTED);
            task.setStartedAt(task.getStartedAt() != null ? task.getStartedAt() : Instant.now());
            task.setFinishedAt(Instant.now());
            task.setCursorEventCount(bill.getEventCount());
            taskRepository.save(task);
        }
    }
}
