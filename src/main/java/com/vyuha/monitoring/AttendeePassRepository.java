package com.vyuha.monitoring;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendeePassRepository extends JpaRepository<AttendeePass, Long> {

    @Query("""
            SELECT p FROM AttendeePass p
            LEFT JOIN FETCH p.lastGate
            LEFT JOIN FETCH p.lastZone
            WHERE p.event.id = :eventId
            ORDER BY p.id DESC
            """)
    List<AttendeePass> findByEventIdWithDetails(@Param("eventId") Long eventId);

    Optional<AttendeePass> findByEventIdAndPassCodeIgnoreCase(Long eventId, String passCode);

    Optional<AttendeePass> findByIdAndEventId(Long id, Long eventId);

    boolean existsByEventIdAndPassCodeIgnoreCase(Long eventId, String passCode);

    int countByEventIdAndStatus(Long eventId, PassStatus status);

    int countByEventIdAndStatusAndLastZoneId(Long eventId, PassStatus status, Long zoneId);

    int countByEventIdAndStatusAndLastZoneIsNull(Long eventId, PassStatus status);
}
