package com.vyuha.venue;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExclusionAreaRepository extends JpaRepository<ExclusionArea, Long> {

    List<ExclusionArea> findByVenuePlanIdOrderBySortOrderAscIdAsc(Long venuePlanId);

    Optional<ExclusionArea> findByIdAndVenuePlanId(Long id, Long venuePlanId);

    void deleteByVenuePlanId(Long venuePlanId);
}
