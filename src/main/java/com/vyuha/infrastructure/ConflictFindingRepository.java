package com.vyuha.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConflictFindingRepository extends JpaRepository<ConflictFinding, Long> {

    List<ConflictFinding> findByVenuePlanIdOrderByActiveDescIdAsc(Long venuePlanId);

    Optional<ConflictFinding> findByIdAndVenuePlanId(Long id, Long venuePlanId);

    Optional<ConflictFinding> findByVenuePlanIdAndFindingKey(Long venuePlanId, String findingKey);

    void deleteByVenuePlanId(Long venuePlanId);
}
