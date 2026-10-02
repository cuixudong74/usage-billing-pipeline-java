package com.github.cuixudong74.usagebillingpipelinejava.web.dto;

import com.github.cuixudong74.usagebillingpipelinejava.domain.Adjustment;
import java.math.BigDecimal;
import java.time.Instant;

public record AdjustmentResponse(
        Long id,
        Long billId,
        Long previousBillId,
        String accountId,
        String windowId,
        BigDecimal beforeAmount,
        BigDecimal afterAmount,
        BigDecimal diffAmount,
        String reason,
        String operator,
        Instant createdAt) {

    public static AdjustmentResponse from(Adjustment a) {
        return new AdjustmentResponse(a.getId(), a.getBillId(), a.getPreviousBillId(),
                a.getAccountId(), a.getWindowId(), a.getBeforeAmount(), a.getAfterAmount(),
                a.getDiffAmount(), a.getReason(), a.getOperator(), a.getCreatedAt());
    }
}
