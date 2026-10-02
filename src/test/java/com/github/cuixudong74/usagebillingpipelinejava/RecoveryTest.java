package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.model.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.model.RecoveryState;
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

class RecoveryTest {
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-01T00:00:00Z");
    private static final Actor ADMIN = new Actor("admin", Set.of("ADMIN"));

    @TempDir
    Path tempDir;

    @Test
    void interruptedMarkerWithoutBillIsDistinguishedFromCommittedBill() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonFileStore jsonStore = new JsonFileStore(tempDir.resolve("json"), mapper);
        BillingStateStore billingStateStore = new BillingStateStore(tempDir.resolve("state/billing.json"), mapper);
        RecoveryStateStore recoveryStateStore = new RecoveryStateStore(tempDir.resolve("state/recovery.json"), mapper);
        var events = new FileEventRepository(jsonStore);
        var plans = new FilePlanRepository(jsonStore);
        var bills = new FileBillRepository(billingStateStore);
        var audits = new FileAuditRepository(billingStateStore);
        var adjustments = new FileAdjustmentRepository(billingStateStore);
        var checkpoints = new FileCheckpointRepository(billingStateStore);
        var authorization = new AuthorizationService();
        var auditService = new AuditService(audits, authorization);
        var planService = new PlanService(plans, authorization, auditService);
        var eventService = new EventService(events, authorization, auditService);
        var billingService = new BillingService(
                events, bills, billingStateStore, recoveryStateStore,
                planService, new UsageAggregationService(), new PricingService(),
                authorization, auditService, new AccountLocks());
        var recoveryService = new RecoveryService(recoveryStateStore, checkpoints);
        planService.commit(new com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion(
                "plan", "acct", 1, JAN, null, Instant.now(),
                List.of(new com.github.cuixudong74.usagebillingpipelinejava.model.PricingRule(
                        "api_calls", new TreeMap<>(),
                        List.of(new com.github.cuixudong74.usagebillingpipelinejava.model.PricingTier(
                                null, BigDecimal.ONE)), null, null, null))), ADMIN);

        recoveryStateStore.put(new RecoveryState("aborted", JAN, FEB, "IN_PROGRESS", null,
                Instant.now(), "biller"));
        eventService.ingest(new UsageEvent("e1", "committed", "api_calls",
                Instant.parse("2026-01-02T00:00:00Z"), Instant.now(), BigDecimal.ONE, new TreeMap<>()), ADMIN);
        planService.commit(new com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion(
                "plan", "committed", 1, JAN, null, Instant.now(),
                List.of(new com.github.cuixudong74.usagebillingpipelinejava.model.PricingRule(
                        "api_calls", new TreeMap<>(),
                        List.of(new com.github.cuixudong74.usagebillingpipelinejava.model.PricingTier(
                                null, BigDecimal.ONE)), null, null, null))), ADMIN);
        Bill committed = billingService.commit("committed", JAN, FEB, "ok", ADMIN);
        recoveryStateStore.put(new RecoveryState("committed", JAN, FEB, "IN_PROGRESS",
                committed.billId(), Instant.now(), "biller"));

        Map<String, String> status = recoveryService.recover();

        assertEquals("INTERRUPTED", status.get(RecoveryStateStore.key("aborted", JAN, FEB)));
        assertEquals("COMMITTED", status.get(RecoveryStateStore.key("committed", JAN, FEB)));
        assertEquals(1, bills.findByAccount("committed").size());
        assertEquals(committed.totalAmount(), bills.findById(committed.billId()).orElseThrow().totalAmount());
    }
}
