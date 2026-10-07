package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ConflictException;
import com.github.cuixudong74.usagebillingpipelinejava.model.Adjustment;
import com.github.cuixudong74.usagebillingpipelinejava.model.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.model.BillStatus;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingRule;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingTier;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FileAdjustmentRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FileAuditRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FileBillRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FileCheckpointRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FileEventRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FilePlanRepository;
import com.github.cuixudong74.usagebillingpipelinejava.service.AccountLocks;
import com.github.cuixudong74.usagebillingpipelinejava.service.AdjustmentService;
import com.github.cuixudong74.usagebillingpipelinejava.service.AuditService;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.AuthorizationService;
import com.github.cuixudong74.usagebillingpipelinejava.service.BillingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.EventService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PlanService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PricingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.UsageAggregationService;
import com.github.cuixudong74.usagebillingpipelinejava.storage.BillingStateStore;
import com.github.cuixudong74.usagebillingpipelinejava.storage.JsonFileStore;
import com.github.cuixudong74.usagebillingpipelinejava.storage.RecoveryStateStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BillingFlowTest {
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-01T00:00:00Z");
    private static final Actor ADMIN = new Actor("admin", Set.of("ADMIN"));

    @TempDir
    Path tempDir;

    @Test
    void sealedBillRequiresExplicitCorrectionAndKeepsOriginalPlanVersion() {
        TestContext context = context();
        UsageEvent firstEvent = event("e1", "2026-01-02T00:00:00Z");
        context.eventService.ingest(firstEvent, ADMIN);
        UsageEvent redelivered = new UsageEvent(
                firstEvent.eventId(), firstEvent.accountId(), firstEvent.metric(),
                firstEvent.eventTime(), null, firstEvent.quantity(), firstEvent.dimensions());
        context.eventService.ingest(redelivered, ADMIN);

        Bill first = context.billingService.commit("acct", JAN, FEB, "key-1", ADMIN);
        assertEquals(new BigDecimal("1.00"), first.totalAmount());
        assertEquals(1, first.planVersion());

        Bill duplicate = context.billingService.commit("acct", JAN, FEB, "another-key", ADMIN);
        assertEquals(first.billId(), duplicate.billId());

        context.eventService.ingest(event("e2", "2026-01-03T00:00:00Z"), ADMIN);
        Bill currentBeforeCorrection = context.billingService.current("acct", JAN, FEB, ADMIN);
        assertEquals(first.billId(), currentBeforeCorrection.billId());
        assertEquals(new BigDecimal("1.00"), currentBeforeCorrection.totalAmount());

        Adjustment adjustment = context.adjustmentService.correct(first.billId(), "late event", ADMIN);
        Bill adjusted = context.billingService.current("acct", JAN, FEB, ADMIN);
        Bill original = context.billRepository.findById(first.billId()).orElseThrow();

        assertEquals(new BigDecimal("1.00"), adjustment.amountDelta());
        assertEquals(BillStatus.SUPERSEDED, original.status());
        assertEquals(BillStatus.ADJUSTED, adjusted.status());
        assertEquals(new BigDecimal("2.00"), adjusted.totalAmount());
        assertEquals(1, adjusted.planVersion());
        assertNotEquals(first.billId(), adjusted.billId());
    }

    @Test
    void laterPlanVersionAppliesToLaterWindowAndMixedWindowsAreRejected() {
        TestContext context = context();
        context.eventService.ingest(event("e1", "2026-01-02T00:00:00Z"), ADMIN);
        context.eventService.ingest(event("e2", "2026-02-02T00:00:00Z"), ADMIN);

        Bill january = context.billingService.commit("acct", JAN, FEB, "jan", ADMIN);
        Bill february = context.billingService.commit("acct", FEB, MAR, "feb", ADMIN);

        assertEquals(1, january.planVersion());
        assertEquals(new BigDecimal("1.00"), january.totalAmount());
        assertEquals(2, february.planVersion());
        assertEquals(new BigDecimal("2.00"), february.totalAmount());
        assertThrows(ConflictException.class,
                () -> context.billingService.preview("acct", JAN, MAR, "mixed", ADMIN));
    }

    private TestContext context() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonFileStore jsonStore = new JsonFileStore(tempDir.resolve("json"), mapper);
        BillingStateStore billingStateStore = new BillingStateStore(
                tempDir.resolve("state/billing-state.json"), mapper);
        RecoveryStateStore recoveryStateStore = new RecoveryStateStore(
                tempDir.resolve("state/recovery-state.json"), mapper);
        var eventRepository = new FileEventRepository(jsonStore);
        var planRepository = new FilePlanRepository(jsonStore);
        var billRepository = new FileBillRepository(billingStateStore);
        var auditRepository = new FileAuditRepository(billingStateStore);
        var adjustmentRepository = new FileAdjustmentRepository(billingStateStore);
        var checkpointRepository = new FileCheckpointRepository(billingStateStore);
        var authorizationService = new AuthorizationService();
        var auditService = new AuditService(auditRepository, authorizationService);
        var planService = new PlanService(planRepository, authorizationService, auditService,
                new AccountLocks());
        var eventService = new EventService(eventRepository, authorizationService, auditService,
                new AccountLocks());
        var pricingService = new PricingService();
        var billingService = new BillingService(
                eventRepository,
                billRepository,
                billingStateStore,
                recoveryStateStore,
                planService,
                new UsageAggregationService(),
                pricingService,
                authorizationService,
                auditService,
                new AccountLocks());
        var adjustmentService = new AdjustmentService(billRepository, adjustmentRepository, billingService);
        planService.commit(plan(1, JAN, FEB, "1.00"), ADMIN);
        planService.commit(plan(2, FEB, null, "2.00"), ADMIN);
        return new TestContext(eventService, billingService, adjustmentService, billRepository);
    }

    private PricingPlanVersion plan(int version, Instant from, Instant to, String price) {
        PricingRule rule = new PricingRule(
                "api_calls",
                new TreeMap<>(),
                List.of(new PricingTier(null, new BigDecimal(price))),
                null,
                null,
                null);
        return new PricingPlanVersion(
                "plan", "acct", version, from, to, Instant.now(),
                List.of(rule));
    }

    private UsageEvent event(String id, String time) {
        return new UsageEvent(
                id, "acct", "api_calls", Instant.parse(time), Instant.now(),
                BigDecimal.ONE, new TreeMap<>());
    }

    private record TestContext(
            EventService eventService,
            BillingService billingService,
            AdjustmentService adjustmentService,
            com.github.cuixudong74.usagebillingpipelinejava.repository.FileBillRepository billRepository) {
    }
}
