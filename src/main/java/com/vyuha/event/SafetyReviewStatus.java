package com.vyuha.event;

/**
 * Human safety reviewer decision-log statuses.
 * Never uses automated "Safe" or "Certified" terminology.
 */
public enum SafetyReviewStatus {
    COMMENTS_ISSUED("Comments Issued", "badge-info"),
    REVISIONS_REQUIRED("Revisions Required", "badge-high"),
    REVIEW_DOCUMENTED("Review Documented", "badge-verified");

    private final String displayName;
    private final String badgeClass;

    SafetyReviewStatus(String displayName, String badgeClass) {
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
