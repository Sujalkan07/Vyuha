package com.vyuha.event;

/**
 * Deterministic checklist completion summary for an event.
 */
public record ChecklistProgress(
        int totalItems,
        int applicableItems,
        int completedCount,
        int inProgressCount,
        int notStartedCount,
        int notApplicableCount,
        int signedOffCount,
        int completionPct
) {
}
