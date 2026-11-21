package com.vyuha.capacity;

/**
 * Per-zone density assumption review status with explicit citation and reviewer verification tracking.
 */
public enum AssumptionReviewStatus {
    PENDING_REVIEW("Pending Review", "badge-low"),
    DOCUMENTED("Documented", "badge-info"),
    REVIEWER_VERIFIED("Reviewer Verified", "badge-verified"),
    FLAGGED("Flagged", "badge-high");

    private final String displayName;
    private final String badgeClass;

    AssumptionReviewStatus(String displayName, String badgeClass) {
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
