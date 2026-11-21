package com.vyuha.operations;

/**
 * Resolution lifecycle statuses for operational incident logs.
 */
public enum IncidentStatus {
    OPEN("Open", "badge-high"),
    IN_PROGRESS("In Progress", "badge-low"),
    ESCALATED("Escalated", "badge-critical"),
    RESOLVED("Resolved", "badge-verified");

    private final String displayName;
    private final String badgeClass;

    IncidentStatus(String displayName, String badgeClass) {
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
