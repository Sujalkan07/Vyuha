package com.vyuha.venue;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenuePlanRepository extends JpaRepository<VenuePlan, Long> {

    Optional<VenuePlan> findByEventId(Long eventId);
}
