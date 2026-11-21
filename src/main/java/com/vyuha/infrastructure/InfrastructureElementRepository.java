package com.vyuha.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InfrastructureElementRepository extends JpaRepository<InfrastructureElement, Long> {

    List<InfrastructureElement> findByVenuePlanIdOrderBySortOrderAscIdAsc(Long venuePlanId);

    Optional<InfrastructureElement> findByIdAndVenuePlanId(Long id, Long venuePlanId);

    Optional<InfrastructureElement> findByVenuePlanIdAndElementUid(Long venuePlanId, String elementUid);

    void deleteByVenuePlanId(Long venuePlanId);
}
