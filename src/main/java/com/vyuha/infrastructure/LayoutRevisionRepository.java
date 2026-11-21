package com.vyuha.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LayoutRevisionRepository extends JpaRepository<LayoutRevision, Long> {

    List<LayoutRevision> findByVenuePlanIdOrderByRevisionNumberAsc(Long venuePlanId);

    Optional<LayoutRevision> findByVenuePlanIdAndCurrentTrue(Long venuePlanId);

    Optional<LayoutRevision> findByVenuePlanIdAndRevisionNumber(Long venuePlanId, int revisionNumber);

    void deleteByVenuePlanIdAndRevisionNumberGreaterThan(Long venuePlanId, int revisionNumber);

    void deleteByVenuePlanId(Long venuePlanId);
}
