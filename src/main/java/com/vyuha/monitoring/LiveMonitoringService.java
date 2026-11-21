package com.vyuha.monitoring;

import com.vyuha.auth.AppUser;
import com.vyuha.auth.UserRepository;
import com.vyuha.capacity.CapacityCalculatorService;
import com.vyuha.capacity.CapacityDtos;
import com.vyuha.capacity.CapacityReview;
import com.vyuha.capacity.CapacityReviewRepository;
import com.vyuha.capacity.ZoneDensityAssumption;
import com.vyuha.capacity.ZoneDensityAssumptionRepository;
import com.vyuha.common.GeometryUtil;
import com.vyuha.event.ChecklistItemStatus;
import com.vyuha.event.Event;
import com.vyuha.event.EventChecklistItem;
import com.vyuha.event.EventChecklistItemRepository;
import com.vyuha.event.EventService;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.FunctionalZoneRepository;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.ConflictFinding;
import com.vyuha.infrastructure.ConflictFindingRepository;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureElementRepository;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.BottleneckFinding;
import com.vyuha.network.BottleneckFindingRepository;
import com.vyuha.operations.IncidentLog;
import com.vyuha.operations.IncidentLogRepository;
import com.vyuha.operations.IncidentStatus;
import com.vyuha.operations.StaffAssignment;
import com.vyuha.operations.StaffAssignmentRepository;
import com.vyuha.operations.StaffMember;
import com.vyuha.operations.StaffMemberRepository;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.simulation.SimulationRun;
import com.vyuha.simulation.SimulationRunRepository;
import com.vyuha.simulation.SimulationStorageService;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionAreaRepository;
import com.vyuha.venue.VenuePlan;
import com.vyuha.venue.VenuePlanRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Coordinates Phase 10 Live Event Monitoring Control Centre, strictly segregated
 * MANUAL_STAFF / PASS_SCAN / PRACTICE_SIMULATION channels, ZXing pass batches,
 * duplicate scan prevention, threshold/staleness alerts, and SSE broadcasts.
 */
@Service
public class LiveMonitoringService {

    public static final String MODE_LIVE = "LIVE";
    public static final String MODE_PRACTICE = "PRACTICE";
    private static final int RECENT_PASS_LIMIT = 40;
    private static final int RECENT_READING_LIMIT = 25;
    private static final double GATE_ZONE_SNAP_METERS = 5.0;

    private final EventService eventService;
    private final UserRepository userRepository;
    private final VenuePlanRepository venuePlanRepository;
    private final ExclusionAreaRepository exclusionAreaRepository;
    private final FunctionalZoneRepository functionalZoneRepository;
    private final InfrastructureElementRepository infrastructureElementRepository;
    private final CapacityReviewRepository capacityReviewRepository;
    private final ZoneDensityAssumptionRepository assumptionRepository;
    private final CapacityCalculatorService capacityCalculatorService;
    private final EventChecklistItemRepository checklistRepository;
    private final ConflictFindingRepository conflictFindingRepository;
    private final BottleneckFindingRepository bottleneckFindingRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final IncidentLogRepository incidentLogRepository;
    private final SimulationRunRepository simulationRunRepository;
    private final SimulationStorageService simulationStorageService;
    private final AttendeePassRepository passRepository;
    private final OccupancyReadingRepository readingRepository;
    private final MonitoringAlertRuleRepository alertRuleRepository;
    private final MonitoringAlertEventRepository alertEventRepository;
    private final PassQrCodeService passQrCodeService;
    private final MonitoringEvaluatorService evaluatorService;
    private final PracticeSimulationBridgeService practiceBridgeService;
    private final GeometryUtil geometryUtil;
    private final SseHub sseHub;

    public LiveMonitoringService(EventService eventService,
                                 UserRepository userRepository,
                                 VenuePlanRepository venuePlanRepository,
                                 ExclusionAreaRepository exclusionAreaRepository,
                                 FunctionalZoneRepository functionalZoneRepository,
                                 InfrastructureElementRepository infrastructureElementRepository,
                                 CapacityReviewRepository capacityReviewRepository,
                                 ZoneDensityAssumptionRepository assumptionRepository,
                                 CapacityCalculatorService capacityCalculatorService,
                                 EventChecklistItemRepository checklistRepository,
                                 ConflictFindingRepository conflictFindingRepository,
                                 BottleneckFindingRepository bottleneckFindingRepository,
                                 StaffMemberRepository staffMemberRepository,
                                 StaffAssignmentRepository staffAssignmentRepository,
                                 IncidentLogRepository incidentLogRepository,
                                 SimulationRunRepository simulationRunRepository,
                                 SimulationStorageService simulationStorageService,
                                 AttendeePassRepository passRepository,
                                 OccupancyReadingRepository readingRepository,
                                 MonitoringAlertRuleRepository alertRuleRepository,
                                 MonitoringAlertEventRepository alertEventRepository,
                                 PassQrCodeService passQrCodeService,
                                 MonitoringEvaluatorService evaluatorService,
                                 PracticeSimulationBridgeService practiceBridgeService,
                                 GeometryUtil geometryUtil,
                                 SseHub sseHub) {
        this.eventService = eventService;
        this.userRepository = userRepository;
        this.venuePlanRepository = venuePlanRepository;
        this.exclusionAreaRepository = exclusionAreaRepository;
        this.functionalZoneRepository = functionalZoneRepository;
        this.infrastructureElementRepository = infrastructureElementRepository;
        this.capacityReviewRepository = capacityReviewRepository;
        this.assumptionRepository = assumptionRepository;
        this.capacityCalculatorService = capacityCalculatorService;
        this.checklistRepository = checklistRepository;
        this.conflictFindingRepository = conflictFindingRepository;
        this.bottleneckFindingRepository = bottleneckFindingRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.staffAssignmentRepository = staffAssignmentRepository;
        this.incidentLogRepository = incidentLogRepository;
        this.simulationRunRepository = simulationRunRepository;
        this.simulationStorageService = simulationStorageService;
        this.passRepository = passRepository;
        this.readingRepository = readingRepository;
        this.alertRuleRepository = alertRuleRepository;
        this.alertEventRepository = alertEventRepository;
        this.passQrCodeService = passQrCodeService;
        this.evaluatorService = evaluatorService;
        this.practiceBridgeService = practiceBridgeService;
        this.geometryUtil = geometryUtil;
        this.sseHub = sseHub;
    }

    @Transactional(readOnly = true)
    public MonitoringDtos.ControlCentreDto getControlCentre(Long eventId,
                                                            String requestedMode,
                                                            OccupancyDataSource requestedSource,
                                                            boolean canEdit,
                                                            boolean canAcknowledge) {
        Event event = eventService.getEventOrThrow(eventId);
        return buildControlCentre(event, requestedMode, requestedSource, canEdit, canAcknowledge, Instant.now());
    }

    @Transactional(readOnly = true)
    public MonitoringDtos.FieldMonitoringDto getFieldMonitoringData(Long eventId) {
        Event event = eventService.getEventOrThrow(eventId);
        Instant now = Instant.now();
        EventLayoutContext ctx = loadLayoutContext(event);

        List<OccupancyReading> allReadings = readingRepository.findByEventIdWithDetails(eventId);
        List<MonitoringAlertRule> rules = alertRuleRepository.findByEventIdWithZone(eventId);

        MonitoringEvaluatorService.ChannelEvaluationResult manualEval = evaluatorService.evaluateChannel(
                OccupancyDataSource.MANUAL_STAFF, ctx.zones(), ctx.capacity().zoneRows(), allReadings, rules, now);

        List<AttendeePass> passes = passRepository.findByEventIdWithDetails(eventId);
        List<MonitoringDtos.GateStatusDto> gates = buildGateStatuses(ctx.elements(), passes);
        MonitoringDtos.PassSummaryDto passSummary = buildPassSummary(passes);
        List<MonitoringDtos.AttendeePassDto> recentPasses = passes.stream()
                .limit(20)
                .map(p -> toPassDto(eventId, p))
                .toList();

        List<MonitoringDtos.OccupancyReadingDto> recentManual = allReadings.stream()
                .filter(r -> r.getDataSource() == OccupancyDataSource.MANUAL_STAFF)
                .limit(12)
                .map(this::toReadingDto)
                .toList();

        List<MonitoringDtos.AlertEventDto> liveAlerts = alertEventRepository.findByEventIdWithDetails(eventId).stream()
                .filter(a -> a.getDataSource().isLiveOperational() && a.getResolvedAt() == null)
                .limit(15)
                .map(this::toAlertDto)
                .toList();

        return new MonitoringDtos.FieldMonitoringDto(
                eventId,
                manualEval.zones(),
                gates,
                passSummary,
                recentPasses,
                recentManual,
                liveAlerts
        );
    }

    @Transactional
    public MonitoringDtos.ControlCentreDto recordManualCount(Long eventId,
                                                             Long actorUserId,
                                                             String actorName,
                                                             MonitoringDtos.ManualCountRequest request,
                                                             boolean canEdit,
                                                             boolean canAcknowledge) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venuePlanRepository.findByEventId(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Define functional zones in Venue Plan -> Zones & Grid before recording zone counts."));
        FunctionalZone zone = functionalZoneRepository.findByIdAndVenuePlanId(request.zoneId(), plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Selected zone does not belong to this event."));

        Instant now = Instant.now();
        AppUser actor = resolveUser(actorUserId);
        String recorder = resolveActorName(actor, actorName);

        OccupancyReading reading = new OccupancyReading();
        reading.setEvent(event);
        reading.setZone(zone);
        reading.setCountValue(request.countValue());
        reading.setDataSource(OccupancyDataSource.MANUAL_STAFF);
        reading.setRecordedByUser(actor);
        reading.setRecordedBy(recorder);
        reading.setRecordedAt(now);
        reading.setNote(trimToNull(request.note()));
        readingRepository.saveAndFlush(reading);

        evaluateAndPersistAlertsForSource(event, OccupancyDataSource.MANUAL_STAFF, now);
        publishSseUpdate(eventId, OccupancyDataSource.MANUAL_STAFF, "MANUAL_COUNT_RECORDED");

        return buildControlCentre(event, MODE_LIVE, OccupancyDataSource.MANUAL_STAFF, canEdit, canAcknowledge, now);
    }

    @Transactional
    public MonitoringDtos.ControlCentreDto generatePassBatch(Long eventId,
                                                             MonitoringDtos.GeneratePassBatchRequest request,
                                                             boolean canEdit,
                                                             boolean canAcknowledge) {
        Event event = eventService.getEventOrThrow(eventId);
        int count = request != null && request.count() != null ? request.count() : 10;
        String prefix = request != null ? trimToNull(request.labelPrefix()) : null;

        List<AttendeePass> batch = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String passCode = passQrCodeService.generateUniquePassCode(
                    code -> passRepository.existsByEventIdAndPassCodeIgnoreCase(eventId, code));
            AttendeePass pass = new AttendeePass();
            pass.setEvent(event);
            pass.setPassCode(passCode);
            pass.setHolderLabel(prefix != null ? prefix + " #" + i : "Attendee #" + i);
            pass.setStatus(PassStatus.ISSUED);
            batch.add(pass);
        }
        passRepository.saveAllAndFlush(batch);

        publishSseUpdate(eventId, OccupancyDataSource.PASS_SCAN, "PASS_BATCH_GENERATED");
        return buildControlCentre(event, MODE_LIVE, OccupancyDataSource.PASS_SCAN, canEdit, canAcknowledge, Instant.now());
    }

    @Transactional(noRollbackFor = DuplicatePassScanException.class)
    public MonitoringDtos.ControlCentreDto scanPass(Long eventId,
                                                    Long actorUserId,
                                                    String actorName,
                                                    MonitoringDtos.ScanPassRequest request,
                                                    boolean canEdit,
                                                    boolean canAcknowledge) {
        Event event = eventService.getEventOrThrow(eventId);
        String passCode;
        try {
            passCode = passQrCodeService.normalizePassCode(request.passCode());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }

        AttendeePass pass = passRepository.findByEventIdAndPassCodeIgnoreCase(eventId, passCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Attendee pass " + passCode + " was not found for this event."));

        Instant now = Instant.now();
        AppUser actor = resolveUser(actorUserId);
        String scannerName = resolveActorName(actor, actorName);

        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);
        InfrastructureElement gate = resolveGate(plan, request.gateId());

        if (request.action() == PassScanAction.CHECK_IN) {
            if (pass.getStatus() == PassStatus.CHECKED_IN) {
                recordDuplicatePassAlert(event, pass, gate, scannerName, now);
                throw new DuplicatePassScanException(
                        "Duplicate check-in rejected: pass " + pass.getPassCode() + " is already checked in.");
            }

            FunctionalZone targetZone = resolveScanZone(plan, request.zoneId(), gate);
            pass.setStatus(PassStatus.CHECKED_IN);
            pass.setLastGate(gate);
            pass.setLastZone(targetZone);
            pass.setLastScannedAt(now);
            pass.setLastScannedBy(actor);
            pass.setLastScannedByName(scannerName);
            passRepository.saveAndFlush(pass);

            int zoneCheckedIn = targetZone != null
                    ? passRepository.countByEventIdAndStatusAndLastZoneId(eventId, PassStatus.CHECKED_IN, targetZone.getId())
                    : passRepository.countByEventIdAndStatusAndLastZoneIsNull(eventId, PassStatus.CHECKED_IN);

            OccupancyReading reading = new OccupancyReading();
            reading.setEvent(event);
            reading.setZone(targetZone);
            reading.setGate(gate);
            reading.setCountValue(zoneCheckedIn);
            reading.setDataSource(OccupancyDataSource.PASS_SCAN);
            reading.setRecordedByUser(actor);
            reading.setRecordedBy(scannerName);
            reading.setRecordedAt(now);
            reading.setNote("Pass check-in: " + pass.getPassCode()
                    + (gate != null ? " via " + gate.getLabel() : ""));
            readingRepository.saveAndFlush(reading);
        } else {
            if (pass.getStatus() != PassStatus.CHECKED_IN) {
                throw new DuplicatePassScanException(
                        "Check-out rejected: pass " + pass.getPassCode()
                                + " is not currently checked in (status: " + pass.getStatus().getDisplayName() + ").");
            }

            FunctionalZone trackedZone = pass.getLastZone();
            pass.setStatus(PassStatus.CHECKED_OUT);
            pass.setLastGate(gate != null ? gate : pass.getLastGate());
            pass.setLastScannedAt(now);
            pass.setLastScannedBy(actor);
            pass.setLastScannedByName(scannerName);
            passRepository.saveAndFlush(pass);

            int zoneCheckedIn = trackedZone != null
                    ? passRepository.countByEventIdAndStatusAndLastZoneId(eventId, PassStatus.CHECKED_IN, trackedZone.getId())
                    : passRepository.countByEventIdAndStatusAndLastZoneIsNull(eventId, PassStatus.CHECKED_IN);

            OccupancyReading reading = new OccupancyReading();
            reading.setEvent(event);
            reading.setZone(trackedZone);
            reading.setGate(gate);
            reading.setCountValue(zoneCheckedIn);
            reading.setDataSource(OccupancyDataSource.PASS_SCAN);
            reading.setRecordedByUser(actor);
            reading.setRecordedBy(scannerName);
            reading.setRecordedAt(now);
            reading.setNote("Pass check-out: " + pass.getPassCode()
                    + (gate != null ? " via " + gate.getLabel() : ""));
            readingRepository.saveAndFlush(reading);
        }

        evaluateAndPersistAlertsForSource(event, OccupancyDataSource.PASS_SCAN, now);
        publishSseUpdate(eventId, OccupancyDataSource.PASS_SCAN, "PASS_SCANNED");

        return buildControlCentre(event, MODE_LIVE, OccupancyDataSource.PASS_SCAN, canEdit, canAcknowledge, now);
    }

    @Transactional(readOnly = true)
    public byte[] renderPassQrPng(Long eventId, Long passId, int sizePx) {
        eventService.getEventOrThrow(eventId);
        AttendeePass pass = passRepository.findByIdAndEventId(passId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendee pass not found."));
        return passQrCodeService.generateQrPng(pass.getPassCode(), sizePx);
    }

    public String decodePassQrBytes(byte[] imageBytes) {
        try {
            return passQrCodeService.decodeQrFromImage(imageBytes);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @Transactional
    public MonitoringDtos.ControlCentreDto upsertAlertRule(Long eventId,
                                                         MonitoringDtos.AlertRuleRequest request,
                                                         boolean canEdit,
                                                         boolean canAcknowledge) {
        Event event = eventService.getEventOrThrow(eventId);
        if (request == null || request.warningThresholdPct() == null
                || request.criticalThresholdPct() == null || request.staleAfterMinutes() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Warning threshold, critical threshold, and staleness window are required.");
        }
        if (request.criticalThresholdPct() <= request.warningThresholdPct()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Critical threshold (%) must be strictly greater than warning threshold (%).");
        }

        FunctionalZone zone = null;
        if (request.zoneId() != null) {
            VenuePlan plan = venuePlanRepository.findByEventId(eventId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Venue plan not found for zone rule."));
            zone = functionalZoneRepository.findByIdAndVenuePlanId(request.zoneId(), plan.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Selected zone does not belong to this event."));
        }

        MonitoringAlertRule rule = (zone == null
                ? alertRuleRepository.findByEventIdAndZoneIsNull(eventId)
                : alertRuleRepository.findByEventIdAndZoneId(eventId, zone.getId()))
                .orElseGet(() -> {
                    MonitoringAlertRule created = new MonitoringAlertRule();
                    created.setEvent(event);
                    return created;
                });

        rule.setZone(zone);
        rule.setWarningThresholdPct(request.warningThresholdPct());
        rule.setCriticalThresholdPct(request.criticalThresholdPct());
        rule.setStaleAfterMinutes(request.staleAfterMinutes());
        alertRuleRepository.saveAndFlush(rule);

        Instant now = Instant.now();
        evaluateAndPersistAlertsForSource(event, OccupancyDataSource.MANUAL_STAFF, now);
        evaluateAndPersistAlertsForSource(event, OccupancyDataSource.PASS_SCAN, now);
        evaluateAndPersistAlertsForSource(event, OccupancyDataSource.PRACTICE_SIMULATION, now);
        publishSseUpdate(eventId, OccupancyDataSource.MANUAL_STAFF, "ALERT_RULES_UPDATED");

        return buildControlCentre(event, MODE_LIVE, OccupancyDataSource.MANUAL_STAFF, canEdit, canAcknowledge, now);
    }

    @Transactional
    public MonitoringDtos.ControlCentreDto deleteAlertRule(Long eventId,
                                                         Long ruleId,
                                                         boolean canEdit,
                                                         boolean canAcknowledge) {
        Event event = eventService.getEventOrThrow(eventId);
        MonitoringAlertRule rule = alertRuleRepository.findByIdAndEventId(ruleId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert rule not found."));
        if (rule.getZone() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Default event alert rule cannot be deleted; update its thresholds instead.");
        }
        alertRuleRepository.delete(rule);
        alertRuleRepository.flush();

        Instant now = Instant.now();
        evaluateAndPersistAlertsForSource(event, OccupancyDataSource.MANUAL_STAFF, now);
        evaluateAndPersistAlertsForSource(event, OccupancyDataSource.PASS_SCAN, now);
        publishSseUpdate(eventId, OccupancyDataSource.MANUAL_STAFF, "ALERT_RULE_REMOVED");

        return buildControlCentre(event, MODE_LIVE, OccupancyDataSource.MANUAL_STAFF, canEdit, canAcknowledge, now);
    }

    @Transactional
    public MonitoringDtos.ControlCentreDto acknowledgeAlert(Long eventId,
                                                            Long alertId,
                                                            Long actorUserId,
                                                            String actorName,
                                                            boolean canEdit,
                                                            boolean canAcknowledge) {
        Event event = eventService.getEventOrThrow(eventId);
        MonitoringAlertEvent alert = alertEventRepository.findByIdAndEventId(alertId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Monitoring alert not found."));
        AppUser actor = resolveUser(actorUserId);
        String ackName = resolveActorName(actor, actorName);

        alert.setAcknowledgedByUser(actor);
        alert.setAcknowledgedBy(ackName);
        alert.setAcknowledgedAt(Instant.now());
        alertEventRepository.saveAndFlush(alert);

        String mode = alert.getDataSource() == OccupancyDataSource.PRACTICE_SIMULATION ? MODE_PRACTICE : MODE_LIVE;
        publishSseUpdate(eventId, alert.getDataSource(), "ALERT_ACKNOWLEDGED");
        return buildControlCentre(event, mode, alert.getDataSource(), canEdit, canAcknowledge, Instant.now());
    }

    @Transactional
    public MonitoringDtos.ControlCentreDto resolveAlert(Long eventId,
                                                        Long alertId,
                                                        Long actorUserId,
                                                        String actorName,
                                                        boolean canEdit,
                                                        boolean canAcknowledge) {
        Event event = eventService.getEventOrThrow(eventId);
        MonitoringAlertEvent alert = alertEventRepository.findByIdAndEventId(alertId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Monitoring alert not found."));
        AppUser actor = resolveUser(actorUserId);
        String actorDisplay = resolveActorName(actor, actorName);

        Instant now = Instant.now();
        if (alert.getAcknowledgedAt() == null) {
            alert.setAcknowledgedByUser(actor);
            alert.setAcknowledgedBy(actorDisplay);
            alert.setAcknowledgedAt(now);
        }
        alert.setResolvedAt(now);
        alertEventRepository.saveAndFlush(alert);

        String mode = alert.getDataSource() == OccupancyDataSource.PRACTICE_SIMULATION ? MODE_PRACTICE : MODE_LIVE;
        publishSseUpdate(eventId, alert.getDataSource(), "ALERT_RESOLVED");
        return buildControlCentre(event, mode, alert.getDataSource(), canEdit, canAcknowledge, now);
    }

    @Transactional
    public MonitoringDtos.ControlCentreDto checkStaleness(Long eventId,
                                                          String requestedMode,
                                                          OccupancyDataSource requestedSource,
                                                          boolean canEdit,
                                                          boolean canAcknowledge) {
        Event event = eventService.getEventOrThrow(eventId);
        Instant now = Instant.now();
        boolean changed = false;
        if (MODE_PRACTICE.equalsIgnoreCase(requestedMode)) {
            changed = evaluateAndPersistAlertsForSource(event, OccupancyDataSource.PRACTICE_SIMULATION, now);
        } else {
            changed |= evaluateAndPersistAlertsForSource(event, OccupancyDataSource.MANUAL_STAFF, now);
            changed |= evaluateAndPersistAlertsForSource(event, OccupancyDataSource.PASS_SCAN, now);
        }
        if (changed) {
            publishSseUpdate(eventId,
                    MODE_PRACTICE.equalsIgnoreCase(requestedMode)
                            ? OccupancyDataSource.PRACTICE_SIMULATION
                            : (requestedSource != null && requestedSource.isLiveOperational()
                            ? requestedSource : OccupancyDataSource.MANUAL_STAFF),
                    "STALENESS_EVALUATED");
        }
        return buildControlCentre(event, requestedMode, requestedSource, canEdit, canAcknowledge, now);
    }

    @Transactional
    public MonitoringDtos.PracticeStepResultDto pushPracticeSimulationFrame(Long eventId,
                                                                            Long actorUserId,
                                                                            String actorName,
                                                                            MonitoringDtos.PracticeStepRequest request,
                                                                            boolean canEdit,
                                                                            boolean canAcknowledge) {
        Event event = eventService.getEventOrThrow(eventId);
        EventLayoutContext ctx = loadLayoutContext(event);
        if (ctx.zones().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Define functional zones in Venue Plan -> Zones & Grid before running practice monitoring.");
        }

        if (request != null && Boolean.TRUE.equals(request.resetFirst())) {
            clearPracticeInternal(eventId);
        }

        AppUser actor = resolveUser(actorUserId);
        String actorDisplay = resolveActorName(actor, actorName);
        Instant now = Instant.now();

        List<SimulationRun> runs = simulationRunRepository.findByEventIdWithSnapshotOrderByCreatedDesc(eventId);
        SimulationRun selectedRun = null;
        if (request != null && request.runId() != null) {
            selectedRun = simulationRunRepository.findByIdAndEventIdWithSnapshot(request.runId(), eventId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation run not found."));
        } else if (!runs.isEmpty()) {
            selectedRun = runs.get(0);
        }

        Map<Long, Integer> zoneCounts;
        int frameIndex = request != null && request.frameIndex() != null ? Math.max(0, request.frameIndex()) : 0;
        int totalFrames;
        double frameTimeSec;
        int mappedCount = 0;
        int unmappedCount = 0;
        Long runId = null;
        String runLabel;

        if (selectedRun != null && selectedRun.getFrameStreamPath() != null) {
            SimulationDtos.SimulationFrameStream stream = simulationStorageService.loadFrameStream(
                    eventId, selectedRun.getFrameStreamPath());
            List<SimulationDtos.SimulationFrame> frames = stream.frames() != null ? stream.frames() : List.of();
            if (frames.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected simulation run contains no frames.");
            }
            totalFrames = frames.size();
            frameIndex = Math.min(frameIndex, totalFrames - 1);
            SimulationDtos.SimulationFrame frame = frames.get(frameIndex);
            frameTimeSec = frame.t();

            PracticeSimulationBridgeService.MappedFrameCounts mapped =
                    practiceBridgeService.mapFrameAgentsToZoneCounts(ctx.zones(), frame);
            zoneCounts = mapped.countsByZoneId();
            mappedCount = mapped.mappedAgentsCount();
            unmappedCount = mapped.unmappedAgentsCount();
            runId = selectedRun.getId();
            runLabel = selectedRun.getLabel() != null ? selectedRun.getLabel() : ("Sim Run #" + selectedRun.getId());
        } else {
            totalFrames = 7;
            frameIndex = frameIndex % totalFrames;
            frameTimeSec = frameIndex * 60.0;
            Map<Long, Integer> caps = new LinkedHashMap<>();
            for (CapacityDtos.ZoneCapacityRow row : ctx.capacity().zoneRows()) {
                if (row.zoneId() != null && row.computedCapacity() != null) {
                    caps.put(row.zoneId(), row.computedCapacity());
                }
            }
            zoneCounts = practiceBridgeService.generatePracticeDryRunCounts(ctx.zones(), caps, frameIndex);
            for (Integer c : zoneCounts.values()) {
                mappedCount += (c != null ? c : 0);
            }
            runLabel = "Practice Dry-Run Drill";
        }

        Map<Long, FunctionalZone> zoneById = new LinkedHashMap<>();
        for (FunctionalZone z : ctx.zones()) {
            if (z.getId() != null) {
                zoneById.put(z.getId(), z);
            }
        }

        List<OccupancyReading> practiceReadings = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : zoneCounts.entrySet()) {
            FunctionalZone zone = zoneById.get(entry.getKey());
            if (zone == null) {
                continue;
            }
            OccupancyReading reading = new OccupancyReading();
            reading.setEvent(event);
            reading.setZone(zone);
            reading.setCountValue(Math.max(0, entry.getValue()));
            reading.setDataSource(OccupancyDataSource.PRACTICE_SIMULATION);
            reading.setRecordedByUser(actor);
            reading.setRecordedBy("Practice: " + runLabel);
            reading.setRecordedAt(now);
            reading.setNote(String.format(Locale.US,
                    "Practice frame %d/%d (t=%.0fs) triggered by %s",
                    frameIndex + 1, totalFrames, frameTimeSec, actorDisplay));
            practiceReadings.add(reading);
        }
        readingRepository.saveAllAndFlush(practiceReadings);

        evaluateAndPersistAlertsForSource(event, OccupancyDataSource.PRACTICE_SIMULATION, now);
        publishSseUpdate(eventId, OccupancyDataSource.PRACTICE_SIMULATION, "PRACTICE_FRAME_PUSHED");

        MonitoringDtos.ControlCentreDto controlCentre = buildControlCentre(
                event, MODE_PRACTICE, OccupancyDataSource.PRACTICE_SIMULATION, canEdit, canAcknowledge, now);

        return new MonitoringDtos.PracticeStepResultDto(
                runId,
                runLabel,
                frameIndex,
                totalFrames,
                frameTimeSec,
                mappedCount,
                unmappedCount,
                controlCentre
        );
    }

    @Transactional
    public MonitoringDtos.ControlCentreDto clearPracticeData(Long eventId,
                                                             boolean canEdit,
                                                             boolean canAcknowledge) {
        Event event = eventService.getEventOrThrow(eventId);
        clearPracticeInternal(eventId);
        publishSseUpdate(eventId, OccupancyDataSource.PRACTICE_SIMULATION, "PRACTICE_CLEARED");
        return buildControlCentre(
                event, MODE_PRACTICE, OccupancyDataSource.PRACTICE_SIMULATION, canEdit, canAcknowledge, Instant.now());
    }

    private void clearPracticeInternal(Long eventId) {
        readingRepository.deleteByEventIdAndDataSource(eventId, OccupancyDataSource.PRACTICE_SIMULATION);
        alertEventRepository.deleteByEventIdAndDataSource(eventId, OccupancyDataSource.PRACTICE_SIMULATION);
        readingRepository.flush();
        alertEventRepository.flush();
    }

    private void recordDuplicatePassAlert(Event event,
                                          AttendeePass pass,
                                          InfrastructureElement gate,
                                          String scannerName,
                                          Instant now) {
        MonitoringAlertEvent alert = new MonitoringAlertEvent();
        alert.setEvent(event);
        alert.setZone(pass.getLastZone());
        alert.setDataSource(OccupancyDataSource.PASS_SCAN);
        alert.setSeverity(FindingSeverity.HIGH);
        alert.setAlertType(MonitoringAlertType.DUPLICATE_PASS_SCAN);
        alert.setMessage("Duplicate entry scan blocked for pass " + pass.getPassCode()
                + (gate != null ? " at " + gate.getLabel() : "")
                + " (operator: " + scannerName + ").");
        alert.setTriggeredAt(now);
        alertEventRepository.saveAndFlush(alert);

        sseHub.publish(event.getId(), "alert", Map.of(
                "alertType", MonitoringAlertType.DUPLICATE_PASS_SCAN.name(),
                "severity", FindingSeverity.HIGH.name(),
                "dataSource", OccupancyDataSource.PASS_SCAN.name(),
                "message", alert.getMessage(),
                "triggeredAt", now.toString()
        ));
    }

    private boolean evaluateAndPersistAlertsForSource(Event event, OccupancyDataSource source, Instant now) {
        Long eventId = event.getId();
        EventLayoutContext ctx = loadLayoutContext(event);
        List<OccupancyReading> readings = readingRepository.findByEventIdAndDataSourceOrderByRecordedAtDescIdDesc(
                eventId, source);
        List<MonitoringAlertRule> rules = alertRuleRepository.findByEventIdWithZone(eventId);
        List<MonitoringAlertEvent> activeAlerts = alertEventRepository
                .findByEventIdAndDataSourceAndResolvedAtIsNullOrderByTriggeredAtDescIdDesc(eventId, source);

        MonitoringEvaluatorService.ChannelEvaluationResult eval = evaluatorService.evaluateChannel(
                source, ctx.zones(), ctx.capacity().zoneRows(), readings, rules, now);

        MonitoringEvaluatorService.AlertMutationPlan plan = evaluatorService.evaluateAlertMutations(
                source, eval.zones(), activeAlerts, now);

        if (plan.toCreate().isEmpty() && plan.alertIdsToResolve().isEmpty()) {
            return false;
        }

        if (!plan.alertIdsToResolve().isEmpty()) {
            Map<Long, MonitoringAlertEvent> activeById = new LinkedHashMap<>();
            for (MonitoringAlertEvent a : activeAlerts) {
                activeById.put(a.getId(), a);
            }
            List<MonitoringAlertEvent> resolved = new ArrayList<>();
            for (Long id : plan.alertIdsToResolve()) {
                MonitoringAlertEvent target = activeById.get(id);
                if (target != null && target.getResolvedAt() == null) {
                    target.setResolvedAt(now);
                    resolved.add(target);
                }
            }
            if (!resolved.isEmpty()) {
                alertEventRepository.saveAll(resolved);
            }
        }

        if (!plan.toCreate().isEmpty()) {
            Map<Long, FunctionalZone> zoneMap = new LinkedHashMap<>();
            for (FunctionalZone z : ctx.zones()) {
                if (z.getId() != null) {
                    zoneMap.put(z.getId(), z);
                }
            }
            List<MonitoringAlertEvent> created = new ArrayList<>(plan.toCreate().size());
            for (MonitoringEvaluatorService.PendingAlert pending : plan.toCreate()) {
                MonitoringAlertEvent entity = new MonitoringAlertEvent();
                entity.setEvent(event);
                entity.setZone(pending.zoneId() != null ? zoneMap.get(pending.zoneId()) : null);
                entity.setDataSource(pending.dataSource());
                entity.setSeverity(pending.severity());
                entity.setAlertType(pending.alertType());
                entity.setMessage(pending.message());
                entity.setTriggeredAt(now);
                created.add(entity);
            }
            alertEventRepository.saveAll(created);
            for (MonitoringAlertEvent a : created) {
                sseHub.publish(eventId, "alert", Map.of(
                        "alertType", a.getAlertType().name(),
                        "severity", a.getSeverity().name(),
                        "dataSource", a.getDataSource().name(),
                        "message", a.getMessage(),
                        "triggeredAt", now.toString()
                ));
            }
        }

        alertEventRepository.flush();
        return true;
    }

    private MonitoringDtos.ControlCentreDto buildControlCentre(Event event,
                                                               String requestedMode,
                                                               OccupancyDataSource requestedSource,
                                                               boolean canEdit,
                                                               boolean canAcknowledge,
                                                               Instant now) {
        Long eventId = event.getId();
        boolean practiceMode = MODE_PRACTICE.equalsIgnoreCase(requestedMode)
                || requestedSource == OccupancyDataSource.PRACTICE_SIMULATION;
        String effectiveMode = practiceMode ? MODE_PRACTICE : MODE_LIVE;
        OccupancyDataSource activeSource = practiceMode
                ? OccupancyDataSource.PRACTICE_SIMULATION
                : (requestedSource == OccupancyDataSource.PASS_SCAN
                ? OccupancyDataSource.PASS_SCAN
                : OccupancyDataSource.MANUAL_STAFF);

        EventLayoutContext ctx = loadLayoutContext(event);
        List<OccupancyReading> allReadings = readingRepository.findByEventIdWithDetails(eventId);
        List<MonitoringAlertRule> rules = alertRuleRepository.findByEventIdWithZone(eventId);
        List<MonitoringAlertEvent> allAlerts = alertEventRepository.findByEventIdWithDetails(eventId);
        List<AttendeePass> allPasses = passRepository.findByEventIdWithDetails(eventId);

        MonitoringEvaluatorService.ChannelEvaluationResult manualEval = evaluatorService.evaluateChannel(
                OccupancyDataSource.MANUAL_STAFF, ctx.zones(), ctx.capacity().zoneRows(), allReadings, rules, now);
        MonitoringEvaluatorService.ChannelEvaluationResult passEval = evaluatorService.evaluateChannel(
                OccupancyDataSource.PASS_SCAN, ctx.zones(), ctx.capacity().zoneRows(), allReadings, rules, now);
        MonitoringEvaluatorService.ChannelEvaluationResult practiceEval = evaluatorService.evaluateChannel(
                OccupancyDataSource.PRACTICE_SIMULATION, ctx.zones(), ctx.capacity().zoneRows(), allReadings, rules, now);

        MonitoringEvaluatorService.ChannelEvaluationResult activeEval = switch (activeSource) {
            case MANUAL_STAFF -> manualEval;
            case PASS_SCAN -> passEval;
            case PRACTICE_SIMULATION -> practiceEval;
        };

        List<MonitoringDtos.GateStatusDto> gates = buildGateStatuses(ctx.elements(), allPasses);
        MonitoringDtos.QueuePressureSummaryDto queuePressure = buildQueuePressure(
                activeEval.zones(), activeSource, ctx.capacity());
        MonitoringDtos.StaffDutySummaryDto staffDuty = buildStaffDuty(eventId);
        MonitoringDtos.PreEventSafetySummaryDto preEventSafety = buildPreEventSafety(
                eventId, ctx.plan(), ctx.capacity());

        // Strict isolation: Practice alerts appear only in Practice mode; Live alerts appear only in Live mode.
        List<MonitoringDtos.AlertEventDto> activeAlerts = allAlerts.stream()
                .filter(a -> a.getResolvedAt() == null)
                .filter(a -> practiceMode
                        ? a.getDataSource() == OccupancyDataSource.PRACTICE_SIMULATION
                        : a.getDataSource().isLiveOperational())
                .map(this::toAlertDto)
                .toList();

        List<MonitoringDtos.AlertEventDto> resolvedAlerts = allAlerts.stream()
                .filter(a -> a.getResolvedAt() != null)
                .filter(a -> practiceMode
                        ? a.getDataSource() == OccupancyDataSource.PRACTICE_SIMULATION
                        : a.getDataSource().isLiveOperational())
                .limit(20)
                .map(this::toAlertDto)
                .toList();

        List<MonitoringDtos.AlertRuleDto> ruleDtos = buildAlertRuleDtos(rules);
        MonitoringDtos.PassSummaryDto passSummary = buildPassSummary(allPasses);
        List<MonitoringDtos.AttendeePassDto> recentPasses = allPasses.stream()
                .limit(RECENT_PASS_LIMIT)
                .map(p -> toPassDto(eventId, p))
                .toList();

        List<MonitoringDtos.OccupancyReadingDto> recentReadings = allReadings.stream()
                .filter(r -> r.getDataSource() == activeSource)
                .limit(RECENT_READING_LIMIT)
                .map(this::toReadingDto)
                .toList();

        List<MonitoringDtos.PracticeRunOptionDto> practiceRuns = buildPracticeRunOptions(eventId);
        MonitoringDtos.HeatmapGeometryDto heatmapGeom = buildHeatmapGeometry(eventId, ctx, gates);

        return new MonitoringDtos.ControlCentreDto(
                eventId,
                event.getName(),
                event.getStatus().getDisplayName(),
                event.getExpectedAttendance(),
                ctx.capacity().preliminaryCapacity(),
                effectiveMode,
                activeSource,
                canEdit,
                canAcknowledge,
                now,
                activeEval.summary(),
                manualEval.summary(),
                passEval.summary(),
                practiceEval.summary(),
                activeEval.zones(),
                gates,
                queuePressure,
                staffDuty,
                preEventSafety,
                activeAlerts,
                resolvedAlerts,
                ruleDtos,
                passSummary,
                recentPasses,
                recentReadings,
                practiceRuns,
                heatmapGeom
        );
    }

    private EventLayoutContext loadLayoutContext(Event event) {
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
        CapacityDtos.CapacityCalculationResult capacity = capacityCalculatorService.calculate(
                event.getExpectedAttendance(),
                plan != null ? plan.getBoundaryWkt() : null,
                exclusions,
                zones,
                review,
                assumptions
        );
        return new EventLayoutContext(plan, exclusions, zones, elements, capacity);
    }

    private List<MonitoringDtos.GateStatusDto> buildGateStatuses(List<InfrastructureElement> elements,
                                                                 List<AttendeePass> passes) {
        Map<Long, Integer> checkedInByGate = new LinkedHashMap<>();
        Map<Long, Integer> checkedOutByGate = new LinkedHashMap<>();
        Map<Long, Instant> lastScanByGate = new LinkedHashMap<>();

        for (AttendeePass p : passes) {
            if (p.getLastGate() == null || p.getLastGate().getId() == null) {
                continue;
            }
            Long gId = p.getLastGate().getId();
            if (p.getStatus() == PassStatus.CHECKED_IN) {
                checkedInByGate.merge(gId, 1, Integer::sum);
            } else if (p.getStatus() == PassStatus.CHECKED_OUT) {
                checkedOutByGate.merge(gId, 1, Integer::sum);
            }
            if (p.getLastScannedAt() != null) {
                Instant prev = lastScanByGate.get(gId);
                if (prev == null || p.getLastScannedAt().isAfter(prev)) {
                    lastScanByGate.put(gId, p.getLastScannedAt());
                }
            }
        }

        List<MonitoringDtos.GateStatusDto> gates = new ArrayList<>();
        for (InfrastructureElement el : elements) {
            InfrastructureType t = el.getElementType();
            if (t != InfrastructureType.ENTRY_GATE
                    && t != InfrastructureType.EXIT_GATE
                    && t != InfrastructureType.EMERGENCY_EXIT
                    && t != InfrastructureType.SECURITY_CHECKPOINT) {
                continue;
            }
            gates.add(new MonitoringDtos.GateStatusDto(
                    el.getId(),
                    el.getLabel(),
                    t,
                    t.getDisplayName(),
                    el.isActive(),
                    el.getThroughputPersonsPerMin(),
                    checkedInByGate.getOrDefault(el.getId(), 0),
                    checkedOutByGate.getOrDefault(el.getId(), 0),
                    lastScanByGate.get(el.getId()),
                    el.getCenterX(),
                    el.getCenterY()
            ));
        }
        return gates;
    }

    private MonitoringDtos.QueuePressureSummaryDto buildQueuePressure(List<MonitoringDtos.ZoneOccupancyDto> activeZones,
                                                                      OccupancyDataSource activeSource,
                                                                      CapacityDtos.CapacityCalculationResult cap) {
        int holdingZones = 0;
        int holdingCurrent = 0;
        Instant lastUpdated = null;
        boolean anyStale = false;
        for (MonitoringDtos.ZoneOccupancyDto z : activeZones) {
            if (z.zoneType().getCapacityBucket() == ZoneType.CapacityBucket.HOLDING) {
                holdingZones++;
                if (z.currentCount() != null) {
                    holdingCurrent += z.currentCount();
                }
                if (z.stale()) {
                    anyStale = true;
                }
                if (z.lastUpdatedAt() != null && (lastUpdated == null || z.lastUpdatedAt().isAfter(lastUpdated))) {
                    lastUpdated = z.lastUpdatedAt();
                }
            }
        }
        int holdingCap = cap.holdingSpaceCapacity() != null ? cap.holdingSpaceCapacity() : 0;
        Double utilPct = holdingCap > 0 ? Math.round((holdingCurrent * 1000.0) / holdingCap) / 10.0 : null;
        return new MonitoringDtos.QueuePressureSummaryDto(
                holdingZones,
                holdingCap,
                holdingCurrent,
                activeSource,
                utilPct,
                lastUpdated,
                anyStale
        );
    }

    private MonitoringDtos.StaffDutySummaryDto buildStaffDuty(Long eventId) {
        List<StaffMember> staff = staffMemberRepository.findByEventIdWithUser(eventId);
        List<StaffAssignment> assignments = staffAssignmentRepository.findByEventIdWithDetails(eventId);
        List<IncidentLog> incidents = incidentLogRepository.findByEventIdWithDetails(eventId);

        LocalDateTime nowLocal = LocalDateTime.now();
        int onDutyNow = (int) assignments.stream()
                .filter(a -> a.getShiftStart() != null && a.getShiftEnd() != null
                        && !nowLocal.isBefore(a.getShiftStart()) && !nowLocal.isAfter(a.getShiftEnd()))
                .map(a -> a.getStaff().getId())
                .distinct()
                .count();
        int openIncidents = (int) incidents.stream()
                .filter(i -> i.getStatus() != IncidentStatus.RESOLVED)
                .count();

        return new MonitoringDtos.StaffDutySummaryDto(
                staff.size(),
                assignments.size(),
                onDutyNow,
                openIncidents
        );
    }

    private MonitoringDtos.PreEventSafetySummaryDto buildPreEventSafety(Long eventId,
                                                                        VenuePlan plan,
                                                                        CapacityDtos.CapacityCalculationResult cap) {
        List<EventChecklistItem> checklist = checklistRepository.findByEventIdWithSigner(eventId);
        int incompleteChecklist = (int) checklist.stream()
                .filter(c -> c.getStatus() != ChecklistItemStatus.COMPLETE
                        && c.getStatus() != ChecklistItemStatus.NOT_APPLICABLE)
                .count();

        int activeConflicts = 0;
        int activeBottlenecks = 0;
        if (plan != null && plan.getId() != null) {
            List<ConflictFinding> conflicts = conflictFindingRepository.findByVenuePlanIdOrderByActiveDescIdAsc(plan.getId());
            activeConflicts = (int) conflicts.stream().filter(ConflictFinding::isActive).count();

            List<BottleneckFinding> bottlenecks = bottleneckFindingRepository.findByVenuePlanIdOrderByActiveDescIdAsc(plan.getId());
            activeBottlenecks = (int) bottlenecks.stream().filter(BottleneckFinding::isActive).count();
        }

        return new MonitoringDtos.PreEventSafetySummaryDto(
                incompleteChecklist,
                checklist.size(),
                activeConflicts,
                activeBottlenecks,
                cap.effectiveReviewStatus().getDisplayName(),
                cap.effectiveReviewStatus().getBadgeClass()
        );
    }

    private List<MonitoringDtos.AlertRuleDto> buildAlertRuleDtos(List<MonitoringAlertRule> rules) {
        List<MonitoringDtos.AlertRuleDto> list = new ArrayList<>();
        boolean hasDefault = false;
        for (MonitoringAlertRule r : rules) {
            boolean isDefault = r.getZone() == null;
            if (isDefault) {
                hasDefault = true;
            }
            list.add(new MonitoringDtos.AlertRuleDto(
                    r.getId(),
                    r.getZone() != null ? r.getZone().getId() : null,
                    r.getZone() != null ? r.getZone().getZoneCode() : "DEFAULT",
                    r.getZone() != null ? r.getZone().getName() : "All Zones (Event Default)",
                    r.getWarningThresholdPct(),
                    r.getCriticalThresholdPct(),
                    r.getStaleAfterMinutes(),
                    isDefault
            ));
        }
        if (!hasDefault) {
            list.add(0, new MonitoringDtos.AlertRuleDto(
                    null,
                    null,
                    "DEFAULT",
                    "All Zones (Event Default)",
                    MonitoringEvaluatorService.DEFAULT_WARNING_THRESHOLD_PCT,
                    MonitoringEvaluatorService.DEFAULT_CRITICAL_THRESHOLD_PCT,
                    MonitoringEvaluatorService.DEFAULT_STALE_AFTER_MINUTES,
                    true
            ));
        }
        return list;
    }

    private MonitoringDtos.PassSummaryDto buildPassSummary(List<AttendeePass> passes) {
        int notEntered = 0;
        int checkedIn = 0;
        int checkedOut = 0;
        Instant lastScan = null;
        for (AttendeePass p : passes) {
            switch (p.getStatus()) {
                case ISSUED -> notEntered++;
                case CHECKED_IN -> checkedIn++;
                case CHECKED_OUT -> checkedOut++;
            }
            if (p.getLastScannedAt() != null && (lastScan == null || p.getLastScannedAt().isAfter(lastScan))) {
                lastScan = p.getLastScannedAt();
            }
        }
        return new MonitoringDtos.PassSummaryDto(passes.size(), notEntered, checkedIn, checkedOut, lastScan);
    }

    private List<MonitoringDtos.PracticeRunOptionDto> buildPracticeRunOptions(Long eventId) {
        List<SimulationRun> runs = simulationRunRepository.findByEventIdWithSnapshotOrderByCreatedDesc(eventId);
        List<MonitoringDtos.PracticeRunOptionDto> options = new ArrayList<>(runs.size());
        for (SimulationRun run : runs) {
            int frameCount = 0;
            if (run.getFrameStreamPath() != null) {
                try {
                    SimulationDtos.SimulationFrameStream stream =
                            simulationStorageService.loadFrameStream(eventId, run.getFrameStreamPath());
                    frameCount = stream.frames() != null ? stream.frames().size() : 0;
                } catch (RuntimeException ignored) {
                }
            }
            options.add(new MonitoringDtos.PracticeRunOptionDto(
                    run.getId(),
                    run.getLabel() != null ? run.getLabel() : ("Run #" + run.getId()),
                    run.getScenarioType().getDisplayName(),
                    frameCount,
                    run.getDurationSeconds(),
                    run.getAgentCount()
            ));
        }
        return options;
    }

    private MonitoringDtos.HeatmapGeometryDto buildHeatmapGeometry(Long eventId,
                                                                   EventLayoutContext ctx,
                                                                   List<MonitoringDtos.GateStatusDto> gates) {
        VenuePlan plan = ctx.plan();
        Envelope env = new Envelope();
        List<GeometryUtil.Point2D> boundaryPoints = List.of();
        if (plan != null && plan.getBoundaryWkt() != null && !plan.getBoundaryWkt().isBlank()) {
            try {
                Polygon boundary = geometryUtil.parsePolygon(plan.getBoundaryWkt());
                boundaryPoints = geometryUtil.extractOuterRing(boundary);
                env.expandToInclude(boundary.getEnvelopeInternal());
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<SimulationDtos.SnapExclusion> snapExclusions = new ArrayList<>();
        for (ExclusionArea ex : ctx.exclusions()) {
            try {
                Polygon poly = geometryUtil.parsePolygon(ex.getPolygonWkt());
                env.expandToInclude(poly.getEnvelopeInternal());
                snapExclusions.add(new SimulationDtos.SnapExclusion(
                        ex.getId(),
                        ex.getLabel(),
                        ex.getExclusionType().name(),
                        ex.getExclusionType().isCorridor(),
                        ex.getExclusionType().getFillColor(),
                        ex.getExclusionType().getStrokeColor(),
                        ex.getPolygonWkt(),
                        geometryUtil.extractOuterRing(poly)
                ));
            } catch (IllegalArgumentException ignored) {
            }
        }

        for (FunctionalZone z : ctx.zones()) {
            if (z.getPolygonWkt() != null && !z.getPolygonWkt().isBlank()) {
                try {
                    Geometry g = geometryUtil.parsePolygonal(z.getPolygonWkt());
                    env.expandToInclude(g.getEnvelopeInternal());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        GeometryUtil.Bounds bounds = !env.isNull()
                ? new GeometryUtil.Bounds(
                geometryUtil.round2(env.getMinX()),
                geometryUtil.round2(env.getMinY()),
                geometryUtil.round2(env.getMaxX()),
                geometryUtil.round2(env.getMaxY()))
                : new GeometryUtil.Bounds(0.0, 0.0, 100.0, 80.0);

        boolean hasBlueprint = plan != null && plan.getBlueprintStoredFilename() != null;
        String blueprintUrl = hasBlueprint ? "/api/events/" + eventId + "/venue/blueprint" : null;

        return new MonitoringDtos.HeatmapGeometryDto(
                plan != null ? plan.getWorkspaceMode().name() : "INTERACTIVE_MAP",
                hasBlueprint,
                blueprintUrl,
                plan != null ? plan.getBlueprintWidthPx() : null,
                plan != null ? plan.getBlueprintHeightPx() : null,
                plan != null ? plan.getPixelsPerMeter() : null,
                boundaryPoints,
                bounds,
                List.copyOf(snapExclusions),
                gates
        );
    }

    private MonitoringDtos.AlertEventDto toAlertDto(MonitoringAlertEvent a) {
        FunctionalZone z = a.getZone();
        String sevBadge = switch (a.getSeverity()) {
            case CRITICAL -> "badge-critical";
            case HIGH -> "badge-high";
            case LOW -> "badge-low";
            case INFO -> "badge-info";
        };
        return new MonitoringDtos.AlertEventDto(
                a.getId(),
                z != null ? z.getId() : null,
                z != null ? z.getZoneCode() : null,
                z != null ? z.getName() : null,
                a.getDataSource(),
                a.getDataSource().getShortLabel(),
                a.getDataSource().getBadgeClass(),
                a.getSeverity(),
                sevBadge,
                a.getAlertType(),
                a.getAlertType().getDisplayName(),
                a.getMessage(),
                a.getTriggeredAt(),
                a.getAcknowledgedBy(),
                a.getAcknowledgedAt(),
                a.getResolvedAt(),
                a.getResolvedAt() == null
        );
    }

    private MonitoringDtos.AttendeePassDto toPassDto(Long eventId, AttendeePass p) {
        return new MonitoringDtos.AttendeePassDto(
                p.getId(),
                p.getPassCode(),
                p.getHolderLabel(),
                p.getStatus(),
                p.getStatus().getDisplayName(),
                p.getStatus().getBadgeClass(),
                p.getLastGate() != null ? p.getLastGate().getId() : null,
                p.getLastGate() != null ? p.getLastGate().getLabel() : null,
                p.getLastZone() != null ? p.getLastZone().getId() : null,
                p.getLastZone() != null ? p.getLastZone().getZoneCode() + " · " + p.getLastZone().getName() : null,
                p.getLastScannedAt(),
                p.getLastScannedByName(),
                "/api/events/" + eventId + "/live/passes/" + p.getId() + "/qr"
        );
    }

    private MonitoringDtos.OccupancyReadingDto toReadingDto(OccupancyReading r) {
        return new MonitoringDtos.OccupancyReadingDto(
                r.getId(),
                r.getZone() != null ? r.getZone().getId() : null,
                r.getZone() != null ? r.getZone().getZoneCode() : null,
                r.getZone() != null ? r.getZone().getName() : null,
                r.getGate() != null ? r.getGate().getId() : null,
                r.getGate() != null ? r.getGate().getLabel() : null,
                r.getCountValue(),
                r.getDataSource(),
                r.getDataSource().getShortLabel(),
                r.getDataSource().getBadgeClass(),
                r.getRecordedBy(),
                r.getRecordedAt(),
                r.getNote()
        );
    }

    private InfrastructureElement resolveGate(VenuePlan plan, Long gateId) {
        if (gateId == null) {
            return null;
        }
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected gate not found in venue plan.");
        }
        return infrastructureElementRepository.findByIdAndVenuePlanId(gateId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Selected gate does not belong to this event."));
    }

    private FunctionalZone resolveScanZone(VenuePlan plan, Long zoneId, InfrastructureElement gate) {
        if (zoneId != null) {
            if (plan == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected zone not found in venue plan.");
            }
            return functionalZoneRepository.findByIdAndVenuePlanId(zoneId, plan.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Selected zone does not belong to this event."));
        }
        if (plan == null || gate == null) {
            return null;
        }
        List<FunctionalZone> zones = functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        Point gatePt = geometryUtil.getGeometryFactory().createPoint(
                new Coordinate(gate.getCenterX(), gate.getCenterY()));
        FunctionalZone nearest = null;
        double bestDist = GATE_ZONE_SNAP_METERS;
        for (FunctionalZone z : zones) {
            if (!z.getZoneType().isWalkable() || z.getPolygonWkt() == null) {
                continue;
            }
            try {
                Geometry geom = geometryUtil.parsePolygonal(z.getPolygonWkt());
                if (geom.covers(gatePt)) {
                    return z;
                }
                double d = geom.distance(gatePt);
                if (d <= bestDist) {
                    bestDist = d;
                    nearest = z;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return nearest;
    }

    private void publishSseUpdate(Long eventId, OccupancyDataSource source, String reason) {
        sseHub.publish(eventId, "update", Map.of(
                "eventId", eventId,
                "dataSource", source.name(),
                "reason", reason,
                "timestamp", Instant.now().toString()
        ));
    }

    private AppUser resolveUser(Long userId) {
        return userId != null ? userRepository.findById(userId).orElse(null) : null;
    }

    private String resolveActorName(AppUser user, String fallbackName) {
        if (user != null && user.getFullName() != null && !user.getFullName().isBlank()) {
            return user.getFullName();
        }
        if (fallbackName != null && !fallbackName.isBlank()) {
            return fallbackName.trim();
        }
        return "Operations Staff";
    }

    private String trimToNull(String val) {
        if (val == null) {
            return null;
        }
        String trimmed = val.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record EventLayoutContext(
            VenuePlan plan,
            List<ExclusionArea> exclusions,
            List<FunctionalZone> zones,
            List<InfrastructureElement> elements,
            CapacityDtos.CapacityCalculationResult capacity
    ) {
    }
}
