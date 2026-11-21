package com.vyuha.operations;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffAssignmentRepository extends JpaRepository<StaffAssignment, Long> {

    @Query("""
            SELECT a FROM StaffAssignment a
            JOIN FETCH a.staff s
            LEFT JOIN FETCH s.user
            LEFT JOIN FETCH a.zone
            LEFT JOIN FETCH a.infra
            WHERE a.event.id = :eventId
            ORDER BY a.shiftStart ASC, a.shiftEnd ASC, a.id ASC
            """)
    List<StaffAssignment> findByEventIdWithDetails(@Param("eventId") Long eventId);

    @Query("""
            SELECT a FROM StaffAssignment a
            JOIN FETCH a.event e
            JOIN FETCH a.staff s
            LEFT JOIN FETCH a.zone
            LEFT JOIN FETCH a.infra
            WHERE s.user.id = :userId
            ORDER BY a.shiftStart ASC, a.id ASC
            """)
    List<StaffAssignment> findByStaffUserIdWithDetails(@Param("userId") Long userId);

    Optional<StaffAssignment> findByIdAndEventId(Long id, Long eventId);
}
