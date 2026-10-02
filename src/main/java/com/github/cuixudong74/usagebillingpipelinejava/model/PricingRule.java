package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.math.BigDecimal;
import java.util.SortedMap;

public record PricingRule(
        String metric,
        SortedMap<String, String> dimensions,
        java.util.List<PricingTier> tiers,
        BigDecimal minimumAmount,
        BigDecimal maximumAmount,
        BigDecimal discountRate
) {
}
