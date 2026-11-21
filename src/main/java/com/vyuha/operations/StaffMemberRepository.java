package com.vyuha.operations;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffMemberRepository extends JpaRepository<StaffMember, Long> {

    @Query("""
            SELECT s FROM StaffMember s
            LEFT JOIN FETCH s.user
            WHERE s.event.id = :eventId
            ORDER BY s.roleType ASC, s.name ASC, s.id ASC
            """)
    List<StaffMember> findByEventIdWithUser(@Param("eventId") Long eventId);

    Optional<StaffMember> findByIdAndEventId(Long id, Long eventId);
}
