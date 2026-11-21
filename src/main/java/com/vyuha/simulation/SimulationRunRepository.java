package com.vyuha.simulation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SimulationRunRepository extends JpaRepository<SimulationRun, Long> {

    @Query("""
            SELECT r FROM SimulationRun r
            JOIN FETCH r.snapshot
            LEFT JOIN FETCH r.createdBy
            WHERE r.event.id = :eventId
            ORDER BY r.audit.createdAt DESC, r.id DESC
            """)
    List<SimulationRun> findByEventIdWithSnapshotOrderByCreatedDesc(@Param("eventId") Long eventId);

    @Query("""
            SELECT r FROM SimulationRun r
            JOIN FETCH r.snapshot
            LEFT JOIN FETCH r.createdBy
            WHERE r.id = :id AND r.event.id = :eventId
            """)
    Optional<SimulationRun> findByIdAndEventIdWithSnapshot(@Param("id") Long id, @Param("eventId") Long eventId);

    List<SimulationRun> findBySnapshotId(Long snapshotId);
}
