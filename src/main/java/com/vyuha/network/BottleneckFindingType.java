package com.vyuha.network;

/**
 * Standardized automated bottleneck and movement-network screening finding categories.
 */
public enum BottleneckFindingType {
    DISCONNECTED_ZONE("Disconnected Zone"),
    ISOLATED_EXIT("Isolated Exit"),
    DEAD_END_CORRIDOR("Dead-End Corridor"),
    NARROW_WALKWAY("Narrow Walkway"),
    HIGH_CONVERGENCE_MERGE("High-Convergence Merge"),
    CONTRA_FLOW_CONFLICT("Contra-Flow Conflict"),
    CROSSING_QUEUE_CONFLICT("Crossing Queue Conflict"),
    ENTRY_GATE_OVERLOAD("Entry Gate Overload");

    private final String displayName;

    BottleneckFindingType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
