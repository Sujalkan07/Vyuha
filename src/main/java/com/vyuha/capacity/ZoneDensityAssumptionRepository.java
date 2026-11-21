package com.vyuha.capacity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneDensityAssumptionRepository extends JpaRepository<ZoneDensityAssumption, Long> {

    List<ZoneDensityAssumption> findByCapacityReviewIdOrderByIdAsc(Long capacityReviewId);

    Optional<ZoneDensityAssumption> findByCapacityReviewIdAndFunctionalZoneId(Long capacityReviewId, Long functionalZoneId);
}
