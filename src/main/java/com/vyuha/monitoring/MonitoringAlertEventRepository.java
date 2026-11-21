package com.vyuha.monitoring;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitoringAlertEventRepository extends JpaRepository<MonitoringAlertEvent, Long> {

    @Query("""
            SELECT a FROM MonitoringAlertEvent a
            LEFT JOIN FETCH a.zone
            WHERE a.event.id = :eventId
            ORDER BY a.triggeredAt DESC, a.id DESC
            """)
    List<MonitoringAlertEvent> findByEventIdWithDetails(@Param("eventId") Long eventId);

    @Query("""
            SELECT a FROM MonitoringAlertEvent a
            LEFT JOIN FETCH a.zone
            WHERE a.event.id = :eventId AND a.dataSource = :dataSource AND a.resolvedAt IS NULL
            ORDER BY a.triggeredAt DESC, a.id DESC
            """)
    List<MonitoringAlertEvent> findByEventIdAndDataSourceAndResolvedAtIsNullOrderByTriggeredAtDescIdDesc(
            @Param("eventId") Long eventId,
            @Param("dataSource") OccupancyDataSource dataSource);

    Optional<MonitoringAlertEvent> findByIdAndEventId(Long id, Long eventId);

    void deleteByEventIdAndDataSource(Long eventId, OccupancyDataSource dataSource);
}
