package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.model.BillLine;
import com.github.cuixudong74.usagebillingpipelinejava.model.MeterKey;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingRule;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingTier;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageAggregate;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.service.PricingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.UsageAggregationService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PricingAndAggregationTest {
    private final UsageAggregationService aggregationService = new UsageAggregationService();
    private final PricingService pricingService = new PricingService();
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void duplicateOutOfOrderAndLateEventsProduceOneStableAggregate() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-02-01T00:00:00Z");
        UsageEvent late = event("e3", 3, Instant.parse("2026-01-03T00:00:00Z"));
        UsageEvent duplicateFirst = event("e1", 1, Instant.parse("2026-01-01T00:00:00Z"));
        UsageEvent second = event("e2", 2, Instant.parse("2026-01-02T00:00:00Z"));
        UsageEvent duplicateFirstAgain = event("e1", 1, Instant.parse("2026-01-01T00:00:00Z"));
        UsageEvent outside = event("e4", 9, end);

        var first = aggregationService.aggregate(
                List.of(late, duplicateFirst, second, duplicateFirstAgain, outside), start, end);
        var secondResult = aggregationService.aggregate(
                List.of(outside, duplicateFirstAgain, late, second, duplicateFirst), start, end);

        assertEquals(first, secondResult);
        assertEquals(1, first.size());
        UsageAggregate aggregate = first.values().iterator().next();
        assertEquals(new BigDecimal("6"), aggregate.quantity());
        assertEquals(List.of("e1", "e2", "e3"), aggregate.eventIds());
    }

    @Test
    void tierDiscountMinimumAndCapUseExactCentArithmetic() {
        MeterKey key = new MeterKey("acct", "api_calls", new TreeMap<>());
        UsageAggregate usage = new UsageAggregate(
                key, new BigDecimal("150"), 3,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-03T00:00:00Z"),
                List.of("e1", "e2", "e3"));
        PricingRule rule = new PricingRule(
                "api_calls",
                new TreeMap<>(),
                List.of(
                        new PricingTier(new BigDecimal("100"), new BigDecimal("0.10")),
                        new PricingTier(null, new BigDecimal("0.20"))),
                new BigDecimal("30.00"),
                new BigDecimal("25.00"),
                new BigDecimal("0.10"));
        PricingPlanVersion plan = new PricingPlanVersion(
                "plan", "acct", 1,
                Instant.parse("2026-01-01T00:00:00Z"), null,
                Instant.now(), List.of(rule));

        BillLine line = pricingService.price(usage, plan);

        assertEquals(new BigDecimal("20.00"), line.listAmount());
        assertEquals(new BigDecimal("2.00"), line.discountAmount());
        assertEquals(new BigDecimal("12.00"), line.minimumAdjustment());
        assertEquals(new BigDecimal("-5.00"), line.capAdjustment());
        assertEquals(new BigDecimal("25.00"), line.netAmount());
    }


    @Test
    void rulesWithSameMetricButDifferentDimensionsAreBothSupported() {
        MeterKey cnKey = new MeterKey("acct", "api_calls", new TreeMap<>(Map.of("region", "cn")));
        MeterKey usKey = new MeterKey("acct", "api_calls", new TreeMap<>(Map.of("region", "us")));
        PricingRule cnRule = new PricingRule("api_calls", new TreeMap<>(Map.of("region", "cn")),
                List.of(new PricingTier(null, new BigDecimal("1.00"))), null, null, null);
        PricingRule usRule = new PricingRule("api_calls", new TreeMap<>(Map.of("region", "us")),
                List.of(new PricingTier(null, new BigDecimal("2.00"))), null, null, null);
        PricingPlanVersion plan = new PricingPlanVersion(
                "plan", "acct", 1, JAN, null, Instant.now(), List.of(cnRule, usRule));

        BillLine cnLine = pricingService.price(aggregate(cnKey, 1), plan);
        BillLine usLine = pricingService.price(aggregate(usKey, 1), plan);

        assertEquals(new BigDecimal("1.00"), cnLine.netAmount());
        assertEquals(new BigDecimal("2.00"), usLine.netAmount());
    }

    private UsageAggregate aggregate(MeterKey key, long quantity) {
        return new UsageAggregate(key, new BigDecimal(quantity), 1,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), List.of("e1"));
    }

    private UsageEvent event(String id, long quantity, Instant time) {
        return new UsageEvent(
                id,
                "acct",
                "api_calls",
                time,
                Instant.now(),
                new BigDecimal(quantity),
                new TreeMap<>());
    }
}
