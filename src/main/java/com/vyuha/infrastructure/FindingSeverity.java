package com.vyuha.infrastructure;

/**
 * Standardized conflict finding severity levels mapped directly to design.md tokens.
 */
public enum FindingSeverity {
    CRITICAL(1),
    HIGH(2),
    LOW(3),
    INFO(4);

    private final int sortRank;

    FindingSeverity(int sortRank) {
        this.sortRank = sortRank;
    }

    public int sortRank() {
        return sortRank;
    }
}
