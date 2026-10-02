package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.config.BillingProperties;
import com.github.cuixudong74.usagebillingpipelinejava.domain.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.domain.BillingTask;
import com.github.cuixudong74.usagebillingpipelinejava.domain.WindowState;
import com.github.cuixudong74.usagebillingpipelinejava.repository.BillRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.BillingTaskRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.WindowStateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 故障恢复：任务中断后从最近已提交位置继续。
 *
 * 规则：
 * - 重启后扫描 PENDING/RUNNING 任务（RUNNING 视为可能中断）；
 * - 若该 (account, window) 已存在已提交账单 → 任务直接置 COMMITTED（不重复出账）；
 * - 否则 → 重置状态并重跑 commit（幂等，从全量事件重新计算，不漏计）；
 * - 已封账窗口与未完成出账在持久层状态可区分（WindowState.status + BillingTask.status）。
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final BillingTaskRepository taskRepository;
    private final BillRepository billRepository;
    private final WindowStateRepository windowStateRepository;
    private final BillingService billingService;
    private final WindowService windowService;
    private final BillingProperties properties;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public RecoveryService(BillingTaskRepository taskRepository,
                           BillRepository billRepository,
                           WindowStateRepository windowStateRepository,
                           BillingService billingService,
                           WindowService windowService,
                           BillingProperties properties,
                           AuditService auditService,
                           TransactionTemplate transactionTemplate) {
        this.taskRepository = taskRepository;
        this.billRepository = billRepository;
        this.windowStateRepository = windowStateRepository;
        this.billingService = billingService;
        this.windowService = windowService;
        this.properties = properties;
        this.auditService = auditService;
        this.transactionTemplate = transactionTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        recoverInterruptedTasks();
    }

    /** 恢复中断任务。 */
    public int recoverInterruptedTasks() {
        List<BillingTask> interrupted = taskRepository.findByStatusIn(
                List.of(BillingTask.Status.PENDING, BillingTask.Status.RUNNING));
        int recovered = 0;
        for (BillingTask task : interrupted) {
            try {
                recoverOne(task);
                recovered++;
            } catch (RuntimeException e) {
                log.error("恢复任务失败: key={}", task.getIdempotencyKey(), e);
                transactionTemplate.executeWithoutResult(tx -> {
                    BillingTask t = taskRepository.findById(task.getId()).orElse(null);
                    if (t != null) {
                        t.setStatus(BillingTask.Status.FAILED);
                        t.setErrorMessage(e.getMessage());
                        taskRepository.save(t);
                    }
                });
            }
        }
        if (recovered > 0) {
            log.info("故障恢复完成：处理 {} 个中断任务", recovered);
        }
        return recovered;
    }

    private void recoverOne(BillingTask task) {
        transactionTemplate.executeWithoutResult(tx -> {
            Bill existing = billRepository
                    .findTopByAccountIdAndWindowIdOrderByBillVersionDesc(task.getAccountId(), task.getWindowId())
                    .orElse(null);
            if (existing != null && existing.getStatus() != null) {
                // 已存在已提交版本：任务直接收尾，不重复出账
                task.setStatus(BillingTask.Status.COMMITTED);
                task.setFinishedAt(Instant.now());
                taskRepository.save(task);
                auditService.recordSuccess(AuditService.ACTION_RECOVERY, "BILLING_TASK",
                        task.getIdempotencyKey(), task.getAccountId(), task.getWindowId(),
                        Map.of("action", "markCommitted", "billNo", existing.getBillNo()));
            } else {
                // 无已提交版本：重置为 PENDING，交给下方重跑
                task.setStatus(BillingTask.Status.PENDING);
                task.setStartedAt(null);
                taskRepository.save(task);
            }
        });

        // 无已提交账单：幂等重跑（commit 内部会复用同一任务键）
        Bill current = billRepository
                .findTopByAccountIdAndWindowIdOrderByBillVersionDesc(task.getAccountId(), task.getWindowId())
                .orElse(null);
        if (current == null) {
            billingService.commit(task.getAccountId(), task.getWindowId(), "system:recovery");
        }
    }

    /** 自动封账巡检：超过宽限期仍 OPEN 的窗口自动封账（已有账单则只封账）。 */
    @Scheduled(cron = "${billing.auto-seal-cron:0 0 * * * *}")
    public void autoSealSweep() {
        if (!properties.isAutoSealEnabled()) {
            return;
        }
        Instant now = Instant.now();
        List<WindowState> openWindows = windowStateRepository.findByStatus(WindowState.Status.OPEN);
        for (WindowState ws : openWindows) {
            if (windowService.isPastGrace(ws.getWindowId(), now)) {
                try {
                    billingService.seal(ws.getAccountId(), ws.getWindowId(), "system:auto");
                    log.info("自动封账: account={} window={}", ws.getAccountId(), ws.getWindowId());
                } catch (RuntimeException e) {
                    log.warn("自动封账失败: account={} window={}: {}",
                            ws.getAccountId(), ws.getWindowId(), e.getMessage());
                }
            }
        }
    }
}
