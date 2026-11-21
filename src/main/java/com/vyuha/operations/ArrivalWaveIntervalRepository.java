package com.vyuha.operations;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArrivalWaveIntervalRepository extends JpaRepository<ArrivalWaveInterval, Long> {

    @Query("""
            SELECT w FROM ArrivalWaveInterval w
            LEFT JOIN FETCH w.targetGate
            WHERE w.event.id = :eventId
            ORDER BY w.startMinute ASC, w.endMinute ASC, w.id ASC
            """)
    List<ArrivalWaveInterval> findByEventIdWithGate(@Param("eventId") Long eventId);

    Optional<ArrivalWaveInterval> findByIdAndEventId(Long id, Long eventId);

    void deleteByEventId(Long eventId);
}
