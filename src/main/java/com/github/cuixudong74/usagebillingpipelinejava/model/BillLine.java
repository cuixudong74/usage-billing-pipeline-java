package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.math.BigDecimal;
import java.util.List;

public record BillLine(
        MeterKey key,
        BigDecimal quantity,
        List<String> eventIds,
        BigDecimal listAmount,
        BigDecimal discountAmount,
        BigDecimal minimumAdjustment,
        BigDecimal capAdjustment,
        BigDecimal netAmount
) {
}
