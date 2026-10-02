package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Bill(
        String billId,
        String accountId,
        Instant windowStart,
        Instant windowEnd,
        int billVersion,
        BillStatus status,
        String planId,
        int planVersion,
        List<BillLine> lines,
        BigDecimal totalAmount,
        Instant createdAt,
        String createdBy,
        String idempotencyKey
) {
}
