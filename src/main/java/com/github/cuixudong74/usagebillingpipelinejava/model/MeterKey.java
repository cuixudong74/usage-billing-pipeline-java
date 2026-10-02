package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.util.SortedMap;

public record MeterKey(String accountId, String metric, SortedMap<String, String> dimensions) {
}
