package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import java.util.List;
import java.util.Optional;

public interface EventRepository {
    /**
     * Look up an event inside a single account. Deduplication is scoped to the account, so the same
     * eventId reported by different accounts resolves to independent events.
     */
    Optional<UsageEvent> findByAccountAndId(String accountId, String eventId);

    UsageEvent save(UsageEvent event);
    List<UsageEvent> findByAccount(String accountId);
}
