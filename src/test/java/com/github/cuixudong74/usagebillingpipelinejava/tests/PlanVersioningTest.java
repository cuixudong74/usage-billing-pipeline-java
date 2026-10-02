package com.github.cuixudong74.usagebillingpipelinejava.tests;

import com.github.cuixudong74.usagebillingpipelinejava.context.Operator;
import com.github.cuixudong74.usagebillingpipelinejava.context.OperatorContext;
import com.github.cuixudong74.usagebillingpipelinejava.domain.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.domain.PricingPlan;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.PlanNotCommittedException;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.PricingRules;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.Tier;
import com.github.cuixudong74.usagebillingpipelinejava.service.BillingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.EventIngestionService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PlanService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案版本化测试：
 * - 只有已提交方案可出账，草稿不可用；
 * - 账户在不同时间段可对应不同方案版本；
 * - 历史账单在方案更新后保持原样（规则快照）。
 */
@SpringBootTest
class PlanVersioningTest {

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

    private PricingRules rules(BigDecimal unitPrice) {
        PricingRules r = new PricingRules();
        r.setCurrency("CNY");
        r.setTiers(List.of(new Tier(null, unitPrice)));
        return r;
    }

    @Test
    void draftPlan_cannotBeUsedForBilling() {
        // 只创建草稿不提交
        planService.createDraft("DRAFT-PLAN", Instant.parse("2026-01-01T00:00:00Z"),
                null, rules(new BigDecimal("1.00")), "tester");
        // 分配未提交方案应被拒绝
        assertThrows(PlanNotCommittedException.class,
                () -> planService.assignPlan("acc-draft", "DRAFT-PLAN", 1,
                        Instant.parse("2026-01-01T00:00:00Z"), null, "tester"));
    }

    @Test
    void accountSwitchesPlanVersions_historicalBillUnchanged() {
        // 方案 v1：单价 1.00；v2：单价 2.00
        PricingPlan v1 = planService.createDraft("STD", Instant.parse("2026-01-01T00:00:00Z"),
                null, rules(new BigDecimal("1.00")), "tester");
        planService.commitPlan("STD", v1.getVersion());
        planService.assignPlan("acc-sw", "STD", v1.getVersion(),
                Instant.parse("2026-01-01T00:00:00Z"), null, "tester");

        Instant jan10 = Instant.parse("2026-01-10T00:00:00Z");
        eventIngestionService.ingest("j1", "acc-sw", "api", new BigDecimal("100"), jan10, "test");

        // 1 月出账：采用 v1，100 * 1.00 = 100
        Bill janBill = billingService.commit("acc-sw", "2026-01", "tester");
        assertEquals(0, new BigDecimal("100.00").compareTo(janBill.getTotalAmount()));
        assertEquals(v1.getVersion(), janBill.getPlanVersion());
        String janSnapshot = janBill.getRulesSnapshot();
        assertTrue(janSnapshot.contains("1.00"));

        // 创建并提交 v2（单价 2.00），2 月起生效
        PricingPlan v2 = planService.createDraft("STD", Instant.parse("2026-02-01T00:00:00Z"),
                null, rules(new BigDecimal("2.00")), "tester");
        planService.commitPlan("STD", v2.getVersion());
        planService.assignPlan("acc-sw", "STD", v2.getVersion(),
                Instant.parse("2026-02-01T00:00:00Z"), null, "tester");

        Instant feb10 = Instant.parse("2026-02-10T00:00:00Z");
        eventIngestionService.ingest("f1", "acc-sw", "api", new BigDecimal("100"), feb10, "test");

        // 2 月出账：采用 v2，100 * 2.00 = 200
        Bill febBill = billingService.commit("acc-sw", "2026-02", "tester");
        assertEquals(0, new BigDecimal("200.00").compareTo(febBill.getTotalAmount()));
        assertEquals(v2.getVersion(), febBill.getPlanVersion());

        // 历史账单（1 月）保持原样：金额、方案版本、规则快照均不变
        Bill janAgain = billingService.getCurrentBill("acc-sw", "2026-01");
        assertEquals(0, new BigDecimal("100.00").compareTo(janAgain.getTotalAmount()));
        assertEquals(v1.getVersion(), janAgain.getPlanVersion());
        assertEquals(janSnapshot, janAgain.getRulesSnapshot());
        assertTrue(janAgain.getRulesSnapshot().contains("1.00"));
    }

    @Test
    void billingUsesCommittedPlanOnly_neverMixesVersions() {
        // 提交 v1
        PricingPlan v1 = planService.createDraft("MIX", Instant.parse("2026-01-01T00:00:00Z"),
                null, rules(new BigDecimal("1.00")), "tester");
        planService.commitPlan("MIX", v1.getVersion());
        planService.assignPlan("acc-mix", "MIX", v1.getVersion(),
                Instant.parse("2026-01-01T00:00:00Z"), null, "tester");

        // v2 仍是草稿（单价 9.99），不得被用于出账
        planService.createDraft("MIX", Instant.parse("2026-01-01T00:00:00Z"),
                null, rules(new BigDecimal("9.99")), "tester");

        Instant jan10 = Instant.parse("2026-01-10T00:00:00Z");
        eventIngestionService.ingest("m1", "acc-mix", "api", new BigDecimal("10"), jan10, "test");

        Bill bill = billingService.commit("acc-mix", "2026-01", "tester");
        // 即使 v2 草稿存在，出账仍用 v1（单价 1.00），不混入 v2 字段
        assertEquals(1, bill.getPlanVersion());
        assertEquals(0, new BigDecimal("10.00").compareTo(bill.getTotalAmount()));
    }
}
