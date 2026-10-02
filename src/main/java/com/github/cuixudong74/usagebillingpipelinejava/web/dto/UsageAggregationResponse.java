package com.github.cuixudong74.usagebillingpipelinejava.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record UsageAggregationResponse(
        String accountId,
        String windowId,
        List<DimensionUsage> dimensions,
        long eventCount) {

    public record DimensionUsage(String dimension, BigDecimal quantity, long eventCount) {
    }
}
