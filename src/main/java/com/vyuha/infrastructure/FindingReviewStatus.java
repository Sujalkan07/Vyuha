package com.vyuha.infrastructure;

/**
 * Human review lifecycle states for spatial conflict findings.
 */
public enum FindingReviewStatus {
    OPEN("Open"),
    ACKNOWLEDGED("Acknowledged"),
    MITIGATED("Mitigated"),
    REVIEWER_NOTED("Reviewer Noted");

    private final String displayName;

    FindingReviewStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
