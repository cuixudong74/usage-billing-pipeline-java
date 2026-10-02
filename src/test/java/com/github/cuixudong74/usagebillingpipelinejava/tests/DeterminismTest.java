package com.github.cuixudong74.usagebillingpipelinejava.tests;

import com.github.cuixudong74.usagebillingpipelinejava.context.Operator;
import com.github.cuixudong74.usagebillingpipelinejava.context.OperatorContext;
import com.github.cuixudong74.usagebillingpipelinejava.domain.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.domain.PricingPlan;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.PricingRules;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.Tier;
import com.github.cuixudong74.usagebillingpipelinejava.service.BillingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.EventIngestionService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PlanService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 确定性测试：重复事件、乱序到达、并发出账都不改变账单结果。
 */
@SpringBootTest
class DeterminismTest {

    @Autowired
    private PlanService planService;
    @Autowired
    private EventIngestionService eventIngestionService;
    @Autowired
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        OperatorContext.set(new Operator("tester", Set.of("BILLER", "ADMIN")));
    }

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    private int setupPlan(String accountId) {
        PricingRules rules = new PricingRules();
        rules.setCurrency("CNY");
        rules.setTiers(List.of(
                new Tier(new BigDecimal("100"), new BigDecimal("1.00")),
                new Tier(new BigDecimal("500"), new BigDecimal("0.80")),
                new Tier(null, new BigDecimal("0.60"))));
        PricingPlan draft = planService.createDraft("STD", Instant.parse("2026-01-01T00:00:00Z"),
                null, rules, "tester");
        planService.commitPlan(draft.getPlanCode(), draft.getVersion());
        planService.assignPlan(accountId, "STD", draft.getVersion(),
                Instant.parse("2026-01-01T00:00:00Z"), null, "tester");
        return draft.getVersion();
    }

    @Test
    void duplicateEvents_areIdempotent() {
        setupPlan("acc-dup");
        Instant t = Instant.parse("2026-09-10T00:00:00Z");
        eventIngestionService.ingest("acc-dup-e1", "acc-dup", "api", new BigDecimal("100"), t, "test");
        // 重复上报同 eventId
        eventIngestionService.ingest("acc-dup-e1", "acc-dup", "api", new BigDecimal("100"), t, "test");
        eventIngestionService.ingest("acc-dup-e1", "acc-dup", "api", new BigDecimal("100"), t, "test");

        Bill bill = billingService.commit("acc-dup", "2026-09", "tester");
        assertEquals(1L, bill.getEventCount());
        assertEquals(0, new BigDecimal("100.00").compareTo(bill.getTotalAmount()));
    }

    @Test
    void outOfOrderArrival_sameResultAsChronological() {
        setupPlan("acc-order");
        Instant t1 = Instant.parse("2026-09-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-09-10T00:00:00Z");
        Instant t3 = Instant.parse("2026-09-20T00:00:00Z");

        // 乱序摄入
        eventIngestionService.ingest("acc-order-e3", "acc-order", "api", new BigDecimal("200"), t3, "test");
        eventIngestionService.ingest("acc-order-e1", "acc-order", "api", new BigDecimal("100"), t1, "test");
        eventIngestionService.ingest("acc-order-e2", "acc-order", "api", new BigDecimal("300"), t2, "test");

        Bill bill = billingService.commit("acc-order", "2026-09", "tester");
        // 与时间顺序单次处理一致：600 -> 480
        assertEquals(0, new BigDecimal("480.00").compareTo(bill.getTotalAmount()));
        assertEquals(3L, bill.getEventCount());
    }

    @Test
    void concurrentCommits_sameAccount_produceOneBill() throws Exception {
        setupPlan("acc-conc");
        Instant t = Instant.parse("2026-09-10T00:00:00Z");
        for (int i = 0; i < 50; i++) {
            eventIngestionService.ingest("acc-conc-e" + i, "acc-conc", "api",
                    new BigDecimal("10"), t.plusSeconds(i), "test");
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Operator operator = OperatorContext.get();
        List<Future<Bill>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                OperatorContext.set(operator);
                try {
                    start.await();
                    return billingService.commit("acc-conc", "2026-09", "tester");
                } finally {
                    OperatorContext.clear();
                }
            }));
        }
        start.countDown();
        List<Bill> results = new ArrayList<>();
        for (Future<Bill> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();

        // 所有线程拿到同一份账单
        long distinctIds = results.stream().map(Bill::getId).distinct().count();
        assertEquals(1, distinctIds);
        assertEquals(1, results.get(0).getBillVersion());
        // 50 * 10 = 500 -> 100*1 + 400*0.8 = 420
        assertEquals(0, new BigDecimal("420.00").compareTo(results.get(0).getTotalAmount()));
    }

    @Test
    void sameInput_anyProcessingOrder_itemWiseIdentical() {
        // 构造两批相同事件，顺序完全不同，逐项比对账单
        setupPlan("acc-a");
        setupPlan("acc-b");
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        List<String> eventIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            eventIds.add("evt-" + i);
        }
        // acc-a：正序
        for (int i = 0; i < eventIds.size(); i++) {
            eventIngestionService.ingest("acc-a-" + eventIds.get(i), "acc-a", "api",
                    new BigDecimal("5").add(new BigDecimal(i)), base.plusSeconds(i * 60L), "test");
        }
        // acc-b：倒序
        List<String> reversed = new ArrayList<>(eventIds);
        Collections.reverse(reversed);
        for (int i = 0; i < reversed.size(); i++) {
            int idx = 20 - 1 - i;
            eventIngestionService.ingest("acc-b-" + reversed.get(i), "acc-b", "api",
                    new BigDecimal("5").add(new BigDecimal(idx)), base.plusSeconds(idx * 60L), "test");
        }

        Bill a = billingService.commit("acc-a", "2026-09", "tester");
        Bill b = billingService.commit("acc-b", "2026-09", "tester");

        assertEquals(0, a.getTotalAmount().compareTo(b.getTotalAmount()));
        assertEquals(a.getEventCount(), b.getEventCount());
        assertEquals(0, a.getTotalQuantity().compareTo(b.getTotalQuantity()));
        // 逐项行项目一致
        assertEquals(a.getLineItems().size(), b.getLineItems().size());
        for (int i = 0; i < a.getLineItems().size(); i++) {
            assertEquals(0, a.getLineItems().get(i).getAmount()
                    .compareTo(b.getLineItems().get(i).getAmount()));
            assertEquals(0, a.getLineItems().get(i).getQuantity()
                    .compareTo(b.getLineItems().get(i).getQuantity()));
        }
    }
}
