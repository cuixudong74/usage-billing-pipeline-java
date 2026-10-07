package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.storage.JsonFileStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        normalizeLegacyKeys();
    }

    /**
     * Older builds keyed events solely by eventId. Rewrite those entries transparently to the
     * account-scoped composite key so existing event data keeps working without a manual migration.
     */
    private synchronized void normalizeLegacyKeys() {
        boolean needsRewrite = events.entrySet().stream()
                .anyMatch(entry -> !entry.getKey().equals(compositeKey(entry.getValue())));
        if (!needsRewrite) {
            return;
        }
        Map<String, UsageEvent> normalized = new LinkedHashMap<>();
        events.values().forEach(event -> normalized.putIfAbsent(compositeKey(event), event));
        store.replaceAll(TYPE, normalized);
        events.clear();
        events.putAll(normalized);
    }

    @Override
    public synchronized Optional<UsageEvent> findByAccountAndId(String accountId, String eventId) {
        return Optional.ofNullable(events.get(compositeKey(accountId, eventId)));
    }

    @Override
    public synchronized UsageEvent save(UsageEvent event) {
        String key = compositeKey(event);
        UsageEvent existing = events.get(key);
        if (existing != null) {
            return existing;
        }
        store.put(TYPE, key, event);
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

    private static String compositeKey(UsageEvent event) {
        return compositeKey(event.accountId(), event.eventId());
    }

    static String compositeKey(String accountId, String eventId) {
        return accountId + "|" + eventId;
    }
}
