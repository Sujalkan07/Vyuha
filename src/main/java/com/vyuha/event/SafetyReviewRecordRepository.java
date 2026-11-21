package com.vyuha.event;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SafetyReviewRecordRepository extends JpaRepository<SafetyReviewRecord, Long> {

    @Query("""
            SELECT r FROM SafetyReviewRecord r
            JOIN FETCH r.reviewer
            LEFT JOIN FETCH r.respondedBy
            WHERE r.event.id = :eventId
            ORDER BY r.reviewedAt DESC, r.id DESC
            """)
    List<SafetyReviewRecord> findByEventIdWithUsers(@Param("eventId") Long eventId);

    @Query("""
            SELECT r FROM SafetyReviewRecord r
            JOIN FETCH r.reviewer
            LEFT JOIN FETCH r.respondedBy
            WHERE r.id = :reviewId AND r.event.id = :eventId
            """)
    Optional<SafetyReviewRecord> findByIdAndEventIdWithUsers(@Param("reviewId") Long reviewId,
                                                             @Param("eventId") Long eventId);
}
