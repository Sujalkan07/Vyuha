package com.vyuha.monitoring;

/**
 * Live zone congestion heatmap bands aligned with design.md reserved colors.
 * Never uses "Safe" or "Certified" labels.
 */
public enum ZoneCongestionBand {

    NO_DATA("No Reading", "rgba(148, 163, 184, 0.14)", "#94A3B8", false, "badge-neutral"),
    STALE("Stale Reading", "rgba(148, 163, 184, 0.28)", "#64748B", true, "badge-low"),
    LOW_LOAD("Below 60% Cap", "rgba(15, 118, 110, 0.28)", "#0F766E", false, "badge-verified"),
    MODERATE_LOAD("60–80% Cap", "rgba(180, 83, 9, 0.28)", "#B45309", false, "badge-low"),
    HIGH_LOAD("80–95% Cap", "rgba(194, 65, 12, 0.34)", "#C2410C", false, "badge-high"),
    CRITICAL_LOAD("> 95% Cap", "rgba(185, 28, 28, 0.38)", "#B91C1C", false, "badge-critical"),
    UNRATED("No Reviewed Cap", "rgba(2, 132, 199, 0.22)", "#0284C7", false, "badge-info");

    private final String label;
    private final String fillColor;
    private final String strokeColor;
    private final boolean hatched;
    private final String badgeClass;

    ZoneCongestionBand(String label, String fillColor, String strokeColor, boolean hatched, String badgeClass) {
        this.label = label;
        this.fillColor = fillColor;
        this.strokeColor = strokeColor;
        this.hatched = hatched;
        this.badgeClass = badgeClass;
    }

    public String getLabel() {
        return label;
    }

    public String getFillColor() {
        return fillColor;
    }

    public String getStrokeColor() {
        return strokeColor;
    }

    public boolean isHatched() {
        return hatched;
    }

    public String getBadgeClass() {
        return badgeClass;
    }
}
