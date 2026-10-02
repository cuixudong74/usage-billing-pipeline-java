package com.github.cuixudong74.usagebillingpipelinejava.web.dto;

import com.github.cuixudong74.usagebillingpipelinejava.domain.Bill;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BillResponse(
        Long id,
        String billNo,
        String accountId,
        String windowId,
        Instant windowStart,
        Instant windowEnd,
        String planCode,
        Integer planVersion,
        String status,
        Integer billVersion,
        BigDecimal totalAmount,
        String currency,
        Long eventCount,
        BigDecimal totalQuantity,
        List<LineItemDto> lineItems,
        List<String> eventIds,
        Instant committedAt,
        String operator,
        Long previousBillId,
        String adjustmentReason,
        boolean preview,
        boolean sealed) {

    public record LineItemDto(String dimension, BigDecimal quantity, BigDecimal amount, String description) {
    }

    public static BillResponse from(Bill bill) {
        List<LineItemDto> lines = bill.getLineItems().stream()
                .map(li -> new LineItemDto(li.getDimension(), li.getQuantity(), li.getAmount(), li.getDescription()))
                .toList();
        return new BillResponse(
                bill.getId(), bill.getBillNo(), bill.getAccountId(), bill.getWindowId(),
                bill.getWindowStart(), bill.getWindowEnd(),
                bill.getPlanCode(), bill.getPlanVersion(),
                bill.getStatus() == null ? null : bill.getStatus().name(),
                bill.getBillVersion(), bill.getTotalAmount(), bill.getCurrency(),
                bill.getEventCount(), bill.getTotalQuantity(), lines,
                bill.getEventIds(), bill.getCommittedAt(), bill.getOperator(),
                bill.getPreviousBillId(), bill.getAdjustmentReason(),
                bill.isPreview(), bill.isSealed());
    }
}
