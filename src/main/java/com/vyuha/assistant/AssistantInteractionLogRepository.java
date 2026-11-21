package com.vyuha.assistant;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssistantInteractionLogRepository extends JpaRepository<AssistantInteractionLog, Long> {

    @Query("""
            SELECT l FROM AssistantInteractionLog l
            LEFT JOIN FETCH l.user
            WHERE l.event.id = :eventId
            ORDER BY l.audit.createdAt DESC, l.id DESC
            """)
    List<AssistantInteractionLog> findByEventIdWithUserOrderByCreatedDesc(@Param("eventId") Long eventId);

    java.util.Optional<AssistantInteractionLog> findByIdAndEventId(Long id, Long eventId);
}
