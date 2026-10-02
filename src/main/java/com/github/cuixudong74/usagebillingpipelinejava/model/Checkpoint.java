package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.time.Instant;
import java.util.List;

public record Checkpoint(
        String accountId,
        Instant windowStart,
        Instant windowEnd,
        String billId,
        List<String> processedEventIds,
        Instant committedAt
) {
}
