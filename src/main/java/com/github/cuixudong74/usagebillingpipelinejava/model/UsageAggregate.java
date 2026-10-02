package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record UsageAggregate(
        MeterKey key,
        BigDecimal quantity,
        int eventCount,
        Instant firstEventTime,
        Instant lastEventTime,
        List<String> eventIds
) {
}
