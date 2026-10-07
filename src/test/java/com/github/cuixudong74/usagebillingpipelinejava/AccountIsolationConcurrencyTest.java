package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.AuthorizationService;
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
import com.github.cuixudong74.usagebillingpipelinejava.service.UsageAggregationService;
import com.github.cuixudong74.usagebillingpipelinejava.storage.BillingStateStore;
import com.github.cuixudong74.usagebillingpipelinejava.storage.JsonFileStore;
import com.github.cuixudong74.usagebillingpipelinejava.storage.RecoveryStateStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountIsolationConcurrencyTest {
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-01T00:00:00Z");
    private static final Actor ADMIN = new Actor("admin", Set.of("ADMIN"));

    @TempDir
    Path tempDir;

    @Test
    void slowCommitForOneAccountDoesNotBlockAnotherAccount() throws Exception {
        Path directory = tempDir.resolve("isolated");
        Files.createDirectories(directory);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonFileStore jsonStore = new JsonFileStore(directory.resolve("json"), mapper);
        BillingStateStore stateStore = new BillingStateStore(directory.resolve("billing.json"), mapper);
        RecoveryStateStore recoveryStore = new RecoveryStateStore(directory.resolve("recovery.json"), mapper);
        var delegate = new FileEventRepository(jsonStore);
        var blockingEvents = new BlockingEventRepository(delegate);
        var plans = new FilePlanRepository(jsonStore);
        var bills = new FileBillRepository(stateStore);
        var audits = new FileAuditRepository(stateStore);
        var adjustments = new FileAdjustmentRepository(stateStore);
        var authorization = new AuthorizationService();
        var auditService = new AuditService(audits, authorization);
        var planService = new PlanService(plans, authorization, auditService);
        var accountLocks = new AccountLocks();
        var eventService = new EventService(blockingEvents, authorization, auditService, accountLocks);
        var billingService = new BillingService(
                blockingEvents, bills, stateStore, recoveryStore, planService,
                new UsageAggregationService(), new PricingService(), authorization, auditService, accountLocks);
        var adjustmentService = new AdjustmentService(bills, adjustments, billingService);

        planService.commit(plan("slow-acct"), ADMIN);
        planService.commit(plan("fast-acct"), ADMIN);
        eventService.ingest(event("slow-acct", "slow-event"), ADMIN);
        eventService.ingest(event("fast-acct", "fast-event"), ADMIN);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Bill> slowCommit = pool.submit(
                    () -> billingService.commit("slow-acct", JAN, FEB, "slow", ADMIN));
            assertTrue(blockingEvents.slowAccountReadStarted.await(5, TimeUnit.SECONDS));

            Future<UsageEvent> fastIngest = pool.submit(() -> {
                UsageEvent event = event("fast-acct", "fast-event-2");
                return eventService.ingest(event, ADMIN);
            });
            Future<Bill> fastCommit = pool.submit(
                    () -> billingService.commit("fast-acct", JAN, FEB, "fast", ADMIN));

            assertEquals(event("fast-acct", "fast-event-2").eventId(),
                    fastIngest.get(5, TimeUnit.SECONDS).eventId());
            Bill fastBill = fastCommit.get(5, TimeUnit.SECONDS);
            assertEquals(new BigDecimal("2.00"), fastBill.totalAmount());

            blockingEvents.allowSlowAccountRead.countDown();
            Bill slowBill = slowCommit.get(5, TimeUnit.SECONDS);
            assertEquals(new BigDecimal("1.00"), slowBill.totalAmount());
        } finally {
            blockingEvents.allowSlowAccountRead.countDown();
            pool.shutdownNow();
        }
    }

    private PricingPlanVersion plan(String accountId) {
        PricingRule rule = new PricingRule("api_calls", new TreeMap<>(),
                List.of(new PricingTier(null, BigDecimal.ONE)), null, null, null);
        return new PricingPlanVersion("plan", accountId, 1, JAN, null, Instant.now(), List.of(rule));
    }

    private UsageEvent event(String accountId, String eventId) {
        return new UsageEvent(eventId, accountId, "api_calls",
                Instant.parse("2026-01-02T00:00:00Z"), Instant.now(),
                BigDecimal.ONE, new TreeMap<>());
    }

    private static final class BlockingEventRepository implements com.github.cuixudong74.usagebillingpipelinejava.repository.EventRepository {
        private final FileEventRepository delegate;
        private final CountDownLatch slowAccountReadStarted = new CountDownLatch(1);
        private final CountDownLatch allowSlowAccountRead = new CountDownLatch(1);

        private BlockingEventRepository(FileEventRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<UsageEvent> findByAccountAndEventId(String accountId, String eventId) {
            return delegate.findByAccountAndEventId(accountId, eventId);
        }

        @Override
        public UsageEvent save(UsageEvent event) {
            return delegate.save(event);
        }

        @Override
        public List<UsageEvent> findByAccount(String accountId) {
            if ("slow-acct".equals(accountId)) {
                slowAccountReadStarted.countDown();
                try {
                    allowSlowAccountRead.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.findByAccount(accountId);
        }
    }
}
