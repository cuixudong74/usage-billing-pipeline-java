package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.time.Instant;
import java.util.Map;

public record AuditEntry(
        String auditId,
        String accountId,
        String actor,
        String action,
        String entityId,
        Map<String, String> details,
        Instant occurredAt
) {
}
