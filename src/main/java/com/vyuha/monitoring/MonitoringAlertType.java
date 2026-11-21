package com.vyuha.monitoring;

/**
 * Alert categories raised by Phase 10 threshold, staleness, and pass-scan integrity checks.
 */
public enum MonitoringAlertType {

    CAPACITY_WARNING("Capacity Warning"),
    CAPACITY_CRITICAL("Capacity Critical"),
    STALE_DATA("Stale Occupancy Data"),
    DUPLICATE_PASS_SCAN("Duplicate Pass Scan Rejected");

    private final String displayName;

    MonitoringAlertType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
