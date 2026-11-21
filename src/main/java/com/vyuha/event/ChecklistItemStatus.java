package com.vyuha.event;

/**
 * Progress state for an individual planning checklist item.
 */
public enum ChecklistItemStatus {
    NOT_STARTED("Not Started", "badge-neutral"),
    IN_PROGRESS("In Progress", "badge-low"),
    COMPLETE("Complete", "badge-verified"),
    NOT_APPLICABLE("Not Applicable", "badge-neutral");

    private final String displayName;
    private final String badgeClass;

    ChecklistItemStatus(String displayName, String badgeClass) {
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
