package com.vyuha.event;

/**
 * Supported event categories driving category-tailored safety planning checklists.
 */
public enum EventCategory {
    COLLEGE_FESTIVAL("College Festival"),
    RELIGIOUS_GATHERING("Religious Gathering"),
    CONCERT("Concert / Live Music"),
    PUBLIC_RALLY("Public Rally / Assembly"),
    SPORTS_MATCH("Sports Match"),
    PUBLIC_CELEBRATION("Public Celebration"),
    EXHIBITION("Exhibition / Trade Fair");

    private final String displayName;

    EventCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
