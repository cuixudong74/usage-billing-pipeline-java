package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountIsolationTest {
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-01T00:00:00Z");
    private static final Actor ADMIN = new Actor("admin", Set.of("ADMIN"));

    @TempDir
    Path tempDir;

    @Test
    void slowAccountDoesNotBlockOtherAccounts() throws Exception {
        TestContext ctx = context();
        ctx.eventService.ingest(event("slow", "e-slow"), ADMIN);
        ctx.eventService.ingest(event("fast", "e-fast"), ADMIN);

        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        AtomicLong fastFinishedAt = new AtomicLong(Long.MAX_VALUE);
        AtomicLong slowStartedAt = new AtomicLong();

        Thread slow = new Thread(() -> {
            ctx.accountLocks.forAccount("slow").lock();
            try {
                slowStartedAt.set(System.nanoTime());
                slowStarted.countDown();
                assertTrue(releaseSlow.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ctx.accountLocks.forAccount("slow").unlock();
            }
        }, "hold-slow-lock");

        Thread fast = new Thread(() -> {
            try {
                slowStarted.await();
                // While "slow"'s account lock is held, operations for "fast" must proceed.
                UsageEvent extra = new UsageEvent("e-fast-2", "fast", "api_calls",
                        Instant.parse("2026-01-03T00:00:00Z"), Instant.now(),
                        BigDecimal.ONE, new TreeMap<>());
                ctx.eventService.ingest(extra, ADMIN);
                Bill bill = ctx.billingService.commit("fast", JAN, FEB, "fast-key", ADMIN);
                fastFinishedAt.set(System.nanoTime());
                assertEquals(new BigDecimal("2.00"), bill.totalAmount());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "fast-account");

        slow.start();
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
        fast.start();
        fast.join(5_000);
        // The fast account completed (ingest + commit) while the slow account lock was still held.
        assertNotEquals(Long.MAX_VALUE, fastFinishedAt.get());
        releaseSlow.countDown();
        slow.join(5_000);
        assertTrue(fastFinishedAt.get() > slowStartedAt.get());

        // Afterwards the slow account can still complete its own billing independently.
        Bill slowBill = ctx.billingService.commit("slow", JAN, FEB, "slow-key", ADMIN);
        assertEquals(new BigDecimal("1.00"), slowBill.totalAmount());
        assertEquals(1, ctx.billRepository.findByAccount("slow").size());
        assertEquals(1, ctx.billRepository.findByAccount("fast").size());
    }

    @Test
    void recoveringMultipleAccountsRunsConcurrentlyAndCompletesAll() {
        TestContext ctx = context();
        for (String account : List.of("r1", "r2", "r3")) {
            ctx.eventService.ingest(event(account, "e-" + account), ADMIN);
            ctx.recoveryStateStore.put(new com.github.cuixudong74.usagebillingpipelinejava.model.RecoveryState(
                    account, JAN, FEB, "IN_PROGRESS", null, Instant.now(), "biller",
                    com.github.cuixudong74.usagebillingpipelinejava.model.RecoveryState.OP_COMMIT,
                    "k-" + account, null));
        }

        Map<String, String> status = ctx.recoveryService.recover();

        for (String account : List.of("r1", "r2", "r3")) {
            assertEquals("COMMITTED",
                    status.get(RecoveryStateStore.key(account, JAN, FEB)));
            Bill bill = ctx.billingService.current(account, JAN, FEB, ADMIN);
            assertEquals(new BigDecimal("1.00"), bill.totalAmount());
            assertEquals(1, ctx.billRepository.findByAccount(account).size());
        }
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
        var recoveryService = new RecoveryService(recoveryStateStore, billingService);
        var adjustmentService = new AdjustmentService(billRepository, adjustmentRepository, billingService);
        for (String accountId : List.of("slow", "fast", "r1", "r2", "r3")) {
            PricingRule rule = new PricingRule("api_calls", new TreeMap<>(),
                    List.of(new PricingTier(null, BigDecimal.ONE)), null, null, null);
            planService.commit(new PricingPlanVersion("plan", accountId, 1, JAN, null,
                    Instant.now(), List.of(rule)), ADMIN);
        }
        return new TestContext(eventService, billingService, adjustmentService, recoveryService,
                recoveryStateStore, billRepository, locks);
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
            FileBillRepository billRepository,
            AccountLocks accountLocks) {
    }
}
