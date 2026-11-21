package com.vyuha.emergency;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, Long> {

    List<EmergencyContact> findByEventIdOrderByPriorityOrderAscIdAsc(Long eventId);

    Optional<EmergencyContact> findByIdAndEventId(Long id, Long eventId);

    void deleteByEventId(Long eventId);
}
