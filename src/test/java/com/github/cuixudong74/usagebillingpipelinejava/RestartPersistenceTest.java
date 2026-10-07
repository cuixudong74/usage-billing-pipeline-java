package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.model.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingRule;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingTier;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FileAdjustmentRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FileAuditRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FileBillRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FileEventRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FilePlanRepository;
import com.github.cuixudong74.usagebillingpipelinejava.service.AccountLocks;
import com.github.cuixudong74.usagebillingpipelinejava.service.AdjustmentService;
import com.github.cuixudong74.usagebillingpipelinejava.service.AuditService;
import com.github.cuixudong74.usagebillingpipelinejava.service.BillingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.EventService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PlanService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PricingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.RecoveryService;
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

/**
 * Simulates a full process restart: repositories/services are recreated from the same on-disk
 * files, and a recompute after restart must produce an item-for-item identical bill.
 */
class RestartPersistenceTest {
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-01T00:00:00Z");
    private static final Actor ADMIN = new Actor("admin", Set.of("ADMIN"));

    @TempDir
    Path tempDir;

    @Test
    void billRecomputedAfterRestartIsItemForItemIdentical() {
        // Seed a complete plan version first.
        Services seed = services();
        seed.planService.commit(new PricingPlanVersion("plan", "acct", 1, JAN, null, Instant.now(),
                List.of(
                        new PricingRule("api_calls", new TreeMap<>(Map.of("region", "cn")),
                                List.of(new PricingTier(null, BigDecimal.ONE)), null, null, null),
                        new PricingRule("api_calls", new TreeMap<>(Map.of("region", "us")),
                                List.of(new PricingTier(null, BigDecimal.ONE)), null, null, null),
                        new PricingRule("storage", new TreeMap<>(),
                                List.of(new PricingTier(null, BigDecimal.ONE)), null, null, null))),
                ADMIN);

        Services first = services();
        first.eventService.ingest(new UsageEvent("e1", "acct", "api_calls",
                Instant.parse("2026-01-04T00:00:00Z"), Instant.now(), new BigDecimal("3"),
                new TreeMap<>(Map.of("region", "cn"))), ADMIN);
        first.eventService.ingest(new UsageEvent("e2", "acct", "api_calls",
                Instant.parse("2026-01-02T00:00:00Z"), Instant.now(), new BigDecimal("2"),
                new TreeMap<>(Map.of("region", "us"))), ADMIN);
        first.eventService.ingest(new UsageEvent("e3", "acct", "storage",
                Instant.parse("2026-01-03T00:00:00Z"), Instant.now(), new BigDecimal("4"),
                new TreeMap<>()), ADMIN);

        Bill preview = first.billingService.preview("acct", JAN, FEB, "pv", ADMIN);
        Bill committed = first.billingService.commit("acct", JAN, FEB, "cm", ADMIN);
        assertEquals(lineSignature(committed), lineSignature(preview));

        // "Restart": brand new repository/service instances reading the same files.
        Services second = services();
        Bill afterRestart = second.billingService.preview("acct", JAN, FEB, "pv2", ADMIN);
        Bill current = second.billingService.current("acct", JAN, FEB, ADMIN);

        assertEquals(lineSignature(committed), lineSignature(afterRestart));
        assertEquals(lineSignature(committed), lineSignature(current));
        assertEquals(committed.totalAmount(), current.totalAmount());
        assertEquals(committed.billId(), current.billId());

        second.recoveryService.recover();
        Bill afterRecovery = second.billingService.current("acct", JAN, FEB, ADMIN);
        assertEquals(committed.billId(), afterRecovery.billId());
        assertEquals(1, second.billRepository.findByAccount("acct").size());
    }

    private String lineSignature(Bill bill) {
        StringBuilder builder = new StringBuilder();
        bill.lines().forEach(line -> builder
                .append(line.key().metric()).append('#')
                .append(line.key().dimensions()).append('#')
                .append(line.quantity()).append('#')
                .append(line.netAmount()).append('#')
                .append(line.eventIds()).append('|'));
        return builder.toString();
    }

    private Services services() {
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
        var authorization = new AuthorizationService();
        var auditService = new AuditService(auditRepository, authorization);
        var locks = new AccountLocks();
        var planService = new PlanService(planRepository, authorization, auditService, locks);
        var eventService = new EventService(eventRepository, authorization, auditService, locks);
        var billingService = new BillingService(
                eventRepository, billRepository, billingStateStore, recoveryStateStore,
                planService, new UsageAggregationService(), new PricingService(),
                authorization, auditService, locks);
        var recoveryService = new RecoveryService(recoveryStateStore, billingService);
        var adjustmentService = new AdjustmentService(billRepository, adjustmentRepository, billingService);
        return new Services(eventService, billingService, adjustmentService, recoveryService,
                billRepository, planService);
    }

    private record Services(
            EventService eventService,
            BillingService billingService,
            AdjustmentService adjustmentService,
            RecoveryService recoveryService,
            FileBillRepository billRepository,
            PlanService planService) {
    }
}
