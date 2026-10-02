package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.time.Instant;

public record Window(String accountId, Instant start, Instant end) {
}
