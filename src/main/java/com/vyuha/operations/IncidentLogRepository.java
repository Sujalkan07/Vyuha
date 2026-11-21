package com.vyuha.operations;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentLogRepository extends JpaRepository<IncidentLog, Long> {

    @Query("""
            SELECT i FROM IncidentLog i
            LEFT JOIN FETCH i.zone
            LEFT JOIN FETCH i.assignedStaff
            LEFT JOIN FETCH i.reportedBy
            WHERE i.event.id = :eventId
            ORDER BY i.reportedAt DESC, i.id DESC
            """)
    List<IncidentLog> findByEventIdWithDetails(@Param("eventId") Long eventId);

    Optional<IncidentLog> findByIdAndEventId(Long id, Long eventId);
}
