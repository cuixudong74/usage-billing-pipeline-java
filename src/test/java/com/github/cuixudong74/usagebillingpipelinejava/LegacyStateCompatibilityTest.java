package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FileEventRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FilePlanRepository;
import com.github.cuixudong74.usagebillingpipelinejava.storage.JsonFileStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyStateCompatibilityTest {
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void legacyGlobalEventKeysAndPlanKeysRemainReadableWithoutMigration() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path directory = tempDir.resolve("legacy");
        Files.createDirectories(directory);

        UsageEvent first = new UsageEvent("shared-event", "acct-a", "api_calls",
                JAN, JAN, BigDecimal.ONE, new TreeMap<>());
        UsageEvent second = new UsageEvent("shared-event", "acct-b", "storage",
                JAN, JAN, new BigDecimal("2"), new TreeMap<>());
        Map<String, UsageEvent> legacyEvents = new LinkedHashMap<>();
        legacyEvents.put("shared-event", first);
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("events.json").toFile(), legacyEvents);

        var firstPlan = new com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion(
                "plan", "acct-a", 1, JAN, null, JAN,
                java.util.List.of());
        var secondPlan = new com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion(
                "plan", "acct-b", 1, JAN, null, JAN,
                java.util.List.of());
        Map<String, Object> legacyPlans = new LinkedHashMap<>();
        legacyPlans.put("plan#1", firstPlan);
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("plans.json").toFile(), legacyPlans);

        JsonFileStore store = new JsonFileStore(directory, mapper);
        FileEventRepository events = new FileEventRepository(store);
        FilePlanRepository plans = new FilePlanRepository(store);

        assertEquals(BigDecimal.ONE,
                events.findByAccountAndEventId("acct-a", "shared-event").orElseThrow().quantity());

        events.save(second);
        plans.save(firstPlan);
        plans.save(secondPlan);

        assertEquals(1, events.findByAccount("acct-a").size());
        assertEquals(1, events.findByAccount("acct-b").size());
        assertTrue(events.findByAccountAndEventId("acct-a", "shared-event").isPresent());
        assertTrue(events.findByAccountAndEventId("acct-b", "shared-event").isPresent());
        assertEquals(2, new JsonFileStore(directory, mapper).<Object>readAll("events",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                }).size());
        assertEquals("acct-a", plans.findEffective("acct-a", JAN).orElseThrow().accountId());
        assertEquals("acct-b", plans.findEffective("acct-b", JAN).orElseThrow().accountId());
        assertEquals(2, new JsonFileStore(directory, mapper).<Object>readAll("plans",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                }).size());
    }
}
