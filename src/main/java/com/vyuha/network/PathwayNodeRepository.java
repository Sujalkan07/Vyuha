package com.vyuha.network;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PathwayNodeRepository extends JpaRepository<PathwayNode, Long> {

    List<PathwayNode> findByVenuePlanIdOrderBySortOrderAscIdAsc(Long venuePlanId);

    Optional<PathwayNode> findByIdAndVenuePlanId(Long id, Long venuePlanId);

    void deleteByVenuePlanId(Long venuePlanId);
}
