package com.github.cuixudong74.usagebillingpipelinejava.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record PlanAssignRequest(
        @NotBlank String accountId,
        @NotBlank String planCode,
        @NotNull Integer planVersion,
        Instant effectiveFrom,
        Instant effectiveTo) {
}
