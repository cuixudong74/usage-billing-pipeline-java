package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FileEventRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.FilePlanRepository;
import com.github.cuixudong74.usagebillingpipelinejava.storage.JsonFileStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Existing events.json/plans.json written by earlier builds used non-account-scoped keys.
 * Repositories must accept that data as-is (no manual migration) and transparently expose
 * account-scoped semantics.
 */
class LegacyDataCompatibilityTest {

    @TempDir
    Path tempDir;

    @Test
    void legacyEventsKeyedByEventIdLoadAndBecomeAccountScoped() throws Exception {
        Path dir = tempDir.resolve("json");
        Files.createDirectories(dir);
        String json = """
                {
                  "evt-1" : {
                    "eventId" : "evt-1",
                    "accountId" : "acct-a",
                    "metric" : "api_calls",
                    "eventTime" : "2026-01-02T00:00:00Z",
                    "receivedAt" : "2026-01-02T00:00:00Z",
                    "quantity" : 5,
                    "dimensions" : { }
                  }
                }
                """;
        Files.writeString(dir.resolve("events.json"), json);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileEventRepository repository = new FileEventRepository(new JsonFileStore(dir, mapper));

        assertEquals(1, repository.findByAccount("acct-a").size());
        assertTrue(repository.findByAccountAndId("acct-a", "evt-1").isPresent());
        // A different account reusing the legacy event id is independent.
        assertEquals(Optional.empty(), repository.findByAccountAndId("acct-b", "evt-1"));
    }

    @Test
    void legacyPlanKeyedWithoutAccountLoadsAndAllowsSamePlanIdForOtherAccounts() throws Exception {
        Path dir = tempDir.resolve("json-plans");
        Files.createDirectories(dir);
        String json = """
                {
                  "plan#1" : {
                    "planId" : "plan",
                    "accountId" : "acct-a",
                    "version" : 1,
                    "effectiveFrom" : "2026-01-01T00:00:00Z",
                    "effectiveTo" : null,
                    "committedAt" : "2026-01-01T00:00:00Z",
                    "rules" : [ {
                      "metric" : "api_calls",
                      "dimensions" : { },
                      "tiers" : [ { "upperBoundExclusive" : null, "unitPrice" : 1.00 } ],
                      "minimumAmount" : null,
                      "maximumAmount" : null,
                      "discountRate" : null
                    } ]
                  }
                }
                """;
        Files.writeString(dir.resolve("plans.json"), json);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FilePlanRepository repository = new FilePlanRepository(new JsonFileStore(dir, mapper));

        assertEquals(1, repository.findByAccount("acct-a").size());
        assertTrue(repository.findVersion("acct-a", 1).isPresent());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"),
                repository.findEffective("acct-a", Instant.parse("2026-01-15T00:00:00Z"))
                        .orElseThrow().effectiveFrom());
        assertEquals(new TreeMap<>(),
                repository.findVersion("acct-a", 1).orElseThrow().rules().get(0).dimensions());
        assertEquals(0, new BigDecimal("1.00").compareTo(
                repository.findVersion("acct-a", 1).orElseThrow()
                        .rules().get(0).tiers().get(0).unitPrice()));
    }
}
