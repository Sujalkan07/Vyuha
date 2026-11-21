package com.vyuha.event;

import java.time.LocalDate;

/**
 * Search and filter parameters for the Events Dashboard.
 */
public record EventSearchCriteria(
        String query,
        EventCategory category,
        EventStatus status,
        LocalDate fromDate,
        LocalDate toDate
) {
}
