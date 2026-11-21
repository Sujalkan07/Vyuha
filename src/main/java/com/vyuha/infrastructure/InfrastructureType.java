package com.vyuha.infrastructure;

/**
 * Supported Phase 5 infrastructure elements with canonical metric footprint defaults,
 * colors, throughput applicability, and spatial conflict roles.
 */
public enum InfrastructureType {
    ENTRY_GATE(
            "Entry Gate",
            "ENT",
            6.0,
            2.0,
            60.0,
            true,
            true,
            false,
            false,
            false,
            true,
            "#10B981",
            "#047857"
    ),
    EXIT_GATE(
            "Exit Gate",
            "EXT",
            6.0,
            2.0,
            80.0,
            true,
            false,
            false,
            true,
            false,
            true,
            "#059669",
            "#065F46"
    ),
    EMERGENCY_EXIT(
            "Emergency Exit",
            "EEX",
            6.0,
            2.0,
            100.0,
            true,
            false,
            false,
            true,
            false,
            true,
            "#DC2626",
            "#991B1B"
    ),
    SECURITY_CHECKPOINT(
            "Security Checkpoint",
            "SEC",
            5.0,
            3.0,
            30.0,
            true,
            true,
            false,
            false,
            false,
            true,
            "#F59E0B",
            "#B45309"
    ),
    STAGE(
            "Stage / Main Platform",
            "STG",
            14.0,
            8.0,
            null,
            true,
            true,
            false,
            false,
            false,
            false,
            "#8B5CF6",
            "#5B21B6"
    ),
    FOOD_STALL(
            "Food / Concession Stall",
            "FOD",
            4.0,
            3.0,
            null,
            true,
            true,
            false,
            false,
            false,
            false,
            "#F97316",
            "#C2410C"
    ),
    WATER_POINT(
            "Drinking Water Point",
            "WTR",
            3.0,
            2.0,
            null,
            true,
            true,
            false,
            false,
            false,
            false,
            "#06B6D4",
            "#0E7490"
    ),
    MEDICAL_POST(
            "Medical / First-Aid Post",
            "MED",
            6.0,
            4.0,
            null,
            true,
            true,
            false,
            false,
            true,
            false,
            "#EF4444",
            "#B91C1C"
    ),
    BARRICADE(
            "Barricade / Crowd Barrier",
            "BAR",
            6.0,
            0.6,
            null,
            true,
            true,
            false,
            false,
            false,
            false,
            "#64748B",
            "#334155"
    ),
    CONTROL_ROOM(
            "Control / Ops Room",
            "CTL",
            6.0,
            4.0,
            null,
            true,
            true,
            false,
            false,
            true,
            false,
            "#3B82F6",
            "#1D4ED8"
    ),
    AMBULANCE_LANE(
            "Ambulance Access Lane",
            "AMB",
            18.0,
            4.0,
            null,
            false,
            false,
            true,
            false,
            false,
            false,
            "#DC2626",
            "#991B1B"
    ),
    FIRE_ACCESS_LANE(
            "Fire Access Lane",
            "FIR",
            18.0,
            4.0,
            null,
            false,
            false,
            true,
            false,
            false,
            false,
            "#EA580C",
            "#9A3412"
    );

    private final String displayName;
    private final String shortCode;
    private final double defaultWidthM;
    private final double defaultHeightM;
    private final Double defaultThroughputPerMin;
    private final boolean rigidStructure;
    private final boolean obstructsExit;
    private final boolean emergencyLane;
    private final boolean exitPortal;
    private final boolean requiresWalkableAccess;
    private final boolean supportsThroughput;
    private final String fillColor;
    private final String strokeColor;

    InfrastructureType(String displayName,
                       String shortCode,
                       double defaultWidthM,
                       double defaultHeightM,
                       Double defaultThroughputPerMin,
                       boolean rigidStructure,
                       boolean obstructsExit,
                       boolean emergencyLane,
                       boolean exitPortal,
                       boolean requiresWalkableAccess,
                       boolean supportsThroughput,
                       String fillColor,
                       String strokeColor) {
        this.displayName = displayName;
        this.shortCode = shortCode;
        this.defaultWidthM = defaultWidthM;
        this.defaultHeightM = defaultHeightM;
        this.defaultThroughputPerMin = defaultThroughputPerMin;
        this.rigidStructure = rigidStructure;
        this.obstructsExit = obstructsExit;
        this.emergencyLane = emergencyLane;
        this.exitPortal = exitPortal;
        this.requiresWalkableAccess = requiresWalkableAccess;
        this.supportsThroughput = supportsThroughput;
        this.fillColor = fillColor;
        this.strokeColor = strokeColor;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getShortCode() {
        return shortCode;
    }

    public double getDefaultWidthM() {
        return defaultWidthM;
    }

    public double getDefaultHeightM() {
        return defaultHeightM;
    }

    public Double getDefaultThroughputPerMin() {
        return defaultThroughputPerMin;
    }

    public boolean isRigidStructure() {
        return rigidStructure;
    }

    public boolean isObstructsExit() {
        return obstructsExit;
    }

    public boolean isEmergencyLane() {
        return emergencyLane;
    }

    public boolean isExitPortal() {
        return exitPortal;
    }

    public boolean isRequiresWalkableAccess() {
        return requiresWalkableAccess;
    }

    public boolean isSupportsThroughput() {
        return supportsThroughput;
    }

    public String getFillColor() {
        return fillColor;
    }

    public String getStrokeColor() {
        return strokeColor;
    }
}
