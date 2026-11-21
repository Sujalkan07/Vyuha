package com.vyuha.emergency;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmergencyAssetRepository extends JpaRepository<EmergencyAsset, Long> {

    List<EmergencyAsset> findByEventIdOrderByAssetTypeAscIdAsc(Long eventId);

    Optional<EmergencyAsset> findByIdAndEventId(Long id, Long eventId);

    void deleteByEventId(Long eventId);
}
