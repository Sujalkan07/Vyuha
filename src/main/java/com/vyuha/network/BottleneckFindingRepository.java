package com.vyuha.network;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BottleneckFindingRepository extends JpaRepository<BottleneckFinding, Long> {

    List<BottleneckFinding> findByVenuePlanIdOrderByActiveDescIdAsc(Long venuePlanId);

    Optional<BottleneckFinding> findByIdAndVenuePlanId(Long id, Long venuePlanId);

    void deleteByVenuePlanId(Long venuePlanId);
}
