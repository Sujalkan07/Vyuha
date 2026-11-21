package com.vyuha.event;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventChecklistItemRepository extends JpaRepository<EventChecklistItem, Long> {

    @Query("""
            SELECT c FROM EventChecklistItem c
            LEFT JOIN FETCH c.signedOffBy
            WHERE c.event.id = :eventId
            ORDER BY c.sortOrder ASC, c.id ASC
            """)
    List<EventChecklistItem> findByEventIdWithSigner(@Param("eventId") Long eventId);

    Optional<EventChecklistItem> findByIdAndEventId(Long id, Long eventId);

    int countByEventId(Long eventId);
}
