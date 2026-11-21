package com.vyuha.grid;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueGridConfigRepository extends JpaRepository<VenueGridConfig, Long> {

    Optional<VenueGridConfig> findByVenuePlanId(Long venuePlanId);
}
