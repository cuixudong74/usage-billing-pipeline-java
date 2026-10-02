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
import com.github.cuixudong74.usagebillingpipelinejava.service.WindowService;
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
 * 端到端出账流程冒烟测试：建方案 -> 提交 -> 分配 -> 摄入事件 -> 出账。
 */
@SpringBootTest
class BillingFlowTest {

    @Autowired
    private PlanService planService;
    @Autowired
    private EventIngestionService eventIngestionService;
    @Autowired
    private BillingService billingService;
    @Autowired
    private WindowService windowService;

    @BeforeEach
    void setUp() {
        OperatorContext.set(new Operator("tester", Set.of("BILLER", "ADMIN")));
    }

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    @Test
    void fullFlow_producesDeterministicBill() {
        // 方案：阶梯价 0-100 @1, 100-500 @0.8, 500+ @0.6；无最低消费/封顶
        PricingRules rules = new PricingRules();
        rules.setCurrency("CNY");
        rules.setTiers(List.of(
                new Tier(new BigDecimal("100"), new BigDecimal("1.00")),
                new Tier(new BigDecimal("500"), new BigDecimal("0.80")),
                new Tier(null, new BigDecimal("0.60"))));

        PricingPlan draft = planService.createDraft("STD", Instant.parse("2026-01-01T00:00:00Z"),
                null, rules, "tester");
        planService.commitPlan(draft.getPlanCode(), draft.getVersion());
        planService.assignPlan("acc-1", "STD", draft.getVersion(),
                Instant.parse("2026-01-01T00:00:00Z"), null, "tester");

        // 摄入 2026-09 月事件（乱序）
        Instant sep10 = Instant.parse("2026-09-10T00:00:00Z");
        Instant sep01 = Instant.parse("2026-09-01T00:00:00Z");
        Instant sep20 = Instant.parse("2026-09-20T00:00:00Z");
        eventIngestionService.ingest("e3", "acc-1", "api", new BigDecimal("200"), sep20, "test");
        eventIngestionService.ingest("e1", "acc-1", "api", new BigDecimal("100"), sep01, "test");
        eventIngestionService.ingest("e2", "acc-1", "api", new BigDecimal("300"), sep10, "test");

        Bill bill = billingService.commit("acc-1", "2026-09", "tester");

        assertNotNull(bill.getId());
        // 数量 600：100*1 + 400*0.8 + 100*0.6 = 100 + 320 + 60 = 480
        assertEquals(new BigDecimal("480.00"), bill.getTotalAmount());
        assertEquals(1, bill.getBillVersion());
        assertEquals(Bill.Status.COMMITTED, bill.getStatus());
        assertEquals(3L, bill.getEventCount());
        assertEquals(0, new BigDecimal("600").compareTo(bill.getTotalQuantity()));
        assertEquals(3, bill.getEventIds().size());
        // 方案版本快照
        assertEquals("STD", bill.getPlanCode());
        assertEquals(draft.getVersion(), bill.getPlanVersion());
        // 窗口边界
        assertEquals(windowService.windowStart("2026-09"), bill.getWindowStart());
        assertEquals(windowService.windowEnd("2026-09"), bill.getWindowEnd());
    }
}
