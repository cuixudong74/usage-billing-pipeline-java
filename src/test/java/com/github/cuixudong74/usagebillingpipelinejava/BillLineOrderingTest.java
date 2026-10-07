package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.model.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.model.BillLine;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BillLineOrderingTest {
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-01T00:00:00Z");
    private static final Actor ADMIN = new Actor("admin", Set.of("ADMIN"));

    @TempDir
    Path tempDir;

    @Test
    void billLinesAreStableRegardlessOfArrivalOrderAndPreviewMatchesCommit() {
        List<Descriptor> descriptors = List.of(
                new Descriptor("storage", Map.of(), "e-storage"),
                new Descriptor("api_calls", Map.of("region", "us"), "e-us"),
                new Descriptor("api_calls", Map.of("region", "cn"), "e-cn"),
                new Descriptor("api_calls", Map.of("region", "cn", "tier", "gold"), "e-cn-gold"),
                new Descriptor("api_calls", Map.of("region", "eu"), "e-eu"),
                new Descriptor("network", Map.of("zone", "a"), "e-net-a"));

        List<Bill> previews = new ArrayList<>();
        // Ingest the same business events in several shuffled arrival orders in fresh stores.
        List<List<Descriptor>> permutations = List.of(
                descriptors,
                reverse(descriptors),
                List.of(descriptors.get(3), descriptors.get(0), descriptors.get(5),
                        descriptors.get(1), descriptors.get(4), descriptors.get(2)));
        for (List<Descriptor> order : permutations) {
            TestContext ctx = context();
            int day = 2;
            for (Descriptor d : order) {
                ctx.eventService.ingest(event(d, Instant.parse("2026-01-0" + day + "T00:00:00Z")), ADMIN);
                day++;
            }
            Bill preview = ctx.billingService.preview("acct", JAN, FEB, "pv", ADMIN);
            previews.add(preview);
            // Preview must not create a bill.
            assertEquals(0, ctx.billRepository.findByAccount("acct").size());
            Bill committed = ctx.billingService.commit("acct", JAN, FEB, "cm", ADMIN);
            assertLinesEqual(preview, committed);
        }

        // All three permutations produce identical, deterministically ordered lines.
        for (Bill preview : previews) {
            assertLinesEqual(previews.get(0), preview);
        }
        List<String> keys = previews.get(0).lines().stream().map(this::lineKey).toList();
        assertEquals(List.of(
                "api_calls|region=cn",
                "api_calls|region=cn|tier=gold",
                "api_calls|region=eu",
                "api_calls|region=us",
                "network|zone=a",
                "storage|"), keys);
    }

    private void assertLinesEqual(Bill expected, Bill actual) {
        assertEquals(expected.totalAmount(), actual.totalAmount());
        List<String> left = expected.lines().stream().map(this::lineKey).toList();
        List<String> right = actual.lines().stream().map(this::lineKey).toList();
        assertEquals(left, right);
        for (int i = 0; i < expected.lines().size(); i++) {
            BillLine a = expected.lines().get(i);
            BillLine b = actual.lines().get(i);
            assertEquals(a.quantity(), b.quantity());
            assertEquals(a.netAmount(), b.netAmount());
            assertEquals(a.eventIds(), b.eventIds());
        }
    }

    private String lineKey(BillLine line) {
        return line.key().metric() + "|" + line.key().dimensions().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce("", (l, r) -> l.isEmpty() ? r : l + "|" + r);
    }

    private List<Descriptor> reverse(List<Descriptor> source) {
        List<Descriptor> reversed = new ArrayList<>(source);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private UsageEvent event(Descriptor descriptor, Instant when) {
        return new UsageEvent(descriptor.eventId(), "acct", descriptor.metric(), when,
                Instant.now(), BigDecimal.ONE, new TreeMap<>(descriptor.dimensions()));
    }

    private TestContext context() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path root = tempDir.resolve("ctx-" + System.nanoTime());
        JsonFileStore jsonStore = new JsonFileStore(root.resolve("json"), mapper);
        BillingStateStore billingStateStore = new BillingStateStore(root.resolve("state/billing.json"), mapper);
        RecoveryStateStore recoveryStateStore = new RecoveryStateStore(root.resolve("state/recovery.json"), mapper);
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
        List<PricingRule> rules = new ArrayList<>();
        for (Descriptor d : List.of(
                new Descriptor("storage", Map.of(), "x"),
                new Descriptor("api_calls", Map.of("region", "us"), "x"),
                new Descriptor("api_calls", Map.of("region", "cn"), "x"),
                new Descriptor("api_calls", Map.of("region", "cn", "tier", "gold"), "x"),
                new Descriptor("api_calls", Map.of("region", "eu"), "x"),
                new Descriptor("network", Map.of("zone", "a"), "x"))) {
            rules.add(new PricingRule(d.metric(), new TreeMap<>(d.dimensions()),
                    List.of(new PricingTier(null, BigDecimal.ONE)), null, null, null));
        }
        planService.commit(new PricingPlanVersion("plan", "acct", 1, JAN, null,
                Instant.now(), rules), ADMIN);
        return new TestContext(eventService, billingService, adjustmentService, billRepository);
    }

    private record Descriptor(String metric, Map<String, String> dimensions, String eventId) {
    }

    private record TestContext(
            EventService eventService,
            BillingService billingService,
            AdjustmentService adjustmentService,
            FileBillRepository billRepository) {
    }
}
