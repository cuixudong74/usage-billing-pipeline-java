package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.authorization.Action;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.AuthorizationService;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ConflictException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ValidationException;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.repository.EventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

@Service
public class EventService {
    private final EventRepository eventRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final AccountLocks accountLocks;

    public EventService(EventRepository eventRepository,
                        AuthorizationService authorizationService,
                        AuditService auditService,
                        AccountLocks accountLocks) {
        this.eventRepository = eventRepository;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.accountLocks = accountLocks;
    }

    public UsageEvent ingest(UsageEvent event, Actor actor) {
        validateActor(actor);
        validate(event);
        authorizationService.authorize(actor, Action.INGEST_EVENT, event.accountId());
        UsageEvent normalized = normalize(event);
        // Serialize ingestion within one account (deduplication must be race-free) while letting
        // different accounts ingest concurrently.
        ReentrantLock lock = accountLocks.forAccount(normalized.accountId());
        lock.lock();
        try {
            var existing = eventRepository.findByAccountAndId(normalized.accountId(), normalized.eventId());
            if (existing.isPresent()) {
                if (!sameBusinessPayload(existing.get(), normalized)) {
                    throw new ConflictException(
                            "Event " + normalized.eventId() + " for account " + normalized.accountId()
                                    + " was already received with a different business payload"
                                    + " (metric/eventTime/quantity/dimensions must match on redelivery);"
                                    + " duplicate is rejected");
                }
                return existing.get();
            }
            UsageEvent saved = eventRepository.save(normalized);
            auditService.record(actor, Action.INGEST_EVENT, saved.accountId(), saved.eventId(), Map.of(
                    "metric", saved.metric(),
                    "eventTime", saved.eventTime().toString(),
                    "quantity", saved.quantity().toPlainString()));
            return saved;
        } finally {
            lock.unlock();
        }
    }


    private boolean sameBusinessPayload(UsageEvent left, UsageEvent right) {
        return left.accountId().equals(right.accountId())
                && left.metric().equals(right.metric())
                && left.eventTime().equals(right.eventTime())
                && left.quantity().compareTo(right.quantity()) == 0
                && left.dimensions().equals(right.dimensions());
    }

    private void validateActor(Actor actor) {
        if (actor == null || actor.subject() == null || actor.subject().isBlank()) {
            throw new ValidationException("Authenticated actor is required");
        }
    }

    private void validate(UsageEvent event) {
        require(event != null, "Event is required");
        require(!event.eventId().isBlank(), "eventId is required");
        require(!event.accountId().isBlank(), "accountId is required");
        require(!event.metric().isBlank(), "metric is required");
        require(event.eventTime() != null, "eventTime is required");
        require(event.quantity() != null && event.quantity().signum() >= 0, "quantity must be non-negative");
        require(event.dimensions() != null, "dimensions are required");
    }

    private UsageEvent normalize(UsageEvent event) {
        Instant receivedAt = event.receivedAt() == null ? Instant.now() : event.receivedAt();
        return new UsageEvent(
                event.eventId(),
                event.accountId(),
                event.metric(),
                event.eventTime(),
                receivedAt,
                event.quantity().stripTrailingZeros(),
                new TreeMap<>(event.dimensions()));
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ValidationException(message);
        }
    }
}
