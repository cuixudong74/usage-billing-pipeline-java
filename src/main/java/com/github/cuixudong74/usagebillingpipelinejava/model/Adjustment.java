package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Adjustment(
        String adjustmentId,
        String accountId,
        String originalBillId,
        String replacementBillId,
        String reason,
        BigDecimal amountDelta,
        List<String> sourceEventIds,
        Instant createdAt,
        String createdBy
) {
}
