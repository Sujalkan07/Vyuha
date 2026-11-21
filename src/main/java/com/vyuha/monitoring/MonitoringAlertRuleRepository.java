package com.vyuha.monitoring;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitoringAlertRuleRepository extends JpaRepository<MonitoringAlertRule, Long> {

    @Query("""
            SELECT r FROM MonitoringAlertRule r
            LEFT JOIN FETCH r.zone z
            WHERE r.event.id = :eventId
            ORDER BY CASE WHEN z IS NULL THEN 0 ELSE 1 END ASC, z.id ASC, r.id ASC
            """)
    List<MonitoringAlertRule> findByEventIdWithZone(@Param("eventId") Long eventId);

    Optional<MonitoringAlertRule> findByEventIdAndZoneIsNull(Long eventId);

    Optional<MonitoringAlertRule> findByEventIdAndZoneId(Long eventId, Long zoneId);

    Optional<MonitoringAlertRule> findByIdAndEventId(Long id, Long eventId);
}
