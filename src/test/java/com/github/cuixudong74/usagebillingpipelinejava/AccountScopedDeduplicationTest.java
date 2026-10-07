package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cuixudong74.usagebillingpipelinejava.exception.ForbiddenException;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.AuthorizationService;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ConflictException;
import com.github.cuixudong74.usagebillingpipelinejava.model.AuditEntry;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.repository.AuditRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.EventRepository;
import com.github.cuixudong74.usagebillingpipelinejava.service.AccountLocks;
import com.github.cuixudong74.usagebillingpipelinejava.service.AuditService;
import com.github.cuixudong74.usagebillingpipelinejava.service.EventService;
import com.github.cuixudong74.usagebillingpipelinejava.service.UsageAggregationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class AccountScopedDeduplicationTest {
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-01T00:00:00Z");
    private static final Actor ADMIN = new Actor("admin", Set.of("ADMIN"));
    private final EventRepository repository = new EventRepository() {
        private final Map<String, UsageEvent> events = new java.util.HashMap<>();

        @Override
        public Optional<UsageEvent> findByAccountAndEventId(String accountId, String eventId) {
            return Optional.ofNullable(events.get(key(accountId, eventId)));
        }

        @Override
        public UsageEvent save(UsageEvent event) {
            events.putIfAbsent(key(event.accountId(), event.eventId()), event);
            return events.get(key(event.accountId(), event.eventId()));
        }

        @Override
        public java.util.List<UsageEvent> findByAccount(String accountId) {
            return events.values().stream().filter(event -> event.accountId().equals(accountId)).toList();
        }

        private String key(String accountId, String eventId) {
            return accountId + "|" + eventId;
        }
    };
    private final List<AuditEntry> audits = new ArrayList<>();
    private final EventService eventService = new EventService(
            repository, new AuthorizationService(),
            new AuditService(new RecordingAuditRepository(audits), new AuthorizationService()),
            new AccountLocks());

    @Test
    void sameEventIdIsDeduplicatedOnlyWithinAnAccount() {
        UsageEvent first = event("acct-a", "shared-id", 3);
        UsageEvent second = event("acct-b", "shared-id", 5);

        eventService.ingest(first, ADMIN);
        eventService.ingest(second, ADMIN);

        assertEquals(new BigDecimal("3"),
                repository.findByAccount("acct-a").get(0).quantity());
        assertEquals(new BigDecimal("5"),
                repository.findByAccount("acct-b").get(0).quantity());
        assertEquals(new BigDecimal("3"), aggregateQuantity("acct-a"));
        assertEquals(new BigDecimal("5"), aggregateQuantity("acct-b"));
    }

    @Test
    void identicalRedeliveryWithinSameAccountReturnsStoredEventOnce() {
        UsageEvent first = eventService.ingest(event("acct-a", "dup-id", 2), ADMIN);
        UsageEvent redelivery = new UsageEvent("dup-id", "acct-a", "api_calls",
                first.eventTime(), Instant.now(), first.quantity(), new TreeMap<>());

        UsageEvent returned = eventService.ingest(redelivery, ADMIN);

        assertSame(first, returned);
        assertEquals(1, repository.findByAccount("acct-a").size());
    }

    @Test
    void sameAccountAndEventIdWithDifferentBusinessPayloadIsRejected() {
        eventService.ingest(event("acct-a", "dup-id", 2), ADMIN);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> eventService.ingest(event("acct-a", "dup-id", 99), ADMIN));
        assertTrue(conflict.getMessage().contains("dup-id"));
        assertTrue(conflict.getMessage().contains("acct-a"));
        assertTrue(conflict.getMessage().contains("different business payload"));
        assertEquals(new BigDecimal("2"),
                repository.findByAccount("acct-a").get(0).quantity());
    }

    @Test
    void forbiddenIngestCreatesNeitherEventNorAudit() {
        Actor auditor = new Actor("auditor", Set.of("AUDITOR"));

        assertThrows(ForbiddenException.class, () -> eventService.ingest(event("acct-a", "forbidden", 1), auditor));

        assertTrue(repository.findByAccount("acct-a").isEmpty());
        assertTrue(audits.isEmpty());
    }

    private BigDecimal aggregateQuantity(String accountId) {
        return new UsageAggregationService()
                .aggregate(repository.findByAccount(accountId), JAN, FEB)
                .values().iterator().next().quantity();
    }

    private UsageEvent event(String accountId, String eventId, int quantity) {
        return new UsageEvent(eventId, accountId, "api_calls",
                Instant.parse("2026-01-02T00:00:00Z"), Instant.now(),
                new BigDecimal(quantity), new TreeMap<>());
    }

    private record RecordingAuditRepository(List<AuditEntry> entries) implements AuditRepository {
        @Override
        public AuditEntry save(AuditEntry entry) {
            entries.add(entry);
            return entry;
        }

        @Override
        public List<AuditEntry> findByAccount(String accountId) {
            return entries.stream().filter(entry -> entry.accountId().equals(accountId)).toList();
        }
    }
}
