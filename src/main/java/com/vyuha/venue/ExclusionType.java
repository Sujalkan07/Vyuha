package com.vyuha.venue;

/**
 * Categories of non-gathering or dedicated-corridor exclusion polygons subtracted from Gross Site Area.
 */
public enum ExclusionType {
    BUILDING("Building / Permanent Structure", false, "#94A3B8", "#475569"),
    STAGE("Stage / Performance Deck", false, "#A855F7", "#6B21A8"),
    EQUIPMENT("AV / Power / Equipment", false, "#F59E0B", "#B45309"),
    RESTRICTED("Restricted / No-Access Area", false, "#EF4444", "#B91C1C"),
    FACILITY("Back-of-House / Utility Facility", false, "#64748B", "#334155"),
    RESERVED_WALKWAY("Reserved Walkway / Corridor", true, "#0EA5E9", "#0369A1");

    private final String displayName;
    private final boolean corridor;
    private final String fillColor;
    private final String strokeColor;

    ExclusionType(String displayName, boolean corridor, String fillColor, String strokeColor) {
        this.displayName = displayName;
        this.corridor = corridor;
        this.fillColor = fillColor;
        this.strokeColor = strokeColor;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isCorridor() {
        return corridor;
    }

    public String getFillColor() {
        return fillColor;
    }

    public String getStrokeColor() {
        return strokeColor;
    }
}
