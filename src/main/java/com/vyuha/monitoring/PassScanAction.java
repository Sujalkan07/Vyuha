package com.vyuha.monitoring;

/**
 * Gate scan direction applied to an attendee pass.
 */
public enum PassScanAction {

    CHECK_IN("Entry Check-In"),
    CHECK_OUT("Exit Check-Out");

    private final String displayName;

    PassScanAction(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
