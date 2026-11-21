package com.vyuha.monitoring;

/**
 * Strictly segregated occupancy data channels — never blended into a single unlabeled figure.
 */
public enum OccupancyDataSource {

    MANUAL_STAFF("Manual Staff Count", "Manual Count", "badge-info", true),
    PASS_SCAN("Attendee Pass Scan", "Pass Scan", "badge-verified", true),
    PRACTICE_SIMULATION("Practice Simulation", "Practice Simulation", "badge-low", false);

    private final String displayName;
    private final String shortLabel;
    private final String badgeClass;
    private final boolean liveOperational;

    OccupancyDataSource(String displayName, String shortLabel, String badgeClass, boolean liveOperational) {
        this.displayName = displayName;
        this.shortLabel = shortLabel;
        this.badgeClass = badgeClass;
        this.liveOperational = liveOperational;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getShortLabel() {
        return shortLabel;
    }

    public String getBadgeClass() {
        return badgeClass;
    }

    public boolean isLiveOperational() {
        return liveOperational;
    }
}
