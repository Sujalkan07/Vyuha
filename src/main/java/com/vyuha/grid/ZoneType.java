package com.vyuha.grid;

/**
 * Standardized functional zone categories, colors, walkability, and capacity roles.
 */
public enum ZoneType {
    AUDIENCE("Audience / Main Gathering", "#3B82F6", "#1D4ED8", false, true, CapacityBucket.GATHERING, "AUD"),
    ENTRY("Entry Zone", "#10B981", "#047857", false, true, CapacityBucket.CIRCULATION, "ENT"),
    EXIT("Exit Zone", "#059669", "#065F46", false, true, CapacityBucket.CIRCULATION, "EXT"),
    SECURITY_SCREENING("Security Screening", "#F59E0B", "#B45309", false, true, CapacityBucket.HOLDING, "SEC"),
    QUEUE("Queue / Holding Zone", "#EAB308", "#A16207", false, true, CapacityBucket.HOLDING, "QUE"),
    WALKWAY("Walkway / Circulation", "#94A3B8", "#475569", false, true, CapacityBucket.CIRCULATION, "WLK"),
    FOOD_STALLS("Food Stalls / Concessions", "#F97316", "#C2410C", false, true, CapacityBucket.GATHERING, "FOD"),
    RESTROOMS("Restrooms / Sanitation", "#06B6D4", "#0E7490", false, true, CapacityBucket.NON_CAPACITY, "RST"),
    MEDICAL("Medical Post / First Aid", "#EF4444", "#B91C1C", false, true, CapacityBucket.NON_CAPACITY, "MED"),
    EMERGENCY_CORRIDOR("Emergency Corridor", "#DC2626", "#991B1B", true, true, CapacityBucket.NON_CAPACITY, "EMG"),
    RESTRICTED("Restricted / No-Access", "#475569", "#1E293B", true, false, CapacityBucket.NON_CAPACITY, "RES");

    public enum CapacityBucket {
        GATHERING,
        HOLDING,
        CIRCULATION,
        NON_CAPACITY
    }

    private final String displayName;
    private final String fillColor;
    private final String strokeColor;
    private final boolean hatched;
    private final boolean walkable;
    private final CapacityBucket capacityBucket;
    private final String codePrefix;

    ZoneType(String displayName,
             String fillColor,
             String strokeColor,
             boolean hatched,
             boolean walkable,
             CapacityBucket capacityBucket,
             String codePrefix) {
        this.displayName = displayName;
        this.fillColor = fillColor;
        this.strokeColor = strokeColor;
        this.hatched = hatched;
        this.walkable = walkable;
        this.capacityBucket = capacityBucket;
        this.codePrefix = codePrefix;
    }

    public String getDisplayName() {
        return displayName;
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

    public boolean isWalkable() {
        return walkable;
    }

    public CapacityBucket getCapacityBucket() {
        return capacityBucket;
    }

    public String getCodePrefix() {
        return codePrefix;
    }

    public boolean isAccessAnchor() {
        return this == ENTRY || this == EXIT || this == EMERGENCY_CORRIDOR;
    }

    public boolean isCapacityBearing() {
        return capacityBucket == CapacityBucket.GATHERING || capacityBucket == CapacityBucket.HOLDING;
    }
}
