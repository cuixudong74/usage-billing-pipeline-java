package com.github.cuixudong74.usagebillingpipelinejava.tests;

import com.github.cuixudong74.usagebillingpipelinejava.context.Operator;
import com.github.cuixudong74.usagebillingpipelinejava.context.OperatorContext;
import com.github.cuixudong74.usagebillingpipelinejava.domain.Adjustment;
import com.github.cuixudong74.usagebillingpipelinejava.domain.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.domain.PricingPlan;
import com.github.cuixudong74.usagebillingpipelinejava.domain.WindowState;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 窗口与调整测试：
 * - 事件按 eventTime 归属窗口（跨窗口迟到事件归属确定）；
 * - 封账窗口不被悄悄改写；
 * - 更正走显式调整并保留差异。
 */
@SpringBootTest
class LateEventAdjustmentTest {

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
    void eventAttributedByEventTime_notIngestionTime() {
        setupPlan("acc-attr");
        // 9 月事件，但在 10 月才上报（迟到）
        Instant sep25 = Instant.parse("2026-09-25T00:00:00Z");
        Instant oct05 = Instant.parse("2026-10-05T00:00:00Z");
        eventIngestionService.ingest("sep-event", "acc-attr", "api", new BigDecimal("100"), sep25, "test");
        eventIngestionService.ingest("oct-event", "acc-attr", "api", new BigDecimal("50"), oct05, "test");

        Bill sepBill = billingService.commit("acc-attr", "2026-09", "tester");
        Bill octBill = billingService.commit("acc-attr", "2026-10", "tester");

        // 9 月账单只含 9 月事件（100），10 月账单只含 10 月事件（50）
        assertEquals(0, new BigDecimal("100.00").compareTo(sepBill.getTotalAmount()));
        assertEquals(0, new BigDecimal("50.00").compareTo(octBill.getTotalAmount()));
        assertEquals(1L, sepBill.getEventCount());
        assertEquals(1L, octBill.getEventCount());
    }

    @Test
    void lateEventAfterSeal_doesNotRewriteSealedBill_requiresAdjustment() {
        setupPlan("acc-late");
        Instant sep10 = Instant.parse("2026-09-10T00:00:00Z");
        eventIngestionService.ingest("e1", "acc-late", "api", new BigDecimal("100"), sep10, "test");

        Bill v1 = billingService.commit("acc-late", "2026-09", "tester");
        assertEquals(0, new BigDecimal("100.00").compareTo(v1.getTotalAmount()));

        // 封账
        WindowState sealed = billingService.seal("acc-late", "2026-09", "tester");
        assertEquals(WindowState.Status.SEALED, sealed.getStatus());

        // 封账后迟到事件到达（超期补报）
        eventIngestionService.ingest("e2-late", "acc-late", "api", new BigDecimal("40"), sep10, "test");

        // 已封账单不被悄悄改写：v1 金额不变
        Bill current = billingService.getCurrentBill("acc-late", "2026-09");
        assertEquals(0, new BigDecimal("100.00").compareTo(current.getTotalAmount()));
        assertEquals(1, current.getBillVersion());

        // 封账窗口直接提交被拒绝
        assertThrows(Exception.class, () -> billingService.commit("acc-late", "2026-09", "tester"));

        // 显式调整：生成新版本并保留差异
        Bill v2 = billingService.adjust("acc-late", "2026-09", "迟到事件补录", "tester");
        assertEquals(2, v2.getBillVersion());
        assertEquals(Bill.Status.ADJUSTED, v2.getStatus());
        assertEquals(0, new BigDecimal("140.00").compareTo(v2.getTotalAmount()));
        assertEquals(0, new BigDecimal("40.00").compareTo(
                v2.getTotalAmount().subtract(v1.getTotalAmount())));
        assertEquals(v1.getId(), v2.getPreviousBillId());

        // 调整记录保留前后差异
        List<Adjustment> adjustments = billingService.listAdjustments("acc-late", "2026-09");
        assertEquals(1, adjustments.size());
        Adjustment adj = adjustments.get(0);
        assertEquals(0, new BigDecimal("100.00").compareTo(adj.getBeforeAmount()));
        assertEquals(0, new BigDecimal("140.00").compareTo(adj.getAfterAmount()));
        assertEquals(0, new BigDecimal("40.00").compareTo(adj.getDiffAmount()));
        assertNotNull(adj.getBeforeSnapshot());
        assertTrue(adj.getBeforeSnapshot().contains("100.00"));

        // 历史版本链完整
        List<Bill> history = billingService.listHistory("acc-late", "2026-09");
        assertEquals(2, history.size());
        assertEquals(1, history.get(0).getBillVersion());
        assertEquals(2, history.get(1).getBillVersion());
    }

    @Test
    void preview_doesNotChangeCommittedState() {
        setupPlan("acc-preview");
        Instant sep10 = Instant.parse("2026-09-10T00:00:00Z");
        eventIngestionService.ingest("e1", "acc-preview", "api", new BigDecimal("100"), sep10, "test");

        Bill preview = billingService.preview("acc-preview", "2026-09");
        assertTrue(preview.isPreview());
        assertEquals(0, new BigDecimal("100.00").compareTo(preview.getTotalAmount()));

        // 预览不产生已提交账单
        assertThrows(Exception.class, () -> billingService.getCurrentBill("acc-preview", "2026-09"));

        // 提交后预览仍标记 preview
        Bill committed = billingService.commit("acc-preview", "2026-09", "tester");
        Bill preview2 = billingService.preview("acc-preview", "2026-09");
        assertTrue(preview2.isPreview());
        assertEquals(committed.getId(), billingService.getCurrentBill("acc-preview", "2026-09").getId());
    }

    @Test
    void windowBoundaries_monthAlignment() {
        // 验证窗口边界：2026-09-01 00:00 UTC 至 2026-10-01 00:00 UTC
        Instant start = windowService.windowStart("2026-09");
        Instant end = windowService.windowEnd("2026-09");
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), start);
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), end);
        // 边界事件：8 月 31 日 23:59:59 属于 8 月；9 月 1 日 00:00 属于 9 月
        assertEquals("2026-08", windowService.windowIdFor(Instant.parse("2026-08-31T23:59:59Z")));
        assertEquals("2026-09", windowService.windowIdFor(Instant.parse("2026-09-01T00:00:00Z")));
    }
}
