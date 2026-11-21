package com.vyuha.event;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportingDocumentRepository extends JpaRepository<SupportingDocument, Long> {

    @Query("""
            SELECT d FROM SupportingDocument d
            JOIN FETCH d.uploadedBy
            WHERE d.event.id = :eventId
            ORDER BY d.uploadedAt DESC, d.id DESC
            """)
    List<SupportingDocument> findByEventIdWithUploader(@Param("eventId") Long eventId);

    Optional<SupportingDocument> findByIdAndEventId(Long id, Long eventId);
}
