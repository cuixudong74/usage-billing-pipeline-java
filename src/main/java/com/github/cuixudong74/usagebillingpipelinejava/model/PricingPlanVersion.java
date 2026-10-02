package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.time.Instant;
import java.util.List;

public record PricingPlanVersion(
        String planId,
        String accountId,
        int version,
        Instant effectiveFrom,
        Instant effectiveTo,
        Instant committedAt,
        List<PricingRule> rules
) {
}
