package com.vyuha.grid;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FunctionalZoneRepository extends JpaRepository<FunctionalZone, Long> {

    List<FunctionalZone> findByVenuePlanIdOrderBySortOrderAscIdAsc(Long venuePlanId);

    Optional<FunctionalZone> findByIdAndVenuePlanId(Long id, Long venuePlanId);

    boolean existsByVenuePlanIdAndZoneCodeIgnoreCase(Long venuePlanId, String zoneCode);

    boolean existsByVenuePlanIdAndZoneCodeIgnoreCaseAndIdNot(Long venuePlanId, String zoneCode, Long id);

    void deleteByVenuePlanId(Long venuePlanId);
}
