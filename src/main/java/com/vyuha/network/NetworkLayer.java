package com.vyuha.network;

/**
 * Multi-layer movement roles for pedestrian pathways.
 */
public enum NetworkLayer {
    NORMAL_ENTRY("Normal Entry", "Entry", "#10B981", 3.5, false),
    INTERNAL_CIRCULATION("Internal Circulation", "Circulation", "#2563EB", 3.0, false),
    NORMAL_EXIT("Normal Exit", "Exit", "#0F766E", 3.5, false),
    EMERGENCY_ACCESS("Emergency Access", "Emergency", "#DC2626", 4.0, true);

    private final String displayName;
    private final String shortLabel;
    private final String colorHex;
    private final double defaultWidthM;
    private final boolean dashed;

    NetworkLayer(String displayName,
                 String shortLabel,
                 String colorHex,
                 double defaultWidthM,
                 boolean dashed) {
        this.displayName = displayName;
        this.shortLabel = shortLabel;
        this.colorHex = colorHex;
        this.defaultWidthM = defaultWidthM;
        this.dashed = dashed;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getShortLabel() {
        return shortLabel;
    }

    public String getColorHex() {
        return colorHex;
    }

    public double getDefaultWidthM() {
        return defaultWidthM;
    }

    public boolean isDashed() {
        return dashed;
    }
}
