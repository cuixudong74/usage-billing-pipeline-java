package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.model.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.model.BillStatus;
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
    void interruptedCommitWithoutBillIsAutomaticallyResumedToCompletion() {
        TestContext ctx = context("resumed");
        ctx.eventService.ingest(event("resumed", "e1"), ADMIN);

        // Simulate a crash after writing the IN_PROGRESS marker but before any bill/checkpoint.
        ctx.recoveryStateStore.put(new RecoveryState("resumed", JAN, FEB, "IN_PROGRESS", null,
                Instant.now(), "biller", RecoveryState.OP_COMMIT, "lost-key", null));

        Map<String, String> status = ctx.recoveryService.recover();

        String key = RecoveryStateStore.key("resumed", JAN, FEB);
        assertEquals("COMMITTED", status.get(key));
        List<Bill> bills = ctx.bills.findByAccount("resumed");
        assertEquals(1, bills.size());
        Bill bill = bills.get(0);
        assertEquals(BillStatus.COMMITTED, bill.status());
        assertEquals(new BigDecimal("1.00"), bill.totalAmount());
        // The resumed bill is queryable as the current bill and the checkpoint exists.
        assertTrue(ctx.billingService.current("resumed", JAN, FEB, ADMIN).billId().equals(bill.billId()));
        assertTrue(ctx.checkpoints.find("resumed", JAN, FEB).isPresent());
    }

    @Test
    void recoveryAfterBillWasWrittenDoesNotCreateSecondBill() {
        TestContext ctx = context("committed");
        ctx.eventService.ingest(event("committed", "e1"), ADMIN);
        Bill committed = ctx.billingService.commit("committed", JAN, FEB, "ok", ADMIN);
        assertEquals(1, ctx.bills.findByAccount("committed").size());

        // Simulate a crash between atomic bill write and flipping the marker to COMMITTED.
        ctx.recoveryStateStore.put(new RecoveryState("committed", JAN, FEB, "IN_PROGRESS",
                committed.billId(), Instant.now(), "biller", RecoveryState.OP_COMMIT, "ok", null));

        Map<String, String> status = ctx.recoveryService.recover();

        assertEquals("COMMITTED", status.get(RecoveryStateStore.key("committed", JAN, FEB)));
        List<Bill> bills = ctx.bills.findByAccount("committed");
        assertEquals(1, bills.size());
        assertEquals(committed.totalAmount(), bills.get(0).totalAmount());
        assertEquals(committed.billId(), bills.get(0).billId());
    }

    @Test
    void alreadyCommittedWindowsStaySealedDuringRecovery() {
        TestContext ctx = context("sealed");
        ctx.eventService.ingest(event("sealed", "e1"), ADMIN);
        Bill committed = ctx.billingService.commit("sealed", JAN, FEB, "ok", ADMIN);

        ctx.recoveryService.recover();

        Bill stillSealed = ctx.billingService.current("sealed", JAN, FEB, ADMIN);
        assertEquals(committed.billId(), stillSealed.billId());
        assertEquals(BillStatus.COMMITTED, stillSealed.status());
        assertEquals(1, ctx.bills.findByAccount("sealed").size());
    }

    @Test
    void interruptedAdjustmentIsResumedWithoutDuplicateCharging() {
        TestContext ctx = context("adjusting");
        ctx.eventService.ingest(event("adjusting", "e1"), ADMIN);
        Bill first = ctx.billingService.commit("adjusting", JAN, FEB, "key", ADMIN);
        // A late event arrives after the window was sealed; a correction was started but crashed.
        ctx.eventService.ingest(new UsageEvent("e2", "adjusting", "api_calls",
                Instant.parse("2026-01-03T00:00:00Z"), Instant.now(),
                java.math.BigDecimal.ONE, new TreeMap<>()), ADMIN);

        ctx.recoveryStateStore.put(new RecoveryState("adjusting", JAN, FEB, "IN_PROGRESS",
                first.billId(), Instant.now(), "biller", RecoveryState.OP_ADJUST, "key",
                "late event after close"));

        Map<String, String> status = ctx.recoveryService.recover();

        assertEquals("COMMITTED",
                status.get(RecoveryStateStore.key("adjusting", JAN, FEB)));
        Bill current = ctx.billingService.current("adjusting", JAN, FEB, ADMIN);
        assertEquals(BillStatus.ADJUSTED, current.status());
        assertEquals(new java.math.BigDecimal("2.00"), current.totalAmount());
        assertEquals(2, current.billVersion());
        // Original bill is kept and marked superseded; no third bill exists.
        Bill original = ctx.bills.findById(first.billId()).orElseThrow();
        assertEquals(BillStatus.SUPERSEDED, original.status());
        long billsForWindow = ctx.bills.findByAccount("adjusting").size();
        assertEquals(2, billsForWindow);
        // A second recovery run must not produce another version or double charge.
        ctx.recoveryService.recover();
        Bill stillCurrent = ctx.billingService.current("adjusting", JAN, FEB, ADMIN);
        assertEquals(current.billId(), stillCurrent.billId());
        assertEquals(2, ctx.bills.findByAccount("adjusting").size());
    }

    private TestContext context(String accountId) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonFileStore jsonStore = new JsonFileStore(tempDir.resolve(accountId + "/json"), mapper);
        BillingStateStore billingStateStore = new BillingStateStore(
                tempDir.resolve(accountId + "/state/billing.json"), mapper);
        RecoveryStateStore recoveryStateStore = new RecoveryStateStore(
                tempDir.resolve(accountId + "/state/recovery.json"), mapper);
        var events = new FileEventRepository(jsonStore);
        var plans = new FilePlanRepository(jsonStore);
        var bills = new FileBillRepository(billingStateStore);
        var audits = new FileAuditRepository(billingStateStore);
        var adjustments = new FileAdjustmentRepository(billingStateStore);
        var checkpoints = new FileCheckpointRepository(billingStateStore);
        var authorization = new AuthorizationService();
        var auditService = new AuditService(audits, authorization);
        var planService = new PlanService(plans, authorization, auditService, new AccountLocks());
        var eventService = new EventService(events, authorization, auditService, new AccountLocks());
        var billingService = new BillingService(
                events, bills, billingStateStore, recoveryStateStore,
                planService, new UsageAggregationService(), new PricingService(),
                authorization, auditService, new AccountLocks());
        var recoveryService = new RecoveryService(recoveryStateStore, billingService);
        var adjustmentService = new AdjustmentService(bills, adjustments, billingService);
        planService.commit(new com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion(
                "plan", accountId, 1, JAN, null, Instant.now(),
                List.of(new com.github.cuixudong74.usagebillingpipelinejava.model.PricingRule(
                        "api_calls", new TreeMap<>(),
                        List.of(new com.github.cuixudong74.usagebillingpipelinejava.model.PricingTier(
                                null, BigDecimal.ONE)), null, null, null))), ADMIN);
        return new TestContext(eventService, billingService, adjustmentService, recoveryService,
                recoveryStateStore, bills, checkpoints);
    }

    private UsageEvent event(String accountId, String id) {
        return new UsageEvent(id, accountId, "api_calls",
                Instant.parse("2026-01-02T00:00:00Z"), Instant.now(), BigDecimal.ONE, new TreeMap<>());
    }

    private record TestContext(
            EventService eventService,
            BillingService billingService,
            AdjustmentService adjustmentService,
            RecoveryService recoveryService,
            RecoveryStateStore recoveryStateStore,
            FileBillRepository bills,
            FileCheckpointRepository checkpoints) {
    }
}
