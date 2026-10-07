package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ConflictException;
import com.github.cuixudong74.usagebillingpipelinejava.model.Bill;
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
import com.github.cuixudong74.usagebillingpipelinejava.service.BillingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.EventService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PlanService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PricingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.UsageAggregationService;
import com.github.cuixudong74.usagebillingpipelinejava.storage.BillingStateStore;
import com.github.cuixudong74.usagebillingpipelinejava.storage.JsonFileStore;
import com.github.cuixudong74.usagebillingpipelinejava.storage.RecoveryStateStore;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.AuthorizationService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountScopedDeduplicationTest {
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-01T00:00:00Z");
    private static final Actor ADMIN = new Actor("admin", Set.of("ADMIN"));

    @TempDir
    Path tempDir;

    @Test
    void sameEventIdForDifferentAccountsIsCountedForEveryAccount() {
        TestContext ctx = context();
        Instant when = Instant.parse("2026-01-02T00:00:00Z");

        UsageEvent forA = new UsageEvent("shared-id", "acct-a", "api_calls", when,
                Instant.now(), new BigDecimal("5"), new TreeMap<>());
        UsageEvent forB = new UsageEvent("shared-id", "acct-b", "api_calls", when,
                Instant.now(), new BigDecimal("7"), new TreeMap<>());
        UsageEvent forBRedelivered = new UsageEvent("shared-id", "acct-b", "api_calls", when,
                null, new BigDecimal("7"), new TreeMap<>());

        ctx.eventService.ingest(forA, ADMIN);
        ctx.eventService.ingest(forB, ADMIN);
        // Same-account redelivery is still deduplicated.
        ctx.eventService.ingest(forBRedelivered, ADMIN);

        assertEquals(1, ctx.eventRepository.findByAccount("acct-a").size());
        assertEquals(1, ctx.eventRepository.findByAccount("acct-b").size());

        Bill billA = ctx.billingService.commit("acct-a", JAN, FEB, "a", ADMIN);
        Bill billB = ctx.billingService.commit("acct-b", JAN, FEB, "b", ADMIN);

        assertEquals(new BigDecimal("5.00"), billA.totalAmount());
        assertEquals(new BigDecimal("7.00"), billB.totalAmount());
        assertEquals(List.of("shared-id"), billA.lines().get(0).eventIds());
        assertEquals(List.of("shared-id"), billB.lines().get(0).eventIds());
        assertNotEquals(billA.billId(), billB.billId());
    }

    @Test
    void sameAccountDuplicateEventIdWithDifferentPayloadIsRejectedClearly() {
        TestContext ctx = context();
        UsageEvent original = new UsageEvent("dup-id", "acct-a", "api_calls",
                Instant.parse("2026-01-02T00:00:00Z"), Instant.now(),
                new BigDecimal("5"), new TreeMap<>());
        UsageEvent conflicting = new UsageEvent("dup-id", "acct-a", "api_calls",
                Instant.parse("2026-01-03T00:00:00Z"), Instant.now(),
                new BigDecimal("9"), new TreeMap<>());

        ctx.eventService.ingest(original, ADMIN);
        ConflictException error = assertThrows(ConflictException.class,
                () -> ctx.eventService.ingest(conflicting, ADMIN));
        // The rejection reason must name both the event/account and the payload mismatch.
        assertEquals(true, error.getMessage().contains("dup-id"));
        assertEquals(true, error.getMessage().contains("acct-a"));
        assertEquals(true, error.getMessage().contains("different business payload"));

        // Only the original event is stored and billed.
        assertEquals(1, ctx.eventRepository.findByAccount("acct-a").size());
        Bill bill = ctx.billingService.commit("acct-a", JAN, FEB, "a", ADMIN);
        assertEquals(new BigDecimal("5.00"), bill.totalAmount());
    }

    @Test
    void sameAccountIdenticalRedeliveryIsIdempotentAcrossDimensions() {
        TestContext ctx = context();
        TreeMap<String, String> dimensions = new TreeMap<>();
        Instant when = Instant.parse("2026-01-02T00:00:00Z");
        UsageEvent first = new UsageEvent("e1", "acct-a", "api_calls", when,
                Instant.parse("2026-01-02T01:00:00Z"), new BigDecimal("3"), dimensions);
        UsageEvent replay = new UsageEvent("e1", "acct-a", "api_calls", when,
                Instant.parse("2026-01-05T01:00:00Z"), new BigDecimal("3"),
                new TreeMap<>());

        ctx.eventService.ingest(first, ADMIN);
        ctx.eventService.ingest(replay, ADMIN); // receivedAt differs: still a replay

        assertEquals(1, ctx.eventRepository.findByAccount("acct-a").size());
        Bill bill = ctx.billingService.commit("acct-a", JAN, FEB, "a", ADMIN);
        assertEquals(new BigDecimal("3.00"), bill.totalAmount());
        assertEquals(1, bill.lines().get(0).eventIds().size());
    }

    private TestContext context() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonFileStore jsonStore = new JsonFileStore(tempDir.resolve("json"), mapper);
        BillingStateStore billingStateStore = new BillingStateStore(
                tempDir.resolve("state/billing.json"), mapper);
        RecoveryStateStore recoveryStateStore = new RecoveryStateStore(
                tempDir.resolve("state/recovery.json"), mapper);
        var eventRepository = new FileEventRepository(jsonStore);
        var planRepository = new FilePlanRepository(jsonStore);
        var billRepository = new FileBillRepository(billingStateStore);
        var auditRepository = new FileAuditRepository(billingStateStore);
        var adjustmentRepository = new FileAdjustmentRepository(billingStateStore);
        var checkpointRepository = new FileCheckpointRepository(billingStateStore);
        var authorization = new AuthorizationService();
        var auditService = new AuditService(auditRepository, authorization);
        var locks = new AccountLocks();
        var planService = new PlanService(planRepository, authorization, auditService, locks);
        var eventService = new EventService(eventRepository, authorization, auditService, locks);
        var billingService = new BillingService(
                eventRepository, billRepository, billingStateStore, recoveryStateStore,
                planService, new UsageAggregationService(), new PricingService(),
                authorization, auditService, locks);
        var adjustmentService = new AdjustmentService(billRepository, adjustmentRepository, billingService);
        for (String accountId : List.of("acct-a", "acct-b")) {
            PricingRule rule = new PricingRule("api_calls", new TreeMap<>(),
                    List.of(new PricingTier(null, BigDecimal.ONE)), null, null, null);
            planService.commit(new PricingPlanVersion("plan", accountId, 1, JAN, null,
                    Instant.now(), List.of(rule)), ADMIN);
        }
        return new TestContext(eventService, billingService, adjustmentService, eventRepository);
    }

    private record TestContext(
            EventService eventService,
            BillingService billingService,
            AdjustmentService adjustmentService,
            FileEventRepository eventRepository) {
    }
}
