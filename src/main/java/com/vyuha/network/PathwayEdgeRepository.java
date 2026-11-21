package com.vyuha.network;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PathwayEdgeRepository extends JpaRepository<PathwayEdge, Long> {

    List<PathwayEdge> findByVenuePlanIdOrderBySortOrderAscIdAsc(Long venuePlanId);

    Optional<PathwayEdge> findByIdAndVenuePlanId(Long id, Long venuePlanId);

    List<PathwayEdge> findByFromNodeIdOrToNodeId(Long fromNodeId, Long toNodeId);

    void deleteByFromNodeIdOrToNodeId(Long fromNodeId, Long toNodeId);

    void deleteByVenuePlanId(Long venuePlanId);
}
