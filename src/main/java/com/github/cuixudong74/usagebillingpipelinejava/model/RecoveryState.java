package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.time.Instant;

public record RecoveryState(
        String accountId,
        Instant windowStart,
        Instant windowEnd,
        String status,
        String billId,
        Instant updatedAt,
        String owner
) {
}
