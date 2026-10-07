package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.exception.ValidationException;
import com.github.cuixudong74.usagebillingpipelinejava.model.MeterKey;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageAggregate;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class UsageAggregationService {
    public Map<MeterKey, UsageAggregate> aggregate(
            Collection<UsageEvent> events, Instant windowStart, Instant windowEnd) {
        if (!windowStart.isBefore(windowEnd)) {
            throw new ValidationException("Window start must be before end");
        }
        Map<String, UsageEvent> unique = new LinkedHashMap<>();
        for (UsageEvent event : events) {
            unique.putIfAbsent(uniqueKey(event), event);
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

        Map<MeterKey, UsageAggregate> result = new TreeMap<>(MeterKey.businessOrder());
        grouped.forEach((key, builder) -> result.put(key, builder.build()));
        return Map.copyOf(result);
    }

    private static String uniqueKey(UsageEvent event) {
        return event.accountId() + "|" + event.eventId();
    }

    private static final class AggregationBuilder {
        private final MeterKey key;
        private final Map<String, UsageEvent> events = new LinkedHashMap<>();
        private BigDecimal quantity = BigDecimal.ZERO;

        private AggregationBuilder(MeterKey key) {
            this.key = key;
        }

        private void add(UsageEvent event) {
            events.putIfAbsent(uniqueKey(event), event);
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
