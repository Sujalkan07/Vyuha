package com.vyuha.operations;

import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureType;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Pure-Java automated staff & security coverage analyzer.
 * Evaluates active gates, public functional zones, medical coverage, supervisor/attendee ratios,
 * exact timeline shift gaps, and staff double-bookings against planner-configured targets.
 */
@Service
public class StaffCoverageService {

    private static final DateTimeFormatter WINDOW_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public OperationsDtos.StaffCoverageResult analyze(Integer expectedAttendance,
                                                      LocalDateTime eventStart,
                                                      LocalDateTime eventEnd,
                                                      List<FunctionalZone> zones,
                                                      List<InfrastructureElement> elements,
                                                      List<StaffMember> staffMembers,
                                                      List<StaffAssignment> assignments,
                                                      StaffingTarget targets) {
        List<FunctionalZone> safeZones = zones != null ? zones : List.of();
        List<InfrastructureElement> safeElements = elements != null ? elements : List.of();
        List<StaffMember> safeStaff = staffMembers != null ? staffMembers : List.of();
        List<StaffAssignment> safeAssignments = assignments != null ? assignments : List.of();

        int minSecGate = targets != null ? targets.getMinSecurityPerActiveGate() : 2;
        int minStaffZone = targets != null ? targets.getMinStaffPerPublicZone() : 2;
        int minMedTotal = targets != null ? targets.getMinMedicalStaffTotal() : 2;
        int minSupTotal = targets != null ? targets.getMinSupervisorsTotal() : 1;
        int ratio = targets != null ? targets.getAttendeesPerStaffRatio() : 150;

        int secCount = 0;
        int volCount = 0;
        int medCount = 0;
        int supCount = 0;
        for (StaffMember m : safeStaff) {
            if (m == null || m.getRoleType() == null) continue;
            switch (m.getRoleType()) {
                case SECURITY -> secCount++;
                case VOLUNTEER -> volCount++;
                case MEDICAL -> medCount++;
                case SUPERVISOR -> supCount++;
            }
        }

        Set<Long> uniqueAssignedIds = new HashSet<>();
        Set<Long> assignedMedicalIds = new HashSet<>();
        Set<Long> assignedSupervisorIds = new HashSet<>();
        Map<Long, Set<Long>> secOrSupByInfra = new HashMap<>();
        Map<Long, Set<Long>> medByInfra = new HashMap<>();
        Map<Long, Set<Long>> staffByZone = new HashMap<>();
        Map<Long, Set<Long>> medByZone = new HashMap<>();
        Map<Long, List<StaffAssignment>> assignmentsByStaff = new LinkedHashMap<>();

        for (StaffAssignment a : safeAssignments) {
            if (a == null || a.getStaff() == null || a.getStaff().getId() == null) {
                continue;
            }
            Long staffId = a.getStaff().getId();
            StaffRoleType role = a.getStaff().getRoleType();
            uniqueAssignedIds.add(staffId);
            if (role == StaffRoleType.MEDICAL) {
                assignedMedicalIds.add(staffId);
            } else if (role == StaffRoleType.SUPERVISOR) {
                assignedSupervisorIds.add(staffId);
            }

            if (a.getInfra() != null && a.getInfra().getId() != null) {
                Long infraId = a.getInfra().getId();
                if (role == StaffRoleType.SECURITY || role == StaffRoleType.SUPERVISOR) {
                    secOrSupByInfra.computeIfAbsent(infraId, k -> new HashSet<>()).add(staffId);
                }
                if (role == StaffRoleType.MEDICAL) {
                    medByInfra.computeIfAbsent(infraId, k -> new HashSet<>()).add(staffId);
                }
            }
            if (a.getZone() != null && a.getZone().getId() != null) {
                Long zoneId = a.getZone().getId();
                staffByZone.computeIfAbsent(zoneId, k -> new HashSet<>()).add(staffId);
                if (role == StaffRoleType.MEDICAL) {
                    medByZone.computeIfAbsent(zoneId, k -> new HashSet<>()).add(staffId);
                }
            }
            assignmentsByStaff.computeIfAbsent(staffId, k -> new ArrayList<>()).add(a);
        }

        int requiredRatioStaff = (expectedAttendance != null && expectedAttendance > 0 && ratio > 0)
                ? (int) Math.ceil(expectedAttendance / (double) ratio)
                : 0;

        List<OperationsDtos.StaffCoverageFindingDto> findings = new ArrayList<>();

        // 1. Active Gates & Security Checkpoints security coverage
        if (minSecGate > 0) {
            for (InfrastructureElement el : safeElements) {
                if (el == null || !el.isActive() || el.getId() == null || !isGateOrCheckpoint(el.getElementType())) {
                    continue;
                }
                int assigned = secOrSupByInfra.getOrDefault(el.getId(), Set.of()).size();
                if (assigned < minSecGate) {
                    FindingSeverity sev = assigned == 0 ? FindingSeverity.CRITICAL : FindingSeverity.HIGH;
                    findings.add(finding(
                            "GATE_UNDERSTAFFED",
                            sev,
                            "Under-staffed " + el.getElementType().getDisplayName() + ": " + el.getLabel(),
                            "Active portal has fewer assigned Security/Supervisor personnel than the configured minimum target.",
                            assigned + " assigned (target " + minSecGate + ")",
                            "Minimum " + minSecGate + " security/supervisor staff per active gate or checkpoint"
                    ));
                }
            }
        }

        // 2. Public Functional Zones coverage
        if (minStaffZone > 0) {
            for (FunctionalZone zone : safeZones) {
                if (zone == null || zone.getId() == null || !isPublicOperationalZone(zone.getZoneType())) {
                    continue;
                }
                int assigned = staffByZone.getOrDefault(zone.getId(), Set.of()).size();
                if (assigned < minStaffZone) {
                    boolean criticalZone = zone.getZoneType() == ZoneType.AUDIENCE
                            || zone.getZoneType() == ZoneType.QUEUE
                            || zone.getZoneType() == ZoneType.SECURITY_SCREENING;
                    FindingSeverity sev = (assigned == 0 && criticalZone)
                            ? FindingSeverity.CRITICAL
                            : FindingSeverity.HIGH;
                    findings.add(finding(
                            "ZONE_UNDERSTAFFED",
                            sev,
                            "Under-staffed zone: " + zone.getZoneCode() + " (" + zone.getName() + ")",
                            "Functional zone has fewer assigned shift personnel than the configured zone minimum.",
                            assigned + " assigned (target " + minStaffZone + ")",
                            "Minimum " + minStaffZone + " staff assigned per public functional zone"
                    ));
                }
            }
        }

        // 3. Medical Personnel & Medical Post Coverage
        if (minMedTotal > 0 && assignedMedicalIds.size() < minMedTotal) {
            FindingSeverity sev = assignedMedicalIds.isEmpty() ? FindingSeverity.CRITICAL : FindingSeverity.HIGH;
            findings.add(finding(
                    "MEDICAL_UNDERSTAFFED",
                    sev,
                    "Insufficient scheduled medical personnel",
                    "Total medical staff assigned to event shifts is below the planner-configured minimum target.",
                    assignedMedicalIds.size() + " scheduled (target " + minMedTotal + ")",
                    "Minimum " + minMedTotal + " medical personnel scheduled"
            ));
        }
        for (InfrastructureElement el : safeElements) {
            if (el != null && el.isActive() && el.getId() != null
                    && el.getElementType() == InfrastructureType.MEDICAL_POST) {
                int medAtPost = medByInfra.getOrDefault(el.getId(), Set.of()).size();
                if (medAtPost == 0) {
                    findings.add(finding(
                            "MEDICAL_POST_UNSTAFFED",
                            FindingSeverity.CRITICAL,
                            "No medical responder assigned to " + el.getLabel(),
                            "Placed active Medical Post has zero assigned MEDICAL staff members.",
                            "0 medical staff assigned",
                            "Assign at least 1 MEDICAL responder to every active Medical Post"
                    ));
                }
            }
        }
        for (FunctionalZone zone : safeZones) {
            if (zone != null && zone.getId() != null && zone.getZoneType() == ZoneType.MEDICAL) {
                int medInZone = medByZone.getOrDefault(zone.getId(), Set.of()).size();
                if (medInZone == 0) {
                    findings.add(finding(
                            "MEDICAL_ZONE_UNSTAFFED",
                            FindingSeverity.CRITICAL,
                            "No medical responder assigned to zone " + zone.getZoneCode(),
                            "Designated Medical functional zone has zero assigned MEDICAL staff members.",
                            "0 medical staff assigned",
                            "Assign at least 1 MEDICAL responder to medical zones"
                    ));
                }
            }
        }

        // 4. Supervisor Presence & Staff-to-Attendee Ratio
        if (minSupTotal > 0 && assignedSupervisorIds.size() < minSupTotal) {
            FindingSeverity sev = assignedSupervisorIds.isEmpty() ? FindingSeverity.CRITICAL : FindingSeverity.HIGH;
            findings.add(finding(
                    "SUPERVISOR_UNDERSTAFFED",
                    sev,
                    "Insufficient scheduled supervisors",
                    "Assigned supervisor count is below the configured supervisory presence target.",
                    assignedSupervisorIds.size() + " scheduled (target " + minSupTotal + ")",
                    "Minimum " + minSupTotal + " supervisor(s) scheduled"
            ));
        }

        if (requiredRatioStaff > 0 && uniqueAssignedIds.size() < requiredRatioStaff) {
            FindingSeverity sev = uniqueAssignedIds.isEmpty() ? FindingSeverity.CRITICAL : FindingSeverity.HIGH;
            findings.add(finding(
                    "STAFF_RATIO_SHORTFALL",
                    sev,
                    "Scheduled staff below attendee-to-staff ratio target",
                    String.format(Locale.US, "Expected attendance of %,d at 1 staff per %d attendees requires at least %d assigned personnel.",
                            expectedAttendance, ratio, requiredRatioStaff),
                    uniqueAssignedIds.size() + " assigned (target " + requiredRatioStaff + ")",
                    "Target ratio: 1 staff per " + ratio + " expected attendees"
            ));
        }

        // 5. Event Timeline Sweep for Uncovered or Under-Staffed Shift Windows
        if (eventStart != null && eventEnd != null && eventEnd.isAfter(eventStart)) {
            detectTimelineGaps(eventStart, eventEnd, safeAssignments, requiredRatioStaff, findings);
        }

        // 6. Staff Double-Booking Overlap Detection
        for (Map.Entry<Long, List<StaffAssignment>> entry : assignmentsByStaff.entrySet()) {
            List<StaffAssignment> list = entry.getValue();
            if (list.size() < 2) continue;
            list.sort(Comparator.comparing(StaffAssignment::getShiftStart).thenComparing(StaffAssignment::getShiftEnd));
            for (int i = 0; i < list.size() - 1; i++) {
                StaffAssignment cur = list.get(i);
                StaffAssignment next = list.get(i + 1);
                if (cur.getShiftEnd().isAfter(next.getShiftStart())) {
                    findings.add(finding(
                            "STAFF_DOUBLE_BOOKED",
                            FindingSeverity.LOW,
                            "Overlapping shifts for " + cur.getStaff().getName(),
                            "Staff member is assigned to multiple overlapping shift windows ("
                                    + formatShortRange(cur.getShiftStart(), cur.getShiftEnd()) + " and "
                                    + formatShortRange(next.getShiftStart(), next.getShiftEnd()) + ").",
                            "Overlapping assignments",
                            "Verify staff assignments do not double-book the same person simultaneously"
                    ));
                    break;
                }
            }
        }

        // 7. Unscheduled Roster Personnel Notice
        int unscheduledCount = 0;
        for (StaffMember m : safeStaff) {
            if (m != null && m.getId() != null && !uniqueAssignedIds.contains(m.getId())) {
                unscheduledCount++;
            }
        }
        if (unscheduledCount > 0) {
            findings.add(finding(
                    "UNSCHEDULED_STAFF",
                    FindingSeverity.INFO,
                    unscheduledCount + " registered staff member(s) have no assigned shift",
                    "Registered personnel without shift assignments do not count toward zone, gate, or timeline coverage.",
                    unscheduledCount + " unscheduled",
                    "Assign shifts to standby or registered personnel"
            ));
        }

        findings.sort(Comparator
                .comparingInt((OperationsDtos.StaffCoverageFindingDto f) -> f.severity().sortRank())
                .thenComparing(OperationsDtos.StaffCoverageFindingDto::code)
                .thenComparing(OperationsDtos.StaffCoverageFindingDto::title));

        int criticalCount = 0;
        int highCount = 0;
        for (OperationsDtos.StaffCoverageFindingDto f : findings) {
            if (f.severity() == FindingSeverity.CRITICAL) criticalCount++;
            else if (f.severity() == FindingSeverity.HIGH) highCount++;
        }

        return new OperationsDtos.StaffCoverageResult(
                safeStaff.size(),
                secCount,
                volCount,
                medCount,
                supCount,
                safeAssignments.size(),
                uniqueAssignedIds.size(),
                requiredRatioStaff,
                criticalCount,
                highCount,
                findings.size(),
                List.copyOf(findings)
        );
    }

    private void detectTimelineGaps(LocalDateTime eventStart,
                                    LocalDateTime eventEnd,
                                    List<StaffAssignment> assignments,
                                    int requiredRatioStaff,
                                    List<OperationsDtos.StaffCoverageFindingDto> findings) {
        TreeSet<LocalDateTime> points = new TreeSet<>();
        points.add(eventStart);
        points.add(eventEnd);

        for (StaffAssignment a : assignments) {
            if (a == null || a.getShiftStart() == null || a.getShiftEnd() == null) continue;
            if (!a.getShiftEnd().isAfter(eventStart) || !a.getShiftStart().isBefore(eventEnd)) {
                continue;
            }
            LocalDateTime cs = a.getShiftStart().isBefore(eventStart) ? eventStart : a.getShiftStart();
            LocalDateTime ce = a.getShiftEnd().isAfter(eventEnd) ? eventEnd : a.getShiftEnd();
            if (ce.isAfter(cs)) {
                points.add(cs);
                points.add(ce);
            }
        }

        List<LocalDateTime> sortedPts = new ArrayList<>(points);
        LocalDateTime zeroStart = null;
        LocalDateTime zeroEnd = null;

        LocalDateTime lowStart = null;
        LocalDateTime lowEnd = null;
        int lowMinSeen = Integer.MAX_VALUE;

        for (int i = 0; i < sortedPts.size() - 1; i++) {
            LocalDateTime segStart = sortedPts.get(i);
            LocalDateTime segEnd = sortedPts.get(i + 1);
            if (!segEnd.isAfter(segStart)) continue;

            Set<Long> activeStaffIds = new HashSet<>();
            for (StaffAssignment a : assignments) {
                if (a == null || a.getStaff() == null || a.getStaff().getId() == null) continue;
                if (!a.getShiftStart().isAfter(segStart) && !a.getShiftEnd().isBefore(segEnd)) {
                    activeStaffIds.add(a.getStaff().getId());
                }
            }
            int activeCount = activeStaffIds.size();

            if (activeCount == 0) {
                if (zeroStart == null) {
                    zeroStart = segStart;
                    zeroEnd = segEnd;
                } else if (zeroEnd.equals(segStart)) {
                    zeroEnd = segEnd;
                } else {
                    emitZeroGap(zeroStart, zeroEnd, findings);
                    zeroStart = segStart;
                    zeroEnd = segEnd;
                }
                if (lowStart != null) {
                    emitUnderStaffedWindow(lowStart, lowEnd, lowMinSeen, requiredRatioStaff, findings);
                    lowStart = null;
                    lowEnd = null;
                    lowMinSeen = Integer.MAX_VALUE;
                }
            } else {
                if (zeroStart != null) {
                    emitZeroGap(zeroStart, zeroEnd, findings);
                    zeroStart = null;
                    zeroEnd = null;
                }
                if (requiredRatioStaff > 0 && activeCount < requiredRatioStaff) {
                    if (lowStart == null) {
                        lowStart = segStart;
                        lowEnd = segEnd;
                        lowMinSeen = activeCount;
                    } else if (lowEnd.equals(segStart)) {
                        lowEnd = segEnd;
                        lowMinSeen = Math.min(lowMinSeen, activeCount);
                    } else {
                        emitUnderStaffedWindow(lowStart, lowEnd, lowMinSeen, requiredRatioStaff, findings);
                        lowStart = segStart;
                        lowEnd = segEnd;
                        lowMinSeen = activeCount;
                    }
                } else if (lowStart != null) {
                    emitUnderStaffedWindow(lowStart, lowEnd, lowMinSeen, requiredRatioStaff, findings);
                    lowStart = null;
                    lowEnd = null;
                    lowMinSeen = Integer.MAX_VALUE;
                }
            }
        }

        if (zeroStart != null) {
            emitZeroGap(zeroStart, zeroEnd, findings);
        }
        if (lowStart != null) {
            emitUnderStaffedWindow(lowStart, lowEnd, lowMinSeen, requiredRatioStaff, findings);
        }
    }

    private void emitZeroGap(LocalDateTime start,
                             LocalDateTime end,
                             List<OperationsDtos.StaffCoverageFindingDto> findings) {
        findings.add(finding(
                "UNCOVERED_EVENT_WINDOW",
                FindingSeverity.CRITICAL,
                "Uncovered event window: " + formatShortRange(start, end),
                "Zero staff members have scheduled shift coverage during this portion of the event schedule.",
                "0 staff on duty",
                "Maintain continuous shift coverage across the full event schedule"
        ));
    }

    private void emitUnderStaffedWindow(LocalDateTime start,
                                        LocalDateTime end,
                                        int minSeen,
                                        int requiredRatioStaff,
                                        List<OperationsDtos.StaffCoverageFindingDto> findings) {
        findings.add(finding(
                "SHIFT_UNDERSTAFFED",
                FindingSeverity.HIGH,
                "Under-staffed shift window: " + formatShortRange(start, end),
                "Concurrent scheduled personnel drops below the configured attendee-to-staff coverage level.",
                minSeen + " concurrent (target " + requiredRatioStaff + ")",
                "Maintain >= " + requiredRatioStaff + " concurrent staff during active schedule"
        ));
    }

    private boolean isGateOrCheckpoint(InfrastructureType type) {
        return type == InfrastructureType.ENTRY_GATE
                || type == InfrastructureType.EXIT_GATE
                || type == InfrastructureType.EMERGENCY_EXIT
                || type == InfrastructureType.SECURITY_CHECKPOINT;
    }

    private boolean isPublicOperationalZone(ZoneType type) {
        return type == ZoneType.AUDIENCE
                || type == ZoneType.FOOD_STALLS
                || type == ZoneType.QUEUE
                || type == ZoneType.SECURITY_SCREENING
                || type == ZoneType.ENTRY
                || type == ZoneType.EXIT;
    }

    private String formatShortRange(LocalDateTime start, LocalDateTime end) {
        if (start.toLocalDate().equals(end.toLocalDate())) {
            return WINDOW_FMT.format(start) + "–" + TIME_FMT.format(end);
        }
        return WINDOW_FMT.format(start) + " – " + WINDOW_FMT.format(end);
    }

    private OperationsDtos.StaffCoverageFindingDto finding(String code,
                                                           FindingSeverity severity,
                                                           String title,
                                                           String detail,
                                                           String measuredValue,
                                                           String targetNote) {
        String badge = switch (severity) {
            case CRITICAL -> "badge-critical";
            case HIGH -> "badge-high";
            case LOW -> "badge-low";
            case INFO -> "badge-info";
        };
        return new OperationsDtos.StaffCoverageFindingDto(
                code, severity, badge, title, detail, measuredValue, targetNote);
    }
}
