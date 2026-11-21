package com.vyuha.monitoring;

/**
 * Lifecycle state of an issued unique attendee pass.
 */
public enum PassStatus {

    ISSUED("Issued", "badge-neutral"),
    CHECKED_IN("Checked In", "badge-verified"),
    CHECKED_OUT("Checked Out", "badge-info");

    private final String displayName;
    private final String badgeClass;

    PassStatus(String displayName, String badgeClass) {
        this.displayName = displayName;
        this.badgeClass = badgeClass;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBadgeClass() {
        return badgeClass;
    }
}
