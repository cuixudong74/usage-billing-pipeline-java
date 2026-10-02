package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.math.BigDecimal;
import java.time.Instant;

public record UsageEvent(
        String eventId,
        String accountId,
        String metric,
        Instant eventTime,
        Instant receivedAt,
        BigDecimal quantity,
        java.util.SortedMap<String, String> dimensions
) {
}
