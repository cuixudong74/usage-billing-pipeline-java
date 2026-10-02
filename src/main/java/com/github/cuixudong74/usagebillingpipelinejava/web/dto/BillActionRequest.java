package com.github.cuixudong74.usagebillingpipelinejava.web.dto;

import jakarta.validation.constraints.NotBlank;

public record BillActionRequest(@NotBlank String accountId, @NotBlank String windowId, String reason) {
}
