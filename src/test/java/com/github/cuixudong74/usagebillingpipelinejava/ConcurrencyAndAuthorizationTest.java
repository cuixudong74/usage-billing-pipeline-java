package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.AuthorizationService;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ForbiddenException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ValidationException;
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
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConcurrencyAndAuthorizationTest {
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-01T00:00:00Z");
    private static final Actor ADMIN = new Actor("admin", Set.of("ADMIN"));

    @TempDir
    Path tempDir;

    @Test
    void repeatedConcurrentCommitForOneAccountProducesOneBill() throws Exception {
        TestContext context = context("acct");
        context.eventService.ingest(event("acct", "e1"), ADMIN);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> billIds = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Bill bill = context.billingService.commit("acct", JAN, FEB, "same-key", ADMIN);
                    billIds.add(bill.billId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(Set.of("bill-acct-1767225600000-1769904000000-v1"), billIds);
        assertEquals(1, context.billRepository.findByAccount("acct").size());
    }

    @Test
    void differentAccountsDoNotShareOneBillingLock() {
        AccountLocks locks = new AccountLocks();
        locks.forAccount("a").lock();
        assertTrue(locks.forAccount("b").tryLock());
        locks.forAccount("b").unlock();
        locks.forAccount("a").unlock();
    }

    @Test
    void unauthorizedActorCannotCommitPlan() {
        AuthorizationService authorizationService = new AuthorizationService();
        Actor eventWriter = new Actor("writer", Set.of("EVENT_WRITER"));
        assertThrows(ForbiddenException.class,
                () -> authorizationService.authorize(eventWriter,
                        com.github.cuixudong74.usagebillingpipelinejava.authorization.Action.COMMIT_PLAN, "acct"));
    }

    @Test
    void missingAuthenticatedActorIsABadRequestNotForbidden() {
        AuthorizationService authorizationService = new AuthorizationService();
        assertThrows(ValidationException.class,
                () -> authorizationService.authorize(
                        new Actor(" ", Set.of("ADMIN")),
                        com.github.cuixudong74.usagebillingpipelinejava.authorization.Action.READ_BILL, "acct"));
    }

    private TestContext context(String accountId) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonFileStore jsonStore = new JsonFileStore(tempDir.resolve("json"), mapper);
        BillingStateStore billingStateStore = new BillingStateStore(tempDir.resolve("state/billing.json"), mapper);
        RecoveryStateStore recoveryStateStore = new RecoveryStateStore(tempDir.resolve("state/recovery.json"), mapper);
        var eventRepository = new FileEventRepository(jsonStore);
        var planRepository = new FilePlanRepository(jsonStore);
        var billRepository = new FileBillRepository(billingStateStore);
        var auditRepository = new FileAuditRepository(billingStateStore);
        var adjustmentRepository = new FileAdjustmentRepository(billingStateStore);
        var checkpointRepository = new FileCheckpointRepository(billingStateStore);
        var authorizationService = new AuthorizationService();
        var auditService = new AuditService(auditRepository, authorizationService);
        var accountLocks = new AccountLocks();
        var planService = new PlanService(planRepository, authorizationService, auditService,
                accountLocks);
        var eventService = new EventService(eventRepository, authorizationService, auditService, accountLocks);
        var billingService = new BillingService(
                eventRepository, billRepository,
                billingStateStore, recoveryStateStore, planService,
                new UsageAggregationService(), new PricingService(),
                authorizationService, auditService, accountLocks);
        var adjustmentService = new AdjustmentService(billRepository, adjustmentRepository, billingService);
        planService.commit(plan(accountId), ADMIN);
        return new TestContext(eventService, billingService, adjustmentService, billRepository);
    }

    private PricingPlanVersion plan(String accountId) {
        PricingRule rule = new PricingRule(
                "api_calls", new TreeMap<>(),
                List.of(new PricingTier(null, BigDecimal.ONE)), null, null, null);
        return new PricingPlanVersion("plan", accountId, 1, JAN, null, Instant.now(),
                List.of(rule));
    }

    private UsageEvent event(String accountId, String id) {
        return new UsageEvent(id, accountId, "api_calls", Instant.parse("2026-01-02T00:00:00Z"),
                Instant.now(), BigDecimal.ONE, new TreeMap<>());
    }

    private record TestContext(
            EventService eventService,
            BillingService billingService,
            AdjustmentService adjustmentService,
            FileBillRepository billRepository) {
    }
}
