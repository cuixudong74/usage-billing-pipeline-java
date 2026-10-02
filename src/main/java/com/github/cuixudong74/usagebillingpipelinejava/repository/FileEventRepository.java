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
    public synchronized Optional<UsageEvent> findById(String eventId) {
        return Optional.ofNullable(events.get(eventId));
    }

    @Override
    public synchronized UsageEvent save(UsageEvent event) {
        UsageEvent existing = events.get(event.eventId());
        if (existing != null) {
            return existing;
        }
        store.put(TYPE, event.eventId(), event);
        events.put(event.eventId(), event);
        return event;
    }

    @Override
    public synchronized List<UsageEvent> findByAccount(String accountId) {
        return events.values().stream()
                .filter(event -> event.accountId().equals(accountId))
                .map(UsageEvent.class::cast)
                .toList();
    }
}
