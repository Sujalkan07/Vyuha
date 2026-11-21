package com.vyuha.network;

/**
 * Traversal directionality of a pedestrian pathway edge.
 */
public enum PathwayDirection {
    BIDIRECTIONAL("Bidirectional"),
    ONE_WAY("One-Way");

    private final String displayName;

    PathwayDirection(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
