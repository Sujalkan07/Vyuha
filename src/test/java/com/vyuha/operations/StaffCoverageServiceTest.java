package com.vyuha.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StaffCoverageServiceTest {

    private final StaffCoverageService coverageService = new StaffCoverageService();

    private FunctionalZone zone(long id, String code, ZoneType type) {
        FunctionalZone z = new FunctionalZone();
        ReflectionTestUtils.setField(z, "id", id);
        z.setZoneCode(code);
        z.setName(code);
        z.setZoneType(type);
        return z;
    }

    private InfrastructureElement infra(long id, String label, InfrastructureType type, boolean active) {
        InfrastructureElement el = new InfrastructureElement();
        ReflectionTestUtils.setField(el, "id", id);
        el.setLabel(label);
        el.setElementType(type);
        el.setActive(active);
        return el;
    }

    private StaffMember staff(long id, String name, StaffRoleType role) {
        StaffMember m = new StaffMember();
        ReflectionTestUtils.setField(m, "id", id);
        m.setName(name);
        m.setRoleType(role);
        return m;
    }

    private StaffAssignment shift(long id, StaffMember s, FunctionalZone z, InfrastructureElement el,
                                  LocalDateTime start, LocalDateTime end) {
        StaffAssignment a = new StaffAssignment();
        ReflectionTestUtils.setField(a, "id", id);
        a.setStaff(s);
        a.setZone(z);
        a.setInfra(el);
        a.setShiftStart(start);
        a.setShiftEnd(end);
        return a;
    }

    @Test
    void detectsUnderStaffedGatesZonesMedicalTimelineGapsAndDoubleBookings() {
        LocalDateTime eventStart = LocalDateTime.of(2026, 12, 20, 16, 0);
        LocalDateTime eventEnd = LocalDateTime.of(2026, 12, 20, 20, 0);

        FunctionalZone aud = zone(1L, "AUD-1", ZoneType.AUDIENCE);
        InfrastructureElement gate = infra(10L, "North Entry", InfrastructureType.ENTRY_GATE, true);
        InfrastructureElement medPost = infra(11L, "First Aid Tent", InfrastructureType.MEDICAL_POST, true);

        StaffMember sec1 = staff(100L, "Security A", StaffRoleType.SECURITY);
        StaffMember volUnscheduled = staff(101L, "Volunteer Standby", StaffRoleType.VOLUNTEER);

        StaffingTarget targets = new StaffingTarget();
        targets.setMinSecurityPerActiveGate(2);
        targets.setMinStaffPerPublicZone(2);
        targets.setMinMedicalStaffTotal(1);
        targets.setMinSupervisorsTotal(1);
        targets.setAttendeesPerStaffRatio(100); // expected 200 -> 2 staff needed concurrently

        // Only sec1 works 16:00-18:00 (double-booked 16:30-17:30), leaving 18:00-20:00 completely uncovered!
        List<StaffAssignment> assignments = List.of(
                shift(1L, sec1, aud, gate, eventStart, eventStart.plusHours(2)),
                shift(2L, sec1, aud, null, eventStart.plusMinutes(30), eventStart.plusMinutes(90))
        );

        OperationsDtos.StaffCoverageResult res = coverageService.analyze(
                200,
                eventStart,
                eventEnd,
                List.of(aud),
                List.of(gate, medPost),
                List.of(sec1, volUnscheduled),
                assignments,
                targets
        );

        assertThat(res.findings()).anyMatch(f -> "GATE_UNDERSTAFFED".equals(f.code())
                && f.severity() == FindingSeverity.HIGH);
        assertThat(res.findings()).anyMatch(f -> "ZONE_UNDERSTAFFED".equals(f.code())
                && f.severity() == FindingSeverity.HIGH);
        assertThat(res.findings()).anyMatch(f -> "MEDICAL_UNDERSTAFFED".equals(f.code())
                && f.severity() == FindingSeverity.CRITICAL);
        assertThat(res.findings()).anyMatch(f -> "MEDICAL_POST_UNSTAFFED".equals(f.code())
                && f.severity() == FindingSeverity.CRITICAL);
        assertThat(res.findings()).anyMatch(f -> "SUPERVISOR_UNDERSTAFFED".equals(f.code())
                && f.severity() == FindingSeverity.CRITICAL);
        assertThat(res.findings()).anyMatch(f -> "STAFF_RATIO_SHORTFALL".equals(f.code()));
        assertThat(res.findings()).anyMatch(f -> "UNCOVERED_EVENT_WINDOW".equals(f.code())
                && f.title().contains("18:00–20:00"));
        assertThat(res.findings()).anyMatch(f -> "SHIFT_UNDERSTAFFED".equals(f.code())
                && f.title().contains("16:00–18:00"));
        assertThat(res.findings()).anyMatch(f -> "STAFF_DOUBLE_BOOKED".equals(f.code())
                && f.severity() == FindingSeverity.LOW);
        assertThat(res.findings()).anyMatch(f -> "UNSCHEDULED_STAFF".equals(f.code())
                && f.severity() == FindingSeverity.INFO);
    }

    @Test
    void returnsZeroFindingsWhenAllTargetsAndFullTimelineAreCovered() {
        LocalDateTime eventStart = LocalDateTime.of(2026, 12, 20, 16, 0);
        LocalDateTime eventEnd = LocalDateTime.of(2026, 12, 20, 20, 0);

        FunctionalZone aud = zone(1L, "AUD-1", ZoneType.AUDIENCE);
        InfrastructureElement gate = infra(10L, "North Entry", InfrastructureType.ENTRY_GATE, true);
        InfrastructureElement medPost = infra(11L, "First Aid Tent", InfrastructureType.MEDICAL_POST, true);

        StaffMember sec1 = staff(100L, "Security A", StaffRoleType.SECURITY);
        StaffMember sec2 = staff(101L, "Security B", StaffRoleType.SECURITY);
        StaffMember med1 = staff(102L, "Medic C", StaffRoleType.MEDICAL);
        StaffMember sup1 = staff(103L, "Supervisor D", StaffRoleType.SUPERVISOR);

        StaffingTarget targets = new StaffingTarget();
        targets.setMinSecurityPerActiveGate(2);
        targets.setMinStaffPerPublicZone(2);
        targets.setMinMedicalStaffTotal(1);
        targets.setMinSupervisorsTotal(1);
        targets.setAttendeesPerStaffRatio(100); // expected 400 -> 4 staff needed

        List<StaffAssignment> assignments = List.of(
                shift(1L, sec1, aud, gate, eventStart, eventEnd),
                shift(2L, sec2, aud, gate, eventStart, eventEnd),
                shift(3L, med1, null, medPost, eventStart, eventEnd),
                shift(4L, sup1, null, null, eventStart, eventEnd)
        );

        OperationsDtos.StaffCoverageResult res = coverageService.analyze(
                400,
                eventStart,
                eventEnd,
                List.of(aud),
                List.of(gate, medPost),
                List.of(sec1, sec2, med1, sup1),
                assignments,
                targets
        );

        assertThat(res.totalFindingCount()).isZero();
        assertThat(res.criticalFindingCount()).isZero();
        assertThat(res.highFindingCount()).isZero();
    }
}
