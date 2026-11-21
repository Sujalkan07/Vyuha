package com.vyuha.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Query("""
            SELECT e FROM Event e
            JOIN FETCH e.organizer
            LEFT JOIN FETCH e.assignedReviewer
            WHERE e.id = :id
            """)
    Optional<Event> findByIdWithUsers(@Param("id") Long id);

    @Query("""
            SELECT e FROM Event e
            JOIN FETCH e.organizer org
            LEFT JOIN FETCH e.assignedReviewer rev
            WHERE (:category IS NULL OR e.category = :category)
              AND (:status IS NULL OR e.status = :status)
              AND (:fromDt IS NULL OR e.startDateTime >= :fromDt)
              AND (:toDt IS NULL OR e.startDateTime <= :toDt)
              AND (:searchPattern IS NULL
                   OR LOWER(e.name) LIKE :searchPattern
                   OR LOWER(COALESCE(e.locationName, '')) LIKE :searchPattern
                   OR LOWER(COALESCE(e.locationAddress, '')) LIKE :searchPattern
                   OR LOWER(org.fullName) LIKE :searchPattern)
            ORDER BY e.audit.updatedAt DESC, e.id DESC
            """)
    List<Event> searchEvents(@Param("searchPattern") String searchPattern,
                             @Param("category") EventCategory category,
                             @Param("status") EventStatus status,
                             @Param("fromDt") LocalDateTime fromDt,
                             @Param("toDt") LocalDateTime toDt);
}
