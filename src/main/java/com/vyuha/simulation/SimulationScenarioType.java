package com.vyuha.simulation;

/**
 * Supported Phase 9 deterministic crowd simulation scenarios.
 */
public enum SimulationScenarioType {
    NORMAL_OPERATIONS(
            "Normal Operations",
            "Balanced arrival, zone circulation, and normal egress."
    ),
    ARRIVAL_RUSH(
            "Arrival Rush (2.5x Surge)",
            "Compressed peak arrival wave at 2.5x baseline rate stressing entry gates, checkpoints, and entry corridors."
    ),
    STAGE_CROWDING(
            "Stage Crowding",
            "High destination affinity (85%) toward the stage-adjacent or primary audience zone."
    ),
    BLOCKED_CORRIDOR(
            "Blocked Corridor",
            "Disables a selected pathway corridor (from t=0 or mid-run), forcing dynamic rerouting."
    ),
    CLOSED_EXIT(
            "Closed Exit",
            "Closes a selected exit gate, forcing egress rerouting to surviving exits and emergency exits."
    );

    private final String displayName;
    private final String description;

    SimulationScenarioType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean requiresTargetEdge() {
        return this == BLOCKED_CORRIDOR;
    }

    public boolean requiresTargetExit() {
        return this == CLOSED_EXIT;
    }
}
