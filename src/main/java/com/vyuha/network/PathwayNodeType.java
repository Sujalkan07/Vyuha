package com.vyuha.network;

/**
 * Classification of nodes in the pedestrian movement graph.
 */
public enum PathwayNodeType {
    ENTRY_GATE("Entry Gate", "ENT", "#10B981", "#047857"),
    EXIT_GATE("Exit Gate", "EXT", "#059669", "#065F46"),
    EMERGENCY_EXIT("Emergency Exit", "EEX", "#DC2626", "#991B1B"),
    SECURITY_CHECKPOINT("Security Checkpoint", "SEC", "#F59E0B", "#B45309"),
    ZONE_HUB("Zone Hub", "ZON", "#3B82F6", "#1D4ED8"),
    INTERSECTION("Walkway Intersection", "INT", "#475569", "#1E293B"),
    SERVICE_POINT("Service / Medical Point", "SRV", "#EF4444", "#B91C1C");

    private final String displayName;
    private final String shortCode;
    private final String fillColor;
    private final String strokeColor;

    PathwayNodeType(String displayName, String shortCode, String fillColor, String strokeColor) {
        this.displayName = displayName;
        this.shortCode = shortCode;
        this.fillColor = fillColor;
        this.strokeColor = strokeColor;
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
}
