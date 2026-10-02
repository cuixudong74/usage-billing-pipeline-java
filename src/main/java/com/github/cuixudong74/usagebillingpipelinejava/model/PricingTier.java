package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.math.BigDecimal;

public record PricingTier(BigDecimal upperBoundExclusive, BigDecimal unitPrice) {
}
