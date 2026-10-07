package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.AuthorizationService;
import com.github.cuixudong74.usagebillingpipelinejava.model.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.model.BillLine;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeterministicBillOrderTest {
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-01T00:00:00Z");
    private static final Actor ADMIN = new Actor("admin", Set.of("ADMIN"));

    @TempDir
    Path tempDir;

    @Test
    void previewCommitAndRestartProduceSameBillLinesInBusinessOrder() {
        TestContext first = context("runtime-1");
        first.planService.commit(plan(), ADMIN);
        List.of(
                event("storage", Map.of("tier", "z"), 4, "e4"),
                event("storage", Map.of("tier", "a"), 3, "e3"),
                event("api_calls", Map.of("region", "us"), 2, "e2"),
                event("api_calls", Map.of("region", "cn"), 1, "e1"))
                .forEach(event -> first.eventService.ingest(event, ADMIN));

        Bill preview = first.billingService.preview("acct", JAN, FEB, "preview", ADMIN);
        Bill committed = first.billingService.commit("acct", JAN, FEB, "commit", ADMIN);

        assertBusinessLineOrder(preview);
        assertEquals(preview.lines(), committed.lines());
        assertEquals(preview.totalAmount(), committed.totalAmount());

        TestContext restarted = context("runtime-1");
        Bill previewAfterRestart = restarted.billingService.preview("acct", JAN, FEB, "preview-again", ADMIN);
        Bill committedAfterRestart = restarted.billingService.commit("acct", JAN, FEB, "commit-again", ADMIN);

        assertEquals(committed.lines(), previewAfterRestart.lines());
        assertEquals(committed, committedAfterRestart);
        assertEquals(1, restarted.billRepository.findByAccount("acct").size());
    }

    private void assertBusinessLineOrder(Bill bill) {
        List<String> order = bill.lines().stream().map(BillLine::key)
                .map(key -> key.metric() + ":" + key.dimensions())
                .toList();
        assertEquals(List.of(
                "api_calls:{region=cn}",
                "api_calls:{region=us}",
                "storage:{tier=a}",
                "storage:{tier=z}"), order);
    }

    private TestContext context(String directory) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonFileStore jsonStore = new JsonFileStore(tempDir.resolve(directory + "/json"), mapper);
        BillingStateStore stateStore = new BillingStateStore(
                tempDir.resolve(directory + "/state/billing.json"), mapper);
        RecoveryStateStore recoveryStore = new RecoveryStateStore(
                tempDir.resolve(directory + "/state/recovery.json"), mapper);
        var eventRepository = new FileEventRepository(jsonStore);
        var planRepository = new FilePlanRepository(jsonStore);
        var billRepository = new FileBillRepository(stateStore);
        var auditRepository = new FileAuditRepository(stateStore);
        var adjustmentRepository = new FileAdjustmentRepository(stateStore);
        var authorization = new AuthorizationService();
        var auditService = new AuditService(auditRepository, authorization);
        var planService = new PlanService(planRepository, authorization, auditService);
        var accountLocks = new AccountLocks();
        var eventService = new EventService(
                eventRepository, authorization, auditService, accountLocks);
        var billingService = new BillingService(
                eventRepository, billRepository, stateStore, recoveryStore, planService,
                new UsageAggregationService(), new PricingService(), authorization, auditService, accountLocks);
        var adjustmentService = new AdjustmentService(billRepository, adjustmentRepository, billingService);
        return new TestContext(planService, eventService, billingService, adjustmentService, billRepository);
    }

    private PricingPlanVersion plan() {
        return new PricingPlanVersion("plan", "acct", 1, JAN, null, Instant.now(), List.of(
                rule("api_calls", Map.of("region", "cn"), 1),
                rule("api_calls", Map.of("region", "us"), 2),
                rule("storage", Map.of("tier", "a"), 3),
                rule("storage", Map.of("tier", "z"), 4)));
    }

    private PricingRule rule(String metric, Map<String, String> dimensions, long price) {
        return new PricingRule(metric, new TreeMap<>(dimensions),
                List.of(new PricingTier(null, new BigDecimal(price))), null, null, null);
    }

    private UsageEvent event(String metric, Map<String, String> dimensions, long quantity, String eventId) {
        return new UsageEvent(eventId, "acct", metric,
                Instant.parse("2026-01-02T00:00:00Z"), Instant.now(),
                new BigDecimal(quantity), new TreeMap<>(dimensions));
    }

    private record TestContext(
            PlanService planService,
            EventService eventService,
            BillingService billingService,
            AdjustmentService adjustmentService,
            FileBillRepository billRepository) {
    }
}
