package com.github.cuixudong74.usagebillingpipelinejava.model;

import java.util.Comparator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public record MeterKey(String accountId, String metric, SortedMap<String, String> dimensions) {
    public static Comparator<MeterKey> businessOrder() {
        return Comparator.comparing(MeterKey::accountId)
                .thenComparing(MeterKey::metric)
                .thenComparing(MeterKey::dimensions, MeterKey::compareDimensions);
    }

    private static int compareDimensions(SortedMap<String, String> left, SortedMap<String, String> right) {
        var sortedLeft = new TreeMap<>(left);
        var sortedRight = new TreeMap<>(right);
        var leftEntries = sortedLeft.entrySet().iterator();
        var rightEntries = sortedRight.entrySet().iterator();

        while (leftEntries.hasNext() && rightEntries.hasNext()) {
            Map.Entry<String, String> leftEntry = leftEntries.next();
            Map.Entry<String, String> rightEntry = rightEntries.next();
            int keyComparison = leftEntry.getKey().compareTo(rightEntry.getKey());
            if (keyComparison != 0) {
                return keyComparison;
            }
            int valueComparison = leftEntry.getValue().compareTo(rightEntry.getValue());
            if (valueComparison != 0) {
                return valueComparison;
            }
        }
        return Integer.compare(sortedLeft.size(), sortedRight.size());
    }
}
