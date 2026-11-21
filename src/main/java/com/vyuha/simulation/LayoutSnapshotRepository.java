package com.vyuha.simulation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LayoutSnapshotRepository extends JpaRepository<LayoutSnapshot, Long> {

    @Query("""
            SELECT s FROM LayoutSnapshot s
            LEFT JOIN FETCH s.createdBy
            WHERE s.event.id = :eventId
            ORDER BY s.versionNumber DESC, s.id DESC
            """)
    List<LayoutSnapshot> findByEventIdOrderByVersionNumberDesc(@Param("eventId") Long eventId);

    @Query("""
            SELECT s FROM LayoutSnapshot s
            LEFT JOIN FETCH s.createdBy
            WHERE s.id = :id AND s.event.id = :eventId
            """)
    Optional<LayoutSnapshot> findByIdAndEventId(@Param("id") Long id, @Param("eventId") Long eventId);

    @Query("SELECT COALESCE(MAX(s.versionNumber), 0) FROM LayoutSnapshot s WHERE s.event.id = :eventId")
    int findMaxVersionNumber(@Param("eventId") Long eventId);
}
