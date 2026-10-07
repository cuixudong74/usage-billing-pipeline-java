package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.storage.JsonFileStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class FileEventRepository implements EventRepository {
    private static final String TYPE = "events";
    private final JsonFileStore store;
    private final Map<String, UsageEvent> events;

    public FileEventRepository(JsonFileStore store) {
        this.store = store;
        this.events = store.readAll(TYPE, new TypeReference<>() {
        });
    }

    @Override
    public synchronized Optional<UsageEvent> findByAccountAndEventId(String accountId, String eventId) {
        UsageEvent canonical = events.get(primaryKey(accountId, eventId));
        if (canonical != null) {
            return Optional.of(canonical);
        }

        UsageEvent legacy = events.get(eventId);
        if (legacy != null && accountId.equals(legacy.accountId())) {
            return Optional.of(legacy);
        }
        return Optional.empty();
    }

    @Override
    public synchronized UsageEvent save(UsageEvent event) {
        String key = primaryKey(event.accountId(), event.eventId());
        UsageEvent existing = events.get(key);
        if (existing != null) {
            return existing;
        }

        UsageEvent legacy = events.get(event.eventId());
        if (legacy != null && event.accountId().equals(legacy.accountId())) {
            store.replace(TYPE, event.eventId(), key, event);
            events.remove(event.eventId());
        } else {
            store.put(TYPE, key, event);
        }
        events.put(key, event);
        return event;
    }

    @Override
    public synchronized List<UsageEvent> findByAccount(String accountId) {
        return events.values().stream()
                .filter(event -> event.accountId().equals(accountId))
                .map(UsageEvent.class::cast)
                .toList();
    }

    private String primaryKey(String accountId, String eventId) {
        return accountId.length() + ":" + accountId + "|" + eventId;
    }
}
