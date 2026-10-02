package com.github.cuixudong74.usagebillingpipelinejava.tests;

import com.github.cuixudong74.usagebillingpipelinejava.context.Operator;
import com.github.cuixudong74.usagebillingpipelinejava.context.OperatorContext;
import com.github.cuixudong74.usagebillingpipelinejava.domain.PricingPlan;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.AccessDeniedException;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 权限校验测试：越权操作必须被拒绝。
 */
@SpringBootTest
class PermissionTest {

    @Autowired
    private BillingService billingService;
    @Autowired
    private PlanService planService;
    @Autowired
    private EventIngestionService eventIngestionService;

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    private PricingRules rules() {
        PricingRules r = new PricingRules();
        r.setCurrency("CNY");
        r.setTiers(List.of(new Tier(null, new BigDecimal("1.00"))));
        return r;
    }

    @Test
    void billerCannotAdjust() {
        OperatorContext.set(new Operator("bill-user", Set.of("BILLER")));
        // 更正需要 ADMIN，BILLER 越权
        assertThrows(AccessDeniedException.class,
                () -> billingService.adjust("acc-x", "2026-09", "reason", "bill-user"));
    }

    @Test
    void billerCannotSeal() {
        OperatorContext.set(new Operator("bill-user", Set.of("BILLER")));
        assertThrows(AccessDeniedException.class,
                () -> billingService.seal("acc-x", "2026-09", "bill-user"));
    }

    @Test
    void billerCannotCreatePlan() {
        OperatorContext.set(new Operator("bill-user", Set.of("BILLER")));
        assertThrows(AccessDeniedException.class,
                () -> planService.createDraft("P", Instant.parse("2026-01-01T00:00:00Z"),
                        null, rules(), "bill-user"));
    }

    @Test
    void anonymousCannotCommit() {
        OperatorContext.set(Operator.anonymous());
        assertThrows(AccessDeniedException.class,
                () -> billingService.commit("acc-x", "2026-09", "anon"));
    }

    @Test
    void adminCanAdjustAndSeal() {
        OperatorContext.set(new Operator("admin-user", Set.of("ADMIN", "BILLER")));
        // ADMIN 同时有 BILLER 权限：出账、更正、封账均允许（不抛 AccessDenied）
        PricingPlan draft = planService.createDraft("P", Instant.parse("2026-01-01T00:00:00Z"),
                null, rules(), "admin-user");
        planService.commitPlan("P", draft.getVersion());
        planService.assignPlan("acc-admin", "P", draft.getVersion(),
                Instant.parse("2026-01-01T00:00:00Z"), null, "admin-user");
        eventIngestionService.ingest("a1", "acc-admin", "api", new BigDecimal("100"),
                Instant.parse("2026-09-10T00:00:00Z"), "test");

        billingService.commit("acc-admin", "2026-09", "admin-user");
        billingService.seal("acc-admin", "2026-09", "admin-user");
        // 封账后更正
        eventIngestionService.ingest("a2", "acc-admin", "api", new BigDecimal("10"),
                Instant.parse("2026-09-10T00:00:00Z"), "test");
        billingService.adjust("acc-admin", "2026-09", "补录", "admin-user");
    }
}
