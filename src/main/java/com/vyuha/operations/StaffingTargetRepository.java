package com.vyuha.operations;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffingTargetRepository extends JpaRepository<StaffingTarget, Long> {

    Optional<StaffingTarget> findByEventId(Long eventId);
}
