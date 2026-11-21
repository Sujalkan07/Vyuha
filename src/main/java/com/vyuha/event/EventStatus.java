package com.vyuha.event;

/**
 * Lifecycle status of a planned event.
 */
public enum EventStatus {
    DRAFT("Draft", "badge-neutral"),
    PLANNING("Planning", "badge-info"),
    IN_REVIEW("In Review", "badge-low"),
    READY("Planning Ready", "badge-verified"),
    LIVE("Live", "badge-high"),
    COMPLETED("Completed", "badge-neutral"),
    ARCHIVED("Archived", "badge-neutral");

    private final String displayName;
    private final String badgeClass;

    EventStatus(String displayName, String badgeClass) {
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
