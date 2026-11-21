package com.vyuha.monitoring;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OccupancyReadingRepository extends JpaRepository<OccupancyReading, Long> {

    @Query("""
            SELECT r FROM OccupancyReading r
            LEFT JOIN FETCH r.zone
            LEFT JOIN FETCH r.gate
            WHERE r.event.id = :eventId
            ORDER BY r.recordedAt DESC, r.id DESC
            """)
    List<OccupancyReading> findByEventIdWithDetails(@Param("eventId") Long eventId);

    @Query("""
            SELECT r FROM OccupancyReading r
            LEFT JOIN FETCH r.zone
            LEFT JOIN FETCH r.gate
            WHERE r.event.id = :eventId AND r.dataSource = :dataSource
            ORDER BY r.recordedAt DESC, r.id DESC
            """)
    List<OccupancyReading> findByEventIdAndDataSourceOrderByRecordedAtDescIdDesc(
            @Param("eventId") Long eventId,
            @Param("dataSource") OccupancyDataSource dataSource);

    void deleteByEventIdAndDataSource(Long eventId, OccupancyDataSource dataSource);
}
