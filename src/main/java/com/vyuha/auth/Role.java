package com.vyuha.auth;

/**
 * Application RBAC roles defined by the Vyuha security model.
 */
public enum Role {
    ADMIN("Admin"),
    ORGANIZER("Organizer"),
    SAFETY_REVIEWER("Safety Reviewer"),
    FIELD_STAFF("Field Staff");

    private final String displayName;

    Role(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
