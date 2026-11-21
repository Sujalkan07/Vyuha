package com.vyuha.capacity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapacityReviewRepository extends JpaRepository<CapacityReview, Long> {

    Optional<CapacityReview> findByEventId(Long eventId);
}
