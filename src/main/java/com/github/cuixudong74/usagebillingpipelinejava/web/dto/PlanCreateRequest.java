package com.github.cuixudong74.usagebillingpipelinejava.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PlanCreateRequest(
        @NotBlank String planCode,
        Instant effectiveFrom,
        Instant effectiveTo,
        @NotNull @Valid RulesDto rules) {

    public record RulesDto(
            String currency,
            @NotEmpty @Valid List<TierDto> tiers,
            BigDecimal discount,
            BigDecimal minCharge,
            BigDecimal cap) {
    }

    public record TierDto(BigDecimal upTo, @NotNull BigDecimal pricePerUnit) {
    }
}
