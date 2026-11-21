package com.vyuha.operations;

import com.vyuha.auth.AppUser;
import com.vyuha.auth.Role;
import com.vyuha.auth.UserRepository;
import com.vyuha.capacity.CapacityCalculatorService;
import com.vyuha.capacity.CapacityDtos;
import com.vyuha.capacity.CapacityReview;
import com.vyuha.capacity.CapacityReviewRepository;
import com.vyuha.capacity.ZoneDensityAssumption;
import com.vyuha.capacity.ZoneDensityAssumptionRepository;
import com.vyuha.event.Event;
import com.vyuha.event.EventRepository;
import com.vyuha.event.EventService;
import com.vyuha.event.EventStatus;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.FunctionalZoneRepository;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureElementRepository;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionAreaRepository;
import com.vyuha.venue.VenuePlan;
import com.vyuha.venue.VenuePlanRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Coordinates Phase 7 Arrival Waves, Queue Overflow Projection, Staff Registry,
 * Shift Assignments, Under-Staffing Analysis, and Operational Incident Logs.
 */
@Service
public class OperationsService {

    private static final DateTimeFormatter SHIFT_FULL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SHIFT_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final EventService eventService;
    private final EventRepository eventRepository;
    private final VenuePlanRepository venuePlanRepository;
    private final ExclusionAreaRepository exclusionAreaRepository;
    private final FunctionalZoneRepository functionalZoneRepository;
    private final InfrastructureElementRepository infrastructureElementRepository;
    private final CapacityReviewRepository capacityReviewRepository;
    private final ZoneDensityAssumptionRepository assumptionRepository;
    private final CapacityCalculatorService capacityCalculatorService;
    private final UserRepository userRepository;
    private final ArrivalWaveIntervalRepository waveRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final StaffAssignmentRepository assignmentRepository;
    private final StaffingTargetRepository targetRepository;
    private final IncidentLogRepository incidentRepository;
    private final QueueProjectionService queueProjectionService;
    private final StaffCoverageService staffCoverageService;

    public OperationsService(EventService eventService,
                             EventRepository eventRepository,
                             VenuePlanRepository venuePlanRepository,
                             ExclusionAreaRepository exclusionAreaRepository,
                             FunctionalZoneRepository functionalZoneRepository,
                             InfrastructureElementRepository infrastructureElementRepository,
                             CapacityReviewRepository capacityReviewRepository,
                             ZoneDensityAssumptionRepository assumptionRepository,
                             CapacityCalculatorService capacityCalculatorService,
                             UserRepository userRepository,
                             ArrivalWaveIntervalRepository waveRepository,
                             StaffMemberRepository staffMemberRepository,
                             StaffAssignmentRepository assignmentRepository,
                             StaffingTargetRepository targetRepository,
                             IncidentLogRepository incidentRepository,
                             QueueProjectionService queueProjectionService,
                             StaffCoverageService staffCoverageService) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.venuePlanRepository = venuePlanRepository;
        this.exclusionAreaRepository = exclusionAreaRepository;
        this.functionalZoneRepository = functionalZoneRepository;
        this.infrastructureElementRepository = infrastructureElementRepository;
        this.capacityReviewRepository = capacityReviewRepository;
        this.assumptionRepository = assumptionRepository;
        this.capacityCalculatorService = capacityCalculatorService;
        this.userRepository = userRepository;
        this.waveRepository = waveRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.assignmentRepository = assignmentRepository;
        this.targetRepository = targetRepository;
        this.incidentRepository = incidentRepository;
        this.queueProjectionService = queueProjectionService;
        this.staffCoverageService = staffCoverageService;
    }

    @Transactional(readOnly = true)
    public OperationsDtos.OperationsWorkspaceDto getWorkspace(Long eventId, boolean canEdit, boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional
    public OperationsDtos.OperationsWorkspaceDto createWave(Long eventId,
                                                            OperationsDtos.ArrivalWaveRequest request,
                                                            boolean canEdit,
                                                            boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);
        validateWaveRequest(request);
        InfrastructureElement targetGate = resolveEntryGate(plan, request.targetGateId());

        ArrivalWaveInterval wave = new ArrivalWaveInterval();
        wave.setEvent(event);
        wave.setTargetGate(targetGate);
        wave.setLabel(trimToNull(request.label()));
        wave.setStartMinute(request.startMinute());
        wave.setEndMinute(request.endMinute());
        wave.setExpectedArrivals(request.expectedArrivals());
        wave.setNotes(trimToNull(request.notes()));
        waveRepository.saveAndFlush(wave);

        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional
    public OperationsDtos.OperationsWorkspaceDto updateWave(Long eventId,
                                                            Long waveId,
                                                            OperationsDtos.ArrivalWaveRequest request,
                                                            boolean canEdit,
                                                            boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);
        validateWaveRequest(request);

        ArrivalWaveInterval wave = waveRepository.findByIdAndEventId(waveId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Arrival wave interval not found."));
        InfrastructureElement targetGate = resolveEntryGate(plan, request.targetGateId());

        wave.setTargetGate(targetGate);
        wave.setLabel(trimToNull(request.label()));
        wave.setStartMinute(request.startMinute());
        wave.setEndMinute(request.endMinute());
        wave.setExpectedArrivals(request.expectedArrivals());
        wave.setNotes(trimToNull(request.notes()));
        waveRepository.saveAndFlush(wave);

        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional
    public OperationsDtos.OperationsWorkspaceDto deleteWave(Long eventId,
                                                            Long waveId,
                                                            boolean canEdit,
                                                            boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        ArrivalWaveInterval wave = waveRepository.findByIdAndEventId(waveId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Arrival wave interval not found."));
        waveRepository.delete(wave);
        waveRepository.flush();
        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional
    public OperationsDtos.OperationsWorkspaceDto generateWaveBuckets(Long eventId,
                                                                     OperationsDtos.GenerateWavesRequest request,
                                                                     boolean canEdit,
                                                                     boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);
        if (request == null || request.bucketMinutes() == null || request.totalDurationMinutes() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bucket duration and total duration are required.");
        }
        int bucketMin = request.bucketMinutes();
        int totalMin = request.totalDurationMinutes();
        if (bucketMin < 5 || totalMin < bucketMin) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Total arrival window must be at least as long as one bucket.");
        }

        InfrastructureElement targetGate = resolveEntryGate(plan, request.targetGateId());
        int totalArrivals = request.totalArrivals() != null
                ? request.totalArrivals()
                : (event.getExpectedAttendance() != null ? Math.max(0, event.getExpectedAttendance()) : 0);

        if (Boolean.TRUE.equals(request.replaceExisting())) {
            waveRepository.deleteByEventId(eventId);
            waveRepository.flush();
        }

        int numBuckets = (int) Math.ceil(totalMin / (double) bucketMin);
        int basePerBucket = numBuckets > 0 ? totalArrivals / numBuckets : 0;
        int remainder = numBuckets > 0 ? totalArrivals % numBuckets : 0;

        List<ArrivalWaveInterval> created = new ArrayList<>(numBuckets);
        for (int i = 0; i < numBuckets; i++) {
            int s = i * bucketMin;
            int e = Math.min(totalMin, (i + 1) * bucketMin);
            int arrivals = basePerBucket + (i < remainder ? 1 : 0);
            ArrivalWaveInterval wave = new ArrivalWaveInterval();
            wave.setEvent(event);
            wave.setTargetGate(targetGate);
            wave.setLabel("Wave " + (i + 1));
            wave.setStartMinute(s);
            wave.setEndMinute(e);
            wave.setExpectedArrivals(arrivals);
            created.add(wave);
        }
        waveRepository.saveAllAndFlush(created);

        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional
    public OperationsDtos.OperationsWorkspaceDto createStaffMember(Long eventId,
                                                                   OperationsDtos.StaffMemberRequest request,
                                                                   boolean canEdit,
                                                                   boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        AppUser linkedUser = resolveLinkedUser(request.userId());

        StaffMember member = new StaffMember();
        member.setEvent(event);
        member.setName(request.name().trim());
        member.setRoleType(request.roleType());
        member.setContactPhone(trimToNull(request.contactPhone()));
        member.setCertifications(trimToNull(request.certifications()));
        member.setNotes(trimToNull(request.notes()));
        member.setUser(linkedUser);
        staffMemberRepository.saveAndFlush(member);

        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional
    public OperationsDtos.OperationsWorkspaceDto updateStaffMember(Long eventId,
                                                                   Long staffId,
                                                                   OperationsDtos.StaffMemberRequest request,
                                                                   boolean canEdit,
                                                                   boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        StaffMember member = staffMemberRepository.findByIdAndEventId(staffId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found."));
        AppUser linkedUser = resolveLinkedUser(request.userId());

        member.setName(request.name().trim());
        member.setRoleType(request.roleType());
        member.setContactPhone(trimToNull(request.contactPhone()));
        member.setCertifications(trimToNull(request.certifications()));
        member.setNotes(trimToNull(request.notes()));
        member.setUser(linkedUser);
        staffMemberRepository.saveAndFlush(member);

        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional
    public OperationsDtos.OperationsWorkspaceDto deleteStaffMember(Long eventId,
                                                                   Long staffId,
                                                                   boolean canEdit,
                                                                   boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        StaffMember member = staffMemberRepository.findByIdAndEventId(staffId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found."));
        staffMemberRepository.delete(member);
        staffMemberRepository.flush();
        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional
    public OperationsDtos.OperationsWorkspaceDto createAssignment(Long eventId,
                                                                  OperationsDtos.StaffAssignmentRequest request,
                                                                  boolean canEdit,
                                                                  boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        if (request.shiftStart() == null || request.shiftEnd() == null
                || !request.shiftEnd().isAfter(request.shiftStart())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shift end time must be after shift start time.");
        }

        StaffMember staff = staffMemberRepository.findByIdAndEventId(request.staffId(), eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Selected staff member does not belong to this event."));

        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);
        FunctionalZone zone = resolveZone(plan, request.zoneId());
        InfrastructureElement infra = resolveInfra(plan, request.infraId());

        StaffAssignment assignment = new StaffAssignment();
        assignment.setEvent(event);
        assignment.setStaff(staff);
        assignment.setZone(zone);
        assignment.setInfra(infra);
        assignment.setPostLabel(trimToNull(request.postLabel()));
        assignment.setShiftStart(request.shiftStart());
        assignment.setShiftEnd(request.shiftEnd());
        assignment.setNotes(trimToNull(request.notes()));
        assignmentRepository.saveAndFlush(assignment);

        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional
    public OperationsDtos.OperationsWorkspaceDto deleteAssignment(Long eventId,
                                                                  Long assignmentId,
                                                                  boolean canEdit,
                                                                  boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        StaffAssignment assignment = assignmentRepository.findByIdAndEventId(assignmentId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shift assignment not found."));
        assignmentRepository.delete(assignment);
        assignmentRepository.flush();
        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional
    public OperationsDtos.OperationsWorkspaceDto updateStaffingTargets(Long eventId,
                                                                       OperationsDtos.StaffingTargetsRequest request,
                                                                       boolean canEdit,
                                                                       boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        StaffingTarget target = targetRepository.findByEventId(eventId).orElseGet(() -> {
            StaffingTarget created = new StaffingTarget();
            created.setEvent(event);
            return created;
        });

        target.setMinSecurityPerActiveGate(request.minSecurityPerActiveGate());
        target.setMinStaffPerPublicZone(request.minStaffPerPublicZone());
        target.setMinMedicalStaffTotal(request.minMedicalStaffTotal());
        target.setMinSupervisorsTotal(request.minSupervisorsTotal());
        target.setAttendeesPerStaffRatio(request.attendeesPerStaffRatio());
        target.setShiftWindowMinutes(request.shiftWindowMinutes());
        target.setNotes(trimToNull(request.notes()));
        targetRepository.saveAndFlush(target);

        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional
    public OperationsDtos.OperationsWorkspaceDto createIncident(Long eventId,
                                                                Long reporterUserId,
                                                                OperationsDtos.CreateIncidentRequest request,
                                                                boolean canEdit,
                                                                boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        saveIncidentInternal(event, reporterUserId, request);
        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional
    public OperationsDtos.OperationsWorkspaceDto updateIncident(Long eventId,
                                                                Long incidentId,
                                                                OperationsDtos.UpdateIncidentRequest request,
                                                                boolean canEdit,
                                                                boolean canVerify) {
        Event event = eventService.getEventOrThrow(eventId);
        updateIncidentInternal(event, incidentId, request);
        return buildWorkspaceDto(event, canEdit, canVerify);
    }

    @Transactional(readOnly = true)
    public OperationsDtos.FieldOperationsViewDto getFieldOperationsView(Long requestedEventId,
                                                                        Long currentUserId,
                                                                        String currentUserName,
                                                                        String currentUserRole,
                                                                        boolean isFieldStaffOnly) {
        List<Event> allEvents = eventRepository.searchEvents(null, null, null, null, null).stream()
                .filter(e -> e.getStatus() != EventStatus.ARCHIVED)
                .toList();

        List<StaffAssignment> myAllShifts = currentUserId != null
                ? assignmentRepository.findByStaffUserIdWithDetails(currentUserId)
                : List.of();

        // Order events with assigned shifts first
        Map<Long, Event> eventById = new LinkedHashMap<>();
        for (StaffAssignment sa : myAllShifts) {
            if (sa.getEvent() != null && sa.getEvent().getStatus() != EventStatus.ARCHIVED) {
                eventById.putIfAbsent(sa.getEvent().getId(), sa.getEvent());
            }
        }
        for (Event e : allEvents) {
            eventById.putIfAbsent(e.getId(), e);
        }

        List<OperationsDtos.OptionItemDto> eventOptions = eventById.values().stream()
                .map(e -> new OperationsDtos.OptionItemDto(e.getId(), e.getName()))
                .toList();

        Event selectedEvent = null;
        if (requestedEventId != null) {
            selectedEvent = eventById.get(requestedEventId);
            if (selectedEvent == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found.");
            }
        } else if (!eventById.isEmpty()) {
            selectedEvent = eventById.values().iterator().next();
        }

        if (selectedEvent == null) {
            return new OperationsDtos.FieldOperationsViewDto(
                    null, null, currentUserName, currentUserRole,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        Long selId = selectedEvent.getId();
        List<OperationsDtos.StaffAssignmentDto> myShiftsForEvent = myAllShifts.stream()
                .filter(sa -> sa.getEvent() != null && selId.equals(sa.getEvent().getId()))
                .map(this::toAssignmentDto)
                .toList();

        List<OperationsDtos.StaffAssignmentDto> eventShifts = isFieldStaffOnly
                ? List.of()
                : assignmentRepository.findByEventIdWithDetails(selId).stream()
                        .map(this::toAssignmentDto)
                        .toList();

        VenuePlan plan = venuePlanRepository.findByEventId(selId).orElse(null);
        List<FunctionalZone> zones = plan != null
                ? functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<OperationsDtos.OptionItemDto> zoneOptions = zones.stream()
                .map(z -> new OperationsDtos.OptionItemDto(z.getId(), z.getZoneCode() + " · " + z.getName()))
                .toList();

        List<OperationsDtos.OptionItemDto> staffOptions = isFieldStaffOnly
                ? List.of()
                : staffMemberRepository.findByEventIdWithUser(selId).stream()
                        .map(s -> new OperationsDtos.OptionItemDto(
                                s.getId(), s.getName() + " (" + s.getRoleType().getDisplayName() + ")"))
                        .toList();

        List<OperationsDtos.IncidentLogDto> incidents = incidentRepository.findByEventIdWithDetails(selId).stream()
                .map(this::toIncidentDto)
                .toList();

        return new OperationsDtos.FieldOperationsViewDto(
                selId,
                selectedEvent.getName(),
                currentUserName,
                currentUserRole,
                eventOptions,
                myShiftsForEvent,
                eventShifts,
                zoneOptions,
                staffOptions,
                incidents
        );
    }

    @Transactional
    public OperationsDtos.FieldOperationsViewDto createFieldIncident(Long eventId,
                                                                     Long reporterUserId,
                                                                     String currentUserName,
                                                                     String currentUserRole,
                                                                     boolean isFieldStaffOnly,
                                                                     OperationsDtos.CreateIncidentRequest request) {
        Event event = eventService.getEventOrThrow(eventId);
        saveIncidentInternal(event, reporterUserId, request);
        return getFieldOperationsView(eventId, reporterUserId, currentUserName, currentUserRole, isFieldStaffOnly);
    }

    @Transactional
    public OperationsDtos.FieldOperationsViewDto updateFieldIncident(Long eventId,
                                                                     Long incidentId,
                                                                     Long actorUserId,
                                                                     String currentUserName,
                                                                     String currentUserRole,
                                                                     boolean isFieldStaffOnly,
                                                                     OperationsDtos.UpdateIncidentRequest request) {
        Event event = eventService.getEventOrThrow(eventId);
        updateIncidentInternal(event, incidentId, request);
        return getFieldOperationsView(eventId, actorUserId, currentUserName, currentUserRole, isFieldStaffOnly);
    }

    private void saveIncidentInternal(Event event, Long reporterUserId, OperationsDtos.CreateIncidentRequest request) {
        VenuePlan plan = venuePlanRepository.findByEventId(event.getId()).orElse(null);
        FunctionalZone zone = resolveZone(plan, request.zoneId());
        StaffMember responder = resolveStaffMember(event.getId(), request.assignedStaffId());
        AppUser reporter = reporterUserId != null ? userRepository.findById(reporterUserId).orElse(null) : null;

        IncidentLog log = new IncidentLog();
        log.setEvent(event);
        log.setZone(zone);
        log.setAssignedStaff(responder);
        log.setReportedBy(reporter);
        log.setReportedByName(reporter != null ? reporter.getFullName() : "System User");
        log.setReportedAt(Instant.now());
        log.setSeverity(request.severity());
        log.setDescription(request.description().trim());
        log.setResponseTaken(trimToNull(request.responseTaken()));
        log.setStatus(IncidentStatus.OPEN);
        incidentRepository.saveAndFlush(log);
    }

    private void updateIncidentInternal(Event event, Long incidentId, OperationsDtos.UpdateIncidentRequest request) {
        IncidentLog log = incidentRepository.findByIdAndEventId(incidentId, event.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident log entry not found."));

        log.setStatus(request.status());
        if (request.severity() != null) {
            log.setSeverity(request.severity());
        }
        if (request.assignedStaffId() != null) {
            log.setAssignedStaff(resolveStaffMember(event.getId(), request.assignedStaffId()));
        } else {
            log.setAssignedStaff(null);
        }
        if (request.responseTaken() != null) {
            log.setResponseTaken(trimToNull(request.responseTaken()));
        }
        if (request.status() == IncidentStatus.RESOLVED) {
            if (log.getResolvedAt() == null) {
                log.setResolvedAt(Instant.now());
            }
        } else {
            log.setResolvedAt(null);
        }
        incidentRepository.saveAndFlush(log);
    }

    private OperationsDtos.OperationsWorkspaceDto buildWorkspaceDto(Event event, boolean canEdit, boolean canVerify) {
        Long eventId = event.getId();
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);
        List<ExclusionArea> exclusions = plan != null
                ? exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<FunctionalZone> zones = plan != null
                ? functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<InfrastructureElement> elements = plan != null
                ? infrastructureElementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();

        CapacityReview review = capacityReviewRepository.findByEventId(eventId).orElse(null);
        List<ZoneDensityAssumption> assumptions = review != null && review.getId() != null
                ? assumptionRepository.findByCapacityReviewIdOrderByIdAsc(review.getId())
                : List.of();
        CapacityDtos.CapacityCalculationResult capacityResult = capacityCalculatorService.calculate(
                event.getExpectedAttendance(),
                plan != null ? plan.getBoundaryWkt() : null,
                exclusions,
                zones,
                review,
                assumptions
        );

        List<ArrivalWaveInterval> waves = waveRepository.findByEventIdWithGate(eventId);
        OperationsDtos.QueueProjectionResult queueProjection = queueProjectionService.project(
                event.getExpectedAttendance(),
                event.getStartDateTime(),
                waves,
                elements,
                review,
                capacityResult
        );

        List<StaffMember> staffMembers = staffMemberRepository.findByEventIdWithUser(eventId);
        List<StaffAssignment> assignments = assignmentRepository.findByEventIdWithDetails(eventId);
        StaffingTarget targets = targetRepository.findByEventId(eventId).orElse(null);

        OperationsDtos.StaffCoverageResult coverageResult = staffCoverageService.analyze(
                event.getExpectedAttendance(),
                event.getStartDateTime(),
                event.getEndDateTime(),
                zones,
                elements,
                staffMembers,
                assignments,
                targets
        );

        Map<Long, Integer> assignmentCountByStaff = new HashMap<>();
        for (StaffAssignment a : assignments) {
            if (a.getStaff() != null && a.getStaff().getId() != null) {
                assignmentCountByStaff.merge(a.getStaff().getId(), 1, Integer::sum);
            }
        }

        List<OperationsDtos.StaffMemberDto> staffDtos = staffMembers.stream()
                .map(m -> toStaffMemberDto(m, assignmentCountByStaff.getOrDefault(m.getId(), 0)))
                .toList();

        List<OperationsDtos.StaffAssignmentDto> assignmentDtos = assignments.stream()
                .map(this::toAssignmentDto)
                .toList();

        OperationsDtos.StaffingTargetsDto targetsDto = new OperationsDtos.StaffingTargetsDto(
                targets != null ? targets.getMinSecurityPerActiveGate() : 2,
                targets != null ? targets.getMinStaffPerPublicZone() : 2,
                targets != null ? targets.getMinMedicalStaffTotal() : 2,
                targets != null ? targets.getMinSupervisorsTotal() : 1,
                targets != null ? targets.getAttendeesPerStaffRatio() : 150,
                targets != null ? targets.getShiftWindowMinutes() : 60,
                targets != null ? targets.getNotes() : null
        );

        List<OperationsDtos.IncidentLogDto> incidentDtos = incidentRepository.findByEventIdWithDetails(eventId).stream()
                .map(this::toIncidentDto)
                .toList();

        List<OperationsDtos.OptionItemDto> entryGateOptions = elements.stream()
                .filter(e -> e.getElementType() == InfrastructureType.ENTRY_GATE)
                .map(e -> new OperationsDtos.OptionItemDto(
                        e.getId(),
                        e.getLabel() + (e.isActive() ? "" : " (Inactive)")))
                .toList();

        List<OperationsDtos.OptionItemDto> zoneOptions = zones.stream()
                .map(z -> new OperationsDtos.OptionItemDto(z.getId(), z.getZoneCode() + " · " + z.getName()))
                .toList();

        List<OperationsDtos.OptionItemDto> infraOptions = elements.stream()
                .map(e -> new OperationsDtos.OptionItemDto(
                        e.getId(),
                        e.getLabel() + " (" + e.getElementType().getDisplayName() + ")"))
                .toList();

        List<OperationsDtos.OptionItemDto> fieldUserOptions = userRepository
                .findByRoleAndEnabledTrueOrderByFullNameAsc(Role.FIELD_STAFF).stream()
                .map(u -> new OperationsDtos.OptionItemDto(u.getId(), u.getFullName() + " (" + u.getEmail() + ")"))
                .toList();

        return new OperationsDtos.OperationsWorkspaceDto(
                event.getId(),
                event.getName(),
                event.getExpectedAttendance(),
                event.getStartDateTime(),
                event.getEndDateTime(),
                canEdit,
                canVerify,
                queueProjection,
                staffDtos,
                assignmentDtos,
                targetsDto,
                coverageResult,
                incidentDtos,
                entryGateOptions,
                zoneOptions,
                infraOptions,
                fieldUserOptions
        );
    }

    private OperationsDtos.StaffMemberDto toStaffMemberDto(StaffMember m, int assignmentCount) {
        AppUser u = m.getUser();
        return new OperationsDtos.StaffMemberDto(
                m.getId(),
                m.getName(),
                m.getRoleType(),
                m.getRoleType().getDisplayName(),
                m.getRoleType().getBadgeClass(),
                m.getContactPhone(),
                m.getCertifications(),
                m.getNotes(),
                u != null ? u.getId() : null,
                u != null ? u.getEmail() : null,
                u != null ? u.getFullName() : null,
                assignmentCount
        );
    }

    private OperationsDtos.StaffAssignmentDto toAssignmentDto(StaffAssignment a) {
        StaffMember s = a.getStaff();
        FunctionalZone z = a.getZone();
        InfrastructureElement el = a.getInfra();
        List<String> locParts = new ArrayList<>();
        if (z != null) {
            locParts.add(z.getZoneCode() + " · " + z.getName());
        }
        if (el != null) {
            locParts.add(el.getLabel());
        }
        if (a.getPostLabel() != null && !a.getPostLabel().isBlank()) {
            locParts.add(a.getPostLabel());
        }
        String locationSummary = locParts.isEmpty() ? "General Roving Coverage" : String.join(" / ", locParts);
        long minutes = Duration.between(a.getShiftStart(), a.getShiftEnd()).toMinutes();
        String windowLabel = a.getShiftStart().toLocalDate().equals(a.getShiftEnd().toLocalDate())
                ? SHIFT_FULL_FMT.format(a.getShiftStart()) + "–" + SHIFT_TIME_FMT.format(a.getShiftEnd())
                : SHIFT_FULL_FMT.format(a.getShiftStart()) + " – " + SHIFT_FULL_FMT.format(a.getShiftEnd());

        return new OperationsDtos.StaffAssignmentDto(
                a.getId(),
                s.getId(),
                s.getName(),
                s.getRoleType(),
                s.getRoleType().getDisplayName(),
                s.getRoleType().getBadgeClass(),
                z != null ? z.getId() : null,
                z != null ? z.getZoneCode() : null,
                z != null ? z.getName() : null,
                el != null ? el.getId() : null,
                el != null ? el.getLabel() : null,
                a.getPostLabel(),
                locationSummary,
                a.getShiftStart(),
                a.getShiftEnd(),
                windowLabel,
                minutes,
                a.getNotes()
        );
    }

    private OperationsDtos.IncidentLogDto toIncidentDto(IncidentLog i) {
        FunctionalZone z = i.getZone();
        StaffMember st = i.getAssignedStaff();
        String sevBadge = switch (i.getSeverity()) {
            case CRITICAL -> "badge-critical";
            case HIGH -> "badge-high";
            case LOW -> "badge-low";
            case INFO -> "badge-info";
        };
        return new OperationsDtos.IncidentLogDto(
                i.getId(),
                z != null ? z.getId() : null,
                z != null ? z.getZoneCode() : null,
                z != null ? z.getName() : null,
                st != null ? st.getId() : null,
                st != null ? st.getName() : null,
                st != null ? st.getRoleType() : null,
                i.getReportedByName() != null ? i.getReportedByName()
                        : (i.getReportedBy() != null ? i.getReportedBy().getFullName() : "Unknown"),
                i.getReportedAt(),
                i.getSeverity(),
                sevBadge,
                i.getDescription(),
                i.getResponseTaken(),
                i.getStatus(),
                i.getStatus().getDisplayName(),
                i.getStatus().getBadgeClass(),
                i.getResolvedAt()
        );
    }

    private void validateWaveRequest(OperationsDtos.ArrivalWaveRequest request) {
        if (request == null || request.startMinute() == null || request.endMinute() == null
                || request.expectedArrivals() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Start minute, end minute, and arrivals are required.");
        }
        if (request.startMinute() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Start minute cannot be negative.");
        }
        if (request.endMinute() <= request.startMinute()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End minute must be greater than start minute.");
        }
        if (request.expectedArrivals() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected arrivals cannot be negative.");
        }
    }

    private InfrastructureElement resolveEntryGate(VenuePlan plan, Long targetGateId) {
        if (targetGateId == null) {
            return null;
        }
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target entry gate not found in event venue.");
        }
        InfrastructureElement el = infrastructureElementRepository.findByIdAndVenuePlanId(targetGateId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Target entry gate not found in this venue plan."));
        if (el.getElementType() != InfrastructureType.ENTRY_GATE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Arrival waves can only target ENTRY_GATE infrastructure elements.");
        }
        return el;
    }

    private FunctionalZone resolveZone(VenuePlan plan, Long zoneId) {
        if (zoneId == null) {
            return null;
        }
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected functional zone not found.");
        }
        return functionalZoneRepository.findByIdAndVenuePlanId(zoneId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Selected functional zone does not belong to this event."));
    }

    private InfrastructureElement resolveInfra(VenuePlan plan, Long infraId) {
        if (infraId == null) {
            return null;
        }
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected infrastructure element not found.");
        }
        return infrastructureElementRepository.findByIdAndVenuePlanId(infraId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Selected infrastructure element does not belong to this event."));
    }

    private StaffMember resolveStaffMember(Long eventId, Long staffId) {
        if (staffId == null) {
            return null;
        }
        return staffMemberRepository.findByIdAndEventId(staffId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Selected responder does not belong to this event."));
    }

    private AppUser resolveLinkedUser(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Linked user account not found."));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
