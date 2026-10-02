package com.github.cuixudong74.usagebillingpipelinejava.storage;

import com.github.cuixudong74.usagebillingpipelinejava.model.Adjustment;
import com.github.cuixudong74.usagebillingpipelinejava.model.AuditEntry;
import com.github.cuixudong74.usagebillingpipelinejava.model.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.model.Checkpoint;
import java.util.LinkedHashMap;
import java.util.Map;

public record BillingState(
        Map<String, Bill> bills,
        Map<String, Adjustment> adjustments,
        Map<String, Checkpoint> checkpoints,
        Map<String, AuditEntry> audits
) {
    public static BillingState empty() {
        return new BillingState(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    public BillingState immutableCopy() {
        return new BillingState(
                Map.copyOf(bills),
                Map.copyOf(adjustments),
                Map.copyOf(checkpoints),
                Map.copyOf(audits));
    }
}
