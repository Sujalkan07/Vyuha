package com.vyuha.capacity;

/**
 * Overall professional review status of an event's preliminary capacity review.
 * Never claims automated "Safe" or "Certified" approval.
 */
public enum CapacityReviewStatus {
    PENDING_REVIEW("Pending Professional Review", "badge-low"),
    DOCUMENTED("Documented — Awaiting Verification", "badge-info"),
    REVIEWER_VERIFIED("Reviewer Verified", "badge-verified"),
    FLAGGED("Revisions Flagged", "badge-high");

    private final String displayName;
    private final String badgeClass;

    CapacityReviewStatus(String displayName, String badgeClass) {
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
