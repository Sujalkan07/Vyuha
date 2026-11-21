package com.vyuha.operations;

/**
 * Personnel role categories for event staff registry and coverage checks.
 */
public enum StaffRoleType {
    SECURITY("Security", "badge-info", "SEC"),
    VOLUNTEER("Volunteer", "badge-neutral", "VOL"),
    MEDICAL("Medical", "badge-critical", "MED"),
    SUPERVISOR("Supervisor", "badge-high", "SUP");

    private final String displayName;
    private final String badgeClass;
    private final String shortCode;

    StaffRoleType(String displayName, String badgeClass, String shortCode) {
        this.displayName = displayName;
        this.badgeClass = badgeClass;
        this.shortCode = shortCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBadgeClass() {
        return badgeClass;
    }

    public String getShortCode() {
        return shortCode;
    }
}
