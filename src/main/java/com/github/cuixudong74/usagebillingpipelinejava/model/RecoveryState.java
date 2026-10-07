package com.github.cuixudong74.usagebillingpipelinejava.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Recovery marker for a single account + window billing task.
 *
 * <p>{@code operation} distinguishes the original task type ({@code COMMIT} for a first bill,
 * {@code ADJUST} for a correction); {@code idempotencyKey} and {@code reason} carry the inputs
 * needed to resume an interrupted task deterministically. Fields added after the initial record
 * default to legacy values when absent from older JSON files, so existing recovery-state.json
 * files keep loading without migration.</p>
 */
public record RecoveryState(
        String accountId,
        Instant windowStart,
        Instant windowEnd,
        String status,
        String billId,
        Instant updatedAt,
        String owner,
        String operation,
        String idempotencyKey,
        String reason
) {
    public static final String OP_COMMIT = "COMMIT";
    public static final String OP_ADJUST = "ADJUST";

    @JsonCreator
    public static RecoveryState create(
            @JsonProperty("accountId") String accountId,
            @JsonProperty("windowStart") Instant windowStart,
            @JsonProperty("windowEnd") Instant windowEnd,
            @JsonProperty("status") String status,
            @JsonProperty("billId") String billId,
            @JsonProperty("updatedAt") Instant updatedAt,
            @JsonProperty("owner") String owner,
            @JsonProperty("operation") String operation,
            @JsonProperty("idempotencyKey") String idempotencyKey,
            @JsonProperty("reason") String reason) {
        return new RecoveryState(
                accountId, windowStart, windowEnd, status, billId, updatedAt, owner,
                operation == null ? OP_COMMIT : operation,
                idempotencyKey,
                reason);
    }
}
