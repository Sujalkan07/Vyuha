package com.vyuha.infrastructure;

/**
 * Spatial conflict detector categories executed by ConflictDetectionService.
 */
public enum ConflictType {
    EMERGENCY_LANE_INTRUSION("Emergency Lane / Corridor Intrusion"),
    EXIT_OBSTRUCTION("Exit Clearance Apron Obstruction"),
    OVERLAPPING_RIGID_STRUCTURES("Overlapping Rigid Structures"),
    OUT_OF_BOUNDS("Out-of-Bounds Placement"),
    UNREACHABLE_SERVICE_POST("Unreachable Medical Post / Control Room"),
    WATER_UTILITY_HAZARD("Water Point Choke / Stage Utility Hazard");

    private final String displayName;

    ConflictType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
