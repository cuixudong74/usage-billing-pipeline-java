package com.github.cuixudong74.usagebillingpipelinejava.tests;

import com.github.cuixudong74.usagebillingpipelinejava.context.Operator;
import com.github.cuixudong74.usagebillingpipelinejava.context.OperatorContext;
import com.github.cuixudong74.usagebillingpipelinejava.domain.AuditLog;
import com.github.cuixudong74.usagebillingpipelinejava.domain.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.domain.PricingPlan;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.PricingRules;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.Tier;
import com.github.cuixudong74.usagebillingpipelinejava.repository.AuditLogRepository;
import com.github.cuixudong74.usagebillingpipelinejava.service.BillingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.EventIngestionService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PlanService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与审计测试：
 * - 不同账户出账互不阻塞（并行推进）；
 * - 每次金额变动可追溯到来源事件与方案版本。
 */
@SpringBootTest
class ConcurrencyAndAuditTest {

    @Autowired
    private PlanService planService;
    @Autowired
    private EventIngestionService eventIngestionService;
    @Autowired
    private BillingService billingService;
    @Autowired
    private AuditLogRepository auditLogRepository;

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
    void differentAccounts_commitInParallel_notBlockedByOthers() throws Exception {
        int accountCount = 6;
        for (int a = 0; a < accountCount; a++) {
            String acc = "acc-par-" + a;
            setupPlan(acc);
            for (int i = 0; i < 10; i++) {
                eventIngestionService.ingest(acc + "-e" + i, acc, "api",
                        new BigDecimal("10"), Instant.parse("2026-09-10T00:00:00Z").plusSeconds(i), "test");
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(accountCount);
        CountDownLatch start = new CountDownLatch(1);
        Operator operator = OperatorContext.get();
        List<Future<Bill>> futures = new ArrayList<>();
        for (int a = 0; a < accountCount; a++) {
            final String acc = "acc-par-" + a;
            futures.add(pool.submit(() -> {
                OperatorContext.set(operator);
                try {
                    start.await();
                    return billingService.commit(acc, "2026-09", "tester");
                } finally {
                    OperatorContext.clear();
                }
            }));
        }
        start.countDown();

        for (Future<Bill> f : futures) {
            Bill bill = f.get(30, TimeUnit.SECONDS);
            assertNotNull(bill);
            // 每账户 10 * 10 = 100
            assertEquals(0, new BigDecimal("100.00").compareTo(bill.getTotalAmount()));
        }
        pool.shutdown();

        // 每账户恰好一份账单
        for (int a = 0; a < accountCount; a++) {
            String acc = "acc-par-" + a;
            assertEquals(1, billingService.listHistory(acc, "2026-09").size());
        }
    }

    @Test
    void auditTrail_tracesAmountChangeToEventsAndPlanVersion() {
        setupPlan("acc-audit");
        Instant sep10 = Instant.parse("2026-09-10T00:00:00Z");
        eventIngestionService.ingest("au1", "acc-audit", "api", new BigDecimal("100"), sep10, "test");

        Bill bill = billingService.commit("acc-audit", "2026-09", "tester");

        // 账单可追溯到事件
        assertNotNull(bill.getEventIds());
        assertFalse(bill.getEventIds().isEmpty());
        assertTrue(bill.getEventIds().contains("au1"));
        // 账单记录方案版本
        assertNotNull(bill.getPlanCode());
        assertNotNull(bill.getPlanVersion());
        assertNotNull(bill.getRulesSnapshot());

        // 审计日志记录出账操作
        List<AuditLog> logs = auditLogRepository.findByAccountIdAndWindowIdOrderByIdAsc(
                "acc-audit", "2026-09");
        boolean hasCommit = logs.stream()
                .anyMatch(l -> "COMMIT_BILL".equals(l.getAction()) && "SUCCESS".equals(l.getResult()));
        assertTrue(hasCommit);
        // 审计详情包含金额
        AuditLog commitLog = logs.stream()
                .filter(l -> "COMMIT_BILL".equals(l.getAction())).findFirst().orElseThrow();
        assertTrue(commitLog.getDetails().contains("totalAmount"));
        assertTrue(commitLog.getOperator().equals("tester"));
    }
}
