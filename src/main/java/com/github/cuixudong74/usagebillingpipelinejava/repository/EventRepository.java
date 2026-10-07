package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import java.util.List;
import java.util.Optional;

public interface EventRepository {
    Optional<UsageEvent> findByAccountAndEventId(String accountId, String eventId);
    UsageEvent save(UsageEvent event);
    List<UsageEvent> findByAccount(String accountId);
}
