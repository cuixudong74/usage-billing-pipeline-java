package com.github.cuixudong74.usagebillingpipelinejava.tests;

import com.github.cuixudong74.usagebillingpipelinejava.context.Operator;
import com.github.cuixudong74.usagebillingpipelinejava.context.OperatorContext;
import com.github.cuixudong74.usagebillingpipelinejava.domain.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.domain.BillingTask;
import com.github.cuixudong74.usagebillingpipelinejava.domain.PricingPlan;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.PricingRules;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.Tier;
import com.github.cuixudong74.usagebillingpipelinejava.repository.BillingTaskRepository;
import com.github.cuixudong74.usagebillingpipelinejava.service.BillingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.EventIngestionService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PlanService;
import com.github.cuixudong74.usagebillingpipelinejava.service.RecoveryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 故障恢复测试：中断任务从最近已提交位置继续，不重复计费、不漏计。
 */
@SpringBootTest
class RecoveryTest {

    @Autowired
    private PlanService planService;
    @Autowired
    private EventIngestionService eventIngestionService;
    @Autowired
    private BillingService billingService;
    @Autowired
    private RecoveryService recoveryService;
    @Autowired
    private BillingTaskRepository taskRepository;

    @BeforeEach
    void setUp() {
        OperatorContext.set(new Operator("tester", Set.of("BILLER", "ADMIN")));
    }

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    private void setupPlan(String accountId) {
        PricingRules rules = new PricingRules();
        rules.setCurrency("CNY");
        rules.setTiers(List.of(new Tier(null, new BigDecimal("1.00"))));
        PricingPlan draft = planService.createDraft("STD", Instant.parse("2026-01-01T00:00:00Z"),
                null, rules, "tester");
        planService.commitPlan(draft.getPlanCode(), draft.getVersion());
        planService.assignPlan(accountId, "STD", draft.getVersion(),
                Instant.parse("2026-01-01T00:00:00Z"), null, "tester");
    }

    @Test
    void interruptedTask_noBill_resumesAndCommits() {
        setupPlan("acc-rec1");
        Instant sep10 = Instant.parse("2026-09-10T00:00:00Z");
        eventIngestionService.ingest("r1", "acc-rec1", "api", new BigDecimal("100"), sep10, "test");

        // 模拟中断：任务停在 RUNNING，且无已提交账单
        BillingTask task = new BillingTask();
        task.setIdempotencyKey("COMMIT:acc-rec1:2026-09");
        task.setAccountId("acc-rec1");
        task.setWindowId("2026-09");
        task.setTaskType(BillingTask.TaskType.COMMIT);
        task.setStatus(BillingTask.Status.RUNNING);
        task.setOperator("tester");
        task.setStartedAt(Instant.now());
        taskRepository.save(task);

        // 执行恢复
        int recovered = recoveryService.recoverInterruptedTasks();
        assertEquals(1, recovered);

        // 恢复后账单已提交，金额正确（不漏计）
        Bill bill = billingService.getCurrentBill("acc-rec1", "2026-09");
        assertNotNull(bill);
        assertEquals(0, new BigDecimal("100.00").compareTo(bill.getTotalAmount()));
        assertEquals(1, bill.getBillVersion());

        // 任务状态为 COMMITTED
        BillingTask after = taskRepository.findByIdempotencyKey("COMMIT:acc-rec1:2026-09").orElseThrow();
        assertEquals(BillingTask.Status.COMMITTED, after.getStatus());
        assertNotNull(after.getFinishedAt());
    }

    @Test
    void interruptedTask_withCommittedBill_marksCommittedNoDuplicate() {
        setupPlan("acc-rec2");
        Instant sep10 = Instant.parse("2026-09-10T00:00:00Z");
        eventIngestionService.ingest("r2", "acc-rec2", "api", new BigDecimal("100"), sep10, "test");

        // 先正常提交
        Bill committed = billingService.commit("acc-rec2", "2026-09", "tester");

        // 模拟任务状态卡在 RUNNING（但账单已存在）
        BillingTask task = taskRepository.findByIdempotencyKey("COMMIT:acc-rec2:2026-09").orElseThrow();
        task.setStatus(BillingTask.Status.RUNNING);
        taskRepository.save(task);

        recoveryService.recoverInterruptedTasks();

        // 不重复出账：仍是同一份账单
        Bill current = billingService.getCurrentBill("acc-rec2", "2026-09");
        assertEquals(committed.getId(), current.getId());
        assertEquals(1, current.getBillVersion());
        assertEquals(1, billingService.listHistory("acc-rec2", "2026-09").size());

        BillingTask after = taskRepository.findByIdempotencyKey("COMMIT:acc-rec2:2026-09").orElseThrow();
        assertEquals(BillingTask.Status.COMMITTED, after.getStatus());
    }

    @Test
    void recovery_isIdempotent_noDoubleBilling() {
        setupPlan("acc-rec3");
        Instant sep10 = Instant.parse("2026-09-10T00:00:00Z");
        eventIngestionService.ingest("r3", "acc-rec3", "api", new BigDecimal("100"), sep10, "test");

        BillingTask task = new BillingTask();
        task.setIdempotencyKey("COMMIT:acc-rec3:2026-09");
        task.setAccountId("acc-rec3");
        task.setWindowId("2026-09");
        task.setTaskType(BillingTask.TaskType.COMMIT);
        task.setStatus(BillingTask.Status.PENDING);
        task.setOperator("tester");
        taskRepository.save(task);

        // 连续恢复两次
        recoveryService.recoverInterruptedTasks();
        recoveryService.recoverInterruptedTasks();

        // 只有一份账单，金额正确
        Bill bill = billingService.getCurrentBill("acc-rec3", "2026-09");
        assertEquals(0, new BigDecimal("100.00").compareTo(bill.getTotalAmount()));
        assertEquals(1, billingService.listHistory("acc-rec3", "2026-09").size());
    }
}
