package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.exception.ValidationException;
import com.github.cuixudong74.usagebillingpipelinejava.model.MeterKey;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageAggregate;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class UsageAggregationService {

    /**
     * Business-deterministic ordering of meter keys: metric first, then every dimension entry in
     * key/value order. The account id is included as the final tie breaker so that results stay
     * stable even if events from multiple accounts are aggregated together.
     */
    public static final Comparator<MeterKey> METER_KEY_ORDER = Comparator
            .comparing(MeterKey::metric)
            .thenComparing(key -> key.dimensions().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce("", (left, right) -> left + "|" + right))
            .thenComparing(MeterKey::accountId);

    public SortedMap<MeterKey, UsageAggregate> aggregate(
            Collection<UsageEvent> events, Instant windowStart, Instant windowEnd) {
        if (!windowStart.isBefore(windowEnd)) {
            throw new ValidationException("Window start must be before end");
        }
        // Deduplicate inside a single account: different accounts may reuse the same eventId.
        Map<String, UsageEvent> unique = new LinkedHashMap<>();
        for (UsageEvent event : events) {
            unique.putIfAbsent(event.accountId() + "|" + event.eventId(), event);
        }

        Map<MeterKey, AggregationBuilder> grouped = new LinkedHashMap<>();
        unique.values().stream()
                .filter(event -> !event.eventTime().isBefore(windowStart) && event.eventTime().isBefore(windowEnd))
                .forEach(event -> {
                    if (event.quantity().signum() < 0) {
                        throw new ValidationException("Usage quantity must not be negative: " + event.eventId());
                    }
                    MeterKey key = new MeterKey(event.accountId(), event.metric(), new TreeMap<>(event.dimensions()));
                    grouped.computeIfAbsent(key, AggregationBuilder::new).add(event);
                });

        SortedMap<MeterKey, UsageAggregate> result = new TreeMap<>(METER_KEY_ORDER);
        grouped.forEach((key, builder) -> result.put(key, builder.build()));
        return Collections.unmodifiableSortedMap(result);
    }

    private static final class AggregationBuilder {
        private final MeterKey key;
        private final Map<String, UsageEvent> events = new LinkedHashMap<>();
        private BigDecimal quantity = BigDecimal.ZERO;

        private AggregationBuilder(MeterKey key) {
            this.key = key;
        }

        private void add(UsageEvent event) {
            events.putIfAbsent(event.eventId(), event);
            quantity = quantity.add(event.quantity());
        }

        private UsageAggregate build() {
            var sorted = events.values().stream()
                    .sorted(Comparator.comparing(UsageEvent::eventTime).thenComparing(UsageEvent::eventId))
                    .toList();
            return new UsageAggregate(
                    key,
                    quantity,
                    sorted.size(),
                    sorted.get(0).eventTime(),
                    sorted.get(sorted.size() - 1).eventTime(),
                    sorted.stream().map(UsageEvent::eventId).toList());
        }
    }
}
