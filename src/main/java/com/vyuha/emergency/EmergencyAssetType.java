package com.vyuha.emergency;

/**
 * Supported Phase 8 emergency preparedness asset categories anchored to an Event venue plan.
 */
public enum EmergencyAssetType {
    EMERGENCY_EXIT("Emergency Exit", "EEX", "#DC2626", "#991B1B", "POINT", 4.0, false),
    ASSEMBLY_POINT("Assembly Point", "ASM", "#0F766E", "#115E59", "POLYGON", null, false),
    AMBULANCE_ROUTE("Ambulance Route", "AMB", "#DC2626", "#991B1B", "LINESTRING", 4.0, true),
    FIRE_ROUTE("Fire Access Route", "FIR", "#EA580C", "#9A3412", "LINESTRING", 4.0, true);

    private final String displayName;
    private final String shortCode;
    private final String fillColor;
    private final String strokeColor;
    private final String primaryGeometryKind;
    private final Double defaultMinWidthM;
    private final boolean vehicleRoute;

    EmergencyAssetType(String displayName,
                       String shortCode,
                       String fillColor,
                       String strokeColor,
                       String primaryGeometryKind,
                       Double defaultMinWidthM,
                       boolean vehicleRoute) {
        this.displayName = displayName;
        this.shortCode = shortCode;
        this.fillColor = fillColor;
        this.strokeColor = strokeColor;
        this.primaryGeometryKind = primaryGeometryKind;
        this.defaultMinWidthM = defaultMinWidthM;
        this.vehicleRoute = vehicleRoute;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getFillColor() {
        return fillColor;
    }

    public String getStrokeColor() {
        return strokeColor;
    }

    public String getPrimaryGeometryKind() {
        return primaryGeometryKind;
    }

    public Double getDefaultMinWidthM() {
        return defaultMinWidthM;
    }

    public boolean isVehicleRoute() {
        return vehicleRoute;
    }
}
