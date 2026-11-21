package com.vyuha.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.auth.AppUser;
import com.vyuha.auth.Role;
import com.vyuha.auth.UserRepository;
import com.vyuha.capacity.AssumptionReviewStatus;
import com.vyuha.capacity.CapacityDtos;
import com.vyuha.capacity.CapacityService;
import com.vyuha.emergency.EmergencyAssetType;
import com.vyuha.emergency.EmergencyDtos;
import com.vyuha.emergency.EmergencyService;
import com.vyuha.event.ChecklistItemStatus;
import com.vyuha.event.Event;
import com.vyuha.event.EventCategory;
import com.vyuha.event.EventChecklistItem;
import com.vyuha.event.EventForm;
import com.vyuha.event.EventRepository;
import com.vyuha.event.EventService;
import com.vyuha.event.EventStatus;
import com.vyuha.event.SafetyReviewRecord;
import com.vyuha.event.SafetyReviewRecordRepository;
import com.vyuha.event.SafetyReviewService;
import com.vyuha.event.SafetyReviewStatus;
import com.vyuha.grid.GridDtos;
import com.vyuha.grid.GridZoneService;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.infrastructure.InfrastructureService;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.monitoring.LiveMonitoringService;
import com.vyuha.monitoring.MonitoringDtos;
import com.vyuha.monitoring.PassScanAction;
import com.vyuha.network.NetworkDtos;
import com.vyuha.network.NetworkLayer;
import com.vyuha.network.NetworkService;
import com.vyuha.network.PathwayDirection;
import com.vyuha.network.PathwayNodeType;
import com.vyuha.operations.IncidentStatus;
import com.vyuha.operations.OperationsDtos;
import com.vyuha.operations.OperationsService;
import com.vyuha.operations.StaffRoleType;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.simulation.SimulationScenarioType;
import com.vyuha.simulation.SimulationService;
import com.vyuha.venue.CalibrationData;
import com.vyuha.venue.CalibrationService;
import com.vyuha.venue.ExclusionType;
import com.vyuha.venue.SiteBlueprintStorageService;
import com.vyuha.venue.VenueDtos;
import com.vyuha.venue.VenuePlan;
import com.vyuha.venue.VenuePlanRepository;
import com.vyuha.venue.VenueService;
import com.vyuha.venue.WorkspaceMode;
import jakarta.persistence.EntityManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent flagship demo seed and one-click reset service for
 * "TechnoVyuha Annual Campus Festival" (Project Spec Section 6).
 */
@Service
public class CampusFestivalSeedService {

    public static final String DEMO_EVENT_NAME = "TechnoVyuha Annual Campus Festival";
    public static final long DEMO_SIM_SEED = 2026L;

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final EventService eventService;
    private final SafetyReviewService safetyReviewService;
    private final SafetyReviewRecordRepository safetyReviewRecordRepository;
    private final VenueService venueService;
    private final VenuePlanRepository venuePlanRepository;
    private final SiteBlueprintStorageService blueprintStorageService;
    private final CalibrationService calibrationService;
    private final GridZoneService gridZoneService;
    private final CapacityService capacityService;
    private final InfrastructureService infrastructureService;
    private final NetworkService networkService;
    private final OperationsService operationsService;
    private final EmergencyService emergencyService;
    private final SimulationService simulationService;
    private final LiveMonitoringService liveMonitoringService;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public CampusFestivalSeedService(EventRepository eventRepository,
                                     UserRepository userRepository,
                                     EventService eventService,
                                     SafetyReviewService safetyReviewService,
                                     SafetyReviewRecordRepository safetyReviewRecordRepository,
                                     VenueService venueService,
                                     VenuePlanRepository venuePlanRepository,
                                     SiteBlueprintStorageService blueprintStorageService,
                                     CalibrationService calibrationService,
                                     GridZoneService gridZoneService,
                                     CapacityService capacityService,
                                     InfrastructureService infrastructureService,
                                     NetworkService networkService,
                                     OperationsService operationsService,
                                     EmergencyService emergencyService,
                                     SimulationService simulationService,
                                     LiveMonitoringService liveMonitoringService,
                                     ObjectMapper objectMapper,
                                     EntityManager entityManager) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.eventService = eventService;
        this.safetyReviewService = safetyReviewService;
        this.safetyReviewRecordRepository = safetyReviewRecordRepository;
        this.venueService = venueService;
        this.venuePlanRepository = venuePlanRepository;
        this.blueprintStorageService = blueprintStorageService;
        this.calibrationService = calibrationService;
        this.gridZoneService = gridZoneService;
        this.capacityService = capacityService;
        this.infrastructureService = infrastructureService;
        this.networkService = networkService;
        this.operationsService = operationsService;
        this.emergencyService = emergencyService;
        this.simulationService = simulationService;
        this.liveMonitoringService = liveMonitoringService;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    @Transactional
    public Event seedIfMissing() {
        List<Event> existing = findDemoEvents();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return createFullCampusFestivalDemo(null);
    }

    @Transactional
    public Event resetDemoEvent(Long actorUserId) {
        List<Event> existing = findDemoEvents();
        for (Event ev : existing) {
            eventService.deleteEvent(ev.getId());
        }
        entityManager.flush();
        entityManager.clear();
        return createFullCampusFestivalDemo(actorUserId);
    }

    private List<Event> findDemoEvents() {
        return eventRepository.findAll().stream()
                .filter(e -> DEMO_EVENT_NAME.equalsIgnoreCase(e.getName()))
                .toList();
    }

    private Event createFullCampusFestivalDemo(Long preferredActorId) {
        AppUser organizer = resolveUser("organizer@vyuha.local", Role.ORGANIZER, preferredActorId);
        AppUser reviewer = resolveUser("reviewer@vyuha.local", Role.SAFETY_REVIEWER, organizer.getId());
        AppUser fieldUser = userRepository.findByEmailIgnoreCase("staff@vyuha.local").orElse(null);

        // 1. Create Event + Checklist + Safety Review Log
        EventForm form = new EventForm();
        form.setName(DEMO_EVENT_NAME);
        form.setCategory(EventCategory.COLLEGE_FESTIVAL);
        form.setStatus(EventStatus.IN_REVIEW);
        LocalDateTime eventStart = LocalDateTime.of(2026, 12, 10, 16, 0);
        LocalDateTime eventEnd = LocalDateTime.of(2026, 12, 10, 22, 0);
        form.setStartDateTime(eventStart);
        form.setEndDateTime(eventEnd);
        form.setScheduleTimeline("16:00 Gate Opening | 17:30 Tech Expo & Food Court | 19:00 Main Stage Showcase | 21:30 Controlled Egress");
        form.setExpectedAttendance(3500);
        form.setLocationName("Central Campus Quadrangle");
        form.setLocationAddress("North Academic Precinct, Vyuha Institute Campus");
        form.setDescription("Flagship end-to-end crowd safety walkthrough comparing flawed Layout V1 against remediated Layout V2 for 3,500 festival attendees.");
        form.setAssignedReviewerId(reviewer.getId());

        Event event = eventService.createEvent(form, organizer.getId());
        Long eventId = event.getId();

        populateChecklistAndReviews(eventId, organizer, reviewer);

        // 2. Calibrated Orthographic Blueprint + Boundary + Exclusions (120 m x 90 m @ 10 px/m)
        setupCalibratedSiteAndExclusions(event);

        // 3. Grid & Functional Zones
        ZoneIds zones = setupGridAndZones(eventId);

        // 4. Capacity Review Parameters & Zone Density Assumptions
        setupCapacityReview(eventId, zones, reviewer);

        // 5. Emergency Assets & Prioritized Contacts
        setupEmergencyPlan(eventId);

        // 6. Arrival Waves, Staff Roster, Shift Coverage & Drill Incident
        setupOperationsAndStaff(eventId, zones, eventStart, eventEnd, fieldUser, organizer);

        // 7. Flawed "Before" Layout V1 -> Snapshot V1 + ARRIVAL_RUSH simulation
        V1Ids v1 = setupFlawedLayoutV1(eventId, zones, organizer.getId());
        SimulationDtos.LayoutSnapshotDetailDto snapV1 = simulationService.captureSnapshot(
                eventId, organizer.getId(),
                new SimulationDtos.CreateSnapshotRequest(
                        "Before — Initial Layout V1",
                        "Flawed V1: Food Stall #3 intrudes East Ambulance Lane; 1.8m central pinch; crossing North entry/exit; blocked SW exit.",
                        null));

        // 8. Remediated "After" Layout V2 -> Snapshot V2 + Paired ARRIVAL_RUSH simulation
        remediateToLayoutV2(eventId, zones, v1, organizer.getId());
        SimulationDtos.LayoutSnapshotDetailDto snapV2 = simulationService.captureSnapshot(
                eventId, organizer.getId(),
                new SimulationDtos.CreateSnapshotRequest(
                        "After — Widened Promenade & Cleared Lanes V2",
                        "Remediated V2: Relocated Food Stall #3, widened 4.5m promenade, separated NE Exit, cleared SW Exit apron.",
                        null));

        simulationService.runPairedComparison(
                eventId,
                organizer.getId(),
                new SimulationDtos.CompareSnapshotsRequest(
                        snapV1.summary().id(),
                        snapV2.summary().id(),
                        SimulationScenarioType.ARRIVAL_RUSH,
                        DEMO_SIM_SEED,
                        300,
                        360,
                        4.0,
                        2.0,
                        1.35,
                        null,
                        null,
                        null,
                        null,
                        45));

        // 9. Sample Live Ops Passes, Source-Segregated Readings & Alert Rule
        setupLiveMonitoringSample(eventId, zones, organizer, fieldUser);

        return eventService.getEventOrThrow(eventId);
    }

    private void populateChecklistAndReviews(Long eventId, AppUser organizer, AppUser reviewer) {
        List<EventChecklistItem> items = eventService.getChecklist(eventId);
        for (int i = 0; i < items.size(); i++) {
            EventChecklistItem item = items.get(i);
            if (i < 5) {
                eventService.updateChecklistItem(eventId, item.getId(), ChecklistItemStatus.COMPLETE,
                        organizer.getFullName(), "Verified in V2 quadrangle layout and emergency audit.");
                if (i < 3) {
                    eventService.toggleChecklistSignOff(eventId, item.getId(), reviewer.getId(), true);
                }
            } else if (i == 5) {
                eventService.updateChecklistItem(eventId, item.getId(), ChecklistItemStatus.IN_PROGRESS,
                        organizer.getFullName(), "Awaiting final pre-gate barricade tape check.");
            }
        }

        SafetyReviewRecord v1Review = safetyReviewService.issueReview(
                eventId,
                reviewer.getId(),
                SafetyReviewStatus.REVISIONS_REQUIRED,
                "Initial Layout V1 findings: (1) Food Stall #3 encroaches on East Ambulance Access Lane; (2) Central 1.8m pinch corridor merges 3 audience walkways; (3) North gate exhibits entry/exit contra-flow; (4) Misplaced barricade obstructs South-West Exit apron.");
        v1Review.setReviewedAt(Instant.now().minusSeconds(3600));
        safetyReviewRecordRepository.save(v1Review);

        safetyReviewService.addOrganizerResponse(
                eventId,
                v1Review.getId(),
                organizer.getId(),
                "Remediated in Layout V2: relocated Food Stall #3 to West Food Court, widened central promenade to 4.5m, added dedicated North-East Exit funnel, and cleared South-West Exit apron.");

        safetyReviewService.issueReview(
                eventId,
                reviewer.getId(),
                SafetyReviewStatus.COMMENTS_ISSUED,
                "Layout V2 remediation verified in Before-and-After ARRIVAL_RUSH simulation; final site walk-through scheduled prior to gate opening.");
    }

    private void setupCalibratedSiteAndExclusions(Event event) {
        Long eventId = event.getId();
        byte[] blueprintPng = renderCampusBlueprintPng();
        SiteBlueprintStorageService.StoredBlueprint stored = blueprintStorageService.storeGeneratedPngBlueprint(
                eventId, "campus-quadrangle-orthographic.png", blueprintPng);

        VenuePlan plan = venueService.getOrCreatePlan(event);
        plan.setWorkspaceMode(WorkspaceMode.BLUEPRINT_UPLOAD);
        plan.setSampleMapKey("campus-quadrangle");
        plan.setBlueprintOriginalFilename(stored.originalFilename());
        plan.setBlueprintStoredFilename(stored.storedFilename());
        plan.setBlueprintContentType(stored.contentType());
        plan.setBlueprintFileSizeBytes(stored.fileSizeBytes());
        plan.setBlueprintWidthPx(stored.widthPx());
        plan.setBlueprintHeightPx(stored.heightPx());
        plan.setGridSnapMeters(1.0);

        CalibrationData cal = calibrationService.calibrate(
                new VenueDtos.CalibrationRequest(
                        80.0, 100.0, 1120.0, 100.0, 104.0,
                        80.0, 100.0, 80.0, 820.0, 72.0,
                        true, false),
                stored.widthPx(), stored.heightPx());
        plan.setPixelsPerMeter(cal.pixelsPerMeter());
        plan.setScaleVerified(cal.scaleVerified());
        plan.setDistortionWarning(cal.distortionWarning());
        plan.setOrthographicConfirmed(cal.orthographicConfirmed());
        try {
            plan.setCalibrationJson(objectMapper.writeValueAsString(cal));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize demo calibration data.", ex);
        }
        venuePlanRepository.save(plan);

        venueService.saveBoundary(eventId,
                new VenueDtos.BoundaryUpdateRequest(null, "POLYGON ((8 10, 112 10, 112 82, 8 82, 8 10))", "METERS", false),
                true);

        venueService.createExclusion(eventId, new VenueDtos.ExclusionRequest(
                "Main Academic Hall (North)", ExclusionType.BUILDING, "Permanent north campus academic block",
                null, "POLYGON ((14 14, 48 14, 48 28, 14 28, 14 14))", "METERS"), true);
        venueService.createExclusion(eventId, new VenueDtos.ExclusionRequest(
                "Science Block (West)", ExclusionType.BUILDING, "Permanent west laboratory wing",
                null, "POLYGON ((12 34, 24 34, 24 68, 12 68, 12 34))", "METERS"), true);
        venueService.createExclusion(eventId, new VenueDtos.ExclusionRequest(
                "Main Auditorium Stage Apron", ExclusionType.STAGE, "Primary concert stage & backstage deck",
                null, "POLYGON ((46 62, 74 62, 74 76, 46 76, 46 62))", "METERS"), true);
        venueService.createExclusion(eventId, new VenueDtos.ExclusionRequest(
                "AV Control & Generator Compound", ExclusionType.EQUIPMENT, "Fenced diesel generator and FOH AV enclosure",
                null, "POLYGON ((76 64, 88 64, 88 76, 76 76, 76 64))", "METERS"), true);
        venueService.createExclusion(eventId, new VenueDtos.ExclusionRequest(
                "East Ambulance & Fire Corridor", ExclusionType.RESERVED_WALKWAY, "Dedicated 10m emergency vehicle lane",
                null, "POLYGON ((96 10, 106 10, 106 82, 96 82, 96 10))", "METERS"), true);
    }

    private ZoneIds setupGridAndZones(Long eventId) {
        gridZoneService.updateGridConfig(eventId, new GridDtos.GridConfigRequest(5.0, 0.0, 0.0, 0.0), true);

        long entryId = createZone(eventId, "Z-ENT", "North Main Entry Plaza", ZoneType.ENTRY,
                "POLYGON ((50 10, 74 10, 74 18, 50 18, 50 10))");
        long secId = createZone(eventId, "Z-SEC", "North Security Screening", ZoneType.SECURITY_SCREENING,
                "POLYGON ((50 18, 74 18, 74 24, 50 24, 50 18))");
        long queueId = createZone(eventId, "Z-QUE", "North Holding & Queue Pen", ZoneType.QUEUE,
                "POLYGON ((50 24, 74 24, 74 32, 50 32, 50 24))");
        long audienceId = createZone(eventId, "Z-AUD1", "Central Lawn Main Audience", ZoneType.AUDIENCE,
                "POLYGON ((34 32, 86 32, 86 60, 34 60, 34 32))");
        long foodId = createZone(eventId, "Z-FOOD", "West Lawn Food & Expo Court", ZoneType.FOOD_STALLS,
                "POLYGON ((24 32, 34 32, 34 60, 24 60, 24 32))");
        long restId = createZone(eventId, "Z-REST", "South-West Amenities", ZoneType.RESTROOMS,
                "POLYGON ((14 70, 32 70, 32 80, 14 80, 14 70))");
        long medId = createZone(eventId, "Z-MED", "East First Aid & Medical Bay", ZoneType.MEDICAL,
                "POLYGON ((86 38, 96 38, 96 52, 86 52, 86 38))");
        long exitId = createZone(eventId, "Z-EX1", "South-West Exit Concourse", ZoneType.EXIT,
                "POLYGON ((32 70, 46 70, 46 82, 32 82, 32 70))");
        long emcId = createZone(eventId, "Z-EMC", "East Emergency Vehicle Corridor", ZoneType.EMERGENCY_CORRIDOR,
                "POLYGON ((96 10, 106 10, 106 82, 96 82, 96 10))");

        return new ZoneIds(entryId, secId, queueId, audienceId, foodId, restId, medId, exitId, emcId);
    }

    private long createZone(Long eventId, String code, String name, ZoneType type, String wkt) {
        GridDtos.GridWorkspaceDto ws = gridZoneService.createZone(
                eventId,
                new GridDtos.ZoneCreateRequest(code, name, type, null, null, null, wkt),
                true);
        return ws.zones().stream()
                .filter(z -> code.equals(z.zoneCode()))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private void setupCapacityReview(Long eventId, ZoneIds zones, AppUser reviewer) {
        capacityService.updateParameters(eventId, new CapacityDtos.CapacityParametersRequest(
                4000,
                "Campus Estates & Fire Advisory Permit #2026-TV",
                140.0,
                30,
                180.0,
                25,
                250,
                "Reviewed multi-constraint ceiling for 3,500 target attendance."
        ), true, false);

        capacityService.saveZoneAssumption(eventId, zones.audienceId(),
                new CapacityDtos.ZoneAssumptionRequest(2.0, "NDMA Campus Gathering Guide §3.2 (Standing Lawn)", reviewer.getFullName()),
                true, false);
        capacityService.reviewZoneAssumption(eventId, zones.audienceId(), reviewer.getId(),
                new CapacityDtos.ReviewerAssumptionAction(AssumptionReviewStatus.REVIEWER_VERIFIED,
                        "2.0 p/m² verified for open flat quadrangle lawn."), true, true);

        capacityService.saveZoneAssumption(eventId, zones.foodId(),
                new CapacityDtos.ZoneAssumptionRequest(1.2, "Campus Event Ops Standard §4.1 (Stall Aisles)", reviewer.getFullName()),
                true, false);
        capacityService.reviewZoneAssumption(eventId, zones.foodId(), reviewer.getId(),
                new CapacityDtos.ReviewerAssumptionAction(AssumptionReviewStatus.REVIEWER_VERIFIED,
                        "Verified lower density allowance around food stall queues."), true, true);

        capacityService.saveZoneAssumption(eventId, zones.queueId(),
                new CapacityDtos.ZoneAssumptionRequest(2.0, "Queue Holding Space Design Guide §2.1", reviewer.getFullName()),
                true, false);
        capacityService.saveZoneAssumption(eventId, zones.secId(),
                new CapacityDtos.ZoneAssumptionRequest(1.5, "Security Screening Lane Guide §2.3", reviewer.getFullName()),
                true, false);
    }

    private void setupEmergencyPlan(Long eventId) {
        emergencyService.createAsset(eventId, new EmergencyDtos.EmergencyAssetRequest(
                EmergencyAssetType.AMBULANCE_ROUTE,
                "East Ambulance Access Road",
                "LINESTRING (101 12, 101 80)",
                null, null, null, 4.5,
                "4.5 m clear asphalt lane from North Road to Medical Bay & South Gate",
                null), true);

        emergencyService.createAsset(eventId, new EmergencyDtos.EmergencyAssetRequest(
                EmergencyAssetType.FIRE_ROUTE,
                "North Perimeter Fire Tender Track",
                "LINESTRING (10 12, 98 12)",
                null, null, null, 4.2,
                "4.2 m perimeter fire lane",
                null), true);

        emergencyService.createAsset(eventId, new EmergencyDtos.EmergencyAssetRequest(
                EmergencyAssetType.ASSEMBLY_POINT,
                "North-East Outer Lawn Assembly Point",
                "POLYGON ((106 14, 111 14, 111 32, 106 32, 106 14))",
                null, null, null, null,
                "External muster area capacity ~600 persons",
                null), true);

        emergencyService.createAsset(eventId, new EmergencyDtos.EmergencyAssetRequest(
                EmergencyAssetType.EMERGENCY_EXIT,
                "East Emergency Gate E1",
                "POINT (108 46)",
                null, 108.0, 46.0, 4.0,
                "4.0 m crash-bar outward gate",
                null), true);

        emergencyService.createContact(eventId, new EmergencyDtos.EmergencyContactRequest(
                "City Police Control Room", "Insp. Vikram Nair", "+91-100-221100", "Law & Perimeter Command", 1, "Direct festival liaison"), true);
        emergencyService.createContact(eventId, new EmergencyDtos.EmergencyContactRequest(
                "District Fire & Rescue", "Station Officer Meera Joshi", "+91-101-224488", "Fire & Hazmat Response", 2, "East gate tender staging"), true);
        emergencyService.createContact(eventId, new EmergencyDtos.EmergencyContactRequest(
                "Campus Trauma Ambulance", "Dr. Sanjay Kulkarni", "+91-108-553311", "On-Site Medical Lead", 3, "Stationed at East Medical Bay"), true);
        emergencyService.createContact(eventId, new EmergencyDtos.EmergencyContactRequest(
                "Vyuha Event Incident Command", "Aarav Mehta", "+91-98200-11223", "Event Incident Coordinator", 4, "Central Control Room"), true);
    }

    private void setupOperationsAndStaff(Long eventId,
                                         ZoneIds zones,
                                         LocalDateTime start,
                                         LocalDateTime end,
                                         AppUser fieldUser,
                                         AppUser organizer) {
        operationsService.createWave(eventId, new OperationsDtos.ArrivalWaveRequest(
                "Early Gate Opening", 0, 60, 650, null, "Student & stall crew entry"), true, false);
        operationsService.createWave(eventId, new OperationsDtos.ArrivalWaveRequest(
                "Evening Arrival Surge", 60, 120, 1450, null, "Peak festival rush prior to stage headliner"), true, false);
        operationsService.createWave(eventId, new OperationsDtos.ArrivalWaveRequest(
                "Main Concert Influx", 120, 180, 950, null, "General admission arrivals"), true, false);
        operationsService.createWave(eventId, new OperationsDtos.ArrivalWaveRequest(
                "Late Evening Arrivals", 180, 240, 450, null, "Tapering entry wave"), true, false);

        operationsService.updateStaffingTargets(eventId, new OperationsDtos.StaffingTargetsRequest(
                2, 1, 2, 1, 600, 60, "Standard campus festival staffing baseline"), true, false);

        var s1 = createStaff(eventId, "Rohan Verma", StaffRoleType.SECURITY, "+91-98111-10001", "Gate Screening & Crowd Marshalling", fieldUser != null ? fieldUser.getId() : null);
        var s2 = createStaff(eventId, "Priya Deshmukh", StaffRoleType.SECURITY, "+91-98111-10002", "North Gate Queue Control", null);
        var s3 = createStaff(eventId, "Dr. Ananya Sen", StaffRoleType.MEDICAL, "+91-98111-10003", "ACLS / Trauma First Aid", null);
        var s4 = createStaff(eventId, "Karan Kapoor", StaffRoleType.MEDICAL, "+91-98111-10004", "EMT Paramedic", null);
        var s5 = createStaff(eventId, "Neha Iyer", StaffRoleType.SUPERVISOR, "+91-98111-10005", "ICS Crowd Safety Lead", null);
        var s6 = createStaff(eventId, "Dev Patel", StaffRoleType.VOLUNTEER, "+91-98111-10006", "Wayfinding & Egress Steward", null);

        operationsService.createAssignment(eventId, new OperationsDtos.StaffAssignmentRequest(
                s1, zones.entryId(), null, "North Entry Gate", start, end, "Primary gate marshal"), true, false);
        operationsService.createAssignment(eventId, new OperationsDtos.StaffAssignmentRequest(
                s2, zones.secId(), null, "Security Screening Lane", start, end, "Queue funnel & bag check"), true, false);
        operationsService.createAssignment(eventId, new OperationsDtos.StaffAssignmentRequest(
                s3, zones.medId(), null, "East Medical Bay", start, end, "Lead trauma physician"), true, false);
        operationsService.createAssignment(eventId, new OperationsDtos.StaffAssignmentRequest(
                s4, zones.medId(), null, "East Ambulance Bay", start, end, "Ambulance bay paramedic"), true, false);
        operationsService.createAssignment(eventId, new OperationsDtos.StaffAssignmentRequest(
                s5, zones.audienceId(), null, "Central Lawn Command", start, end, "Floor crowd density supervisor"), true, false);
        operationsService.createAssignment(eventId, new OperationsDtos.StaffAssignmentRequest(
                s6, zones.exitId(), null, "South-West Exit Concourse", start, end, "Egress flow steward"), true, false);

        var incRes = operationsService.createIncident(eventId, organizer.getId(), new OperationsDtos.CreateIncidentRequest(
                FindingSeverity.LOW, zones.foodId(), s5,
                "Pre-event walkthrough identified Food Stall #3 utility crates staged inside East Ambulance Lane.",
                "Relocated stall and crates to West Lawn Food Court in Layout V2."), true, false);
        if (!incRes.incidents().isEmpty()) {
            long incId = incRes.incidents().get(0).id();
            operationsService.updateIncident(eventId, incId, new OperationsDtos.UpdateIncidentRequest(
                    IncidentStatus.RESOLVED, FindingSeverity.LOW, s5,
                    "Verified 4.5 m clear ambulance corridor in Layout V2."), true, false);
        }
    }

    private long createStaff(Long eventId, String name, StaffRoleType role, String phone, String cert, Long userId) {
        OperationsDtos.OperationsWorkspaceDto ws = operationsService.createStaffMember(
                eventId,
                new OperationsDtos.StaffMemberRequest(name, role, phone, cert, null, userId),
                true, false);
        return ws.staffMembers().stream()
                .filter(m -> name.equals(m.name()))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private V1Ids setupFlawedLayoutV1(Long eventId, ZoneIds zones, Long actorId) {
        long entryGateId = placeElement(eventId, actorId, InfrastructureType.ENTRY_GATE, "North Main Entry Gate", 60, 12, 8.0, 2.0, 120.0, false);
        long secId = placeElement(eventId, actorId, InfrastructureType.SECURITY_CHECKPOINT, "Security Screening Lane", 60, 20, 8.0, 2.0, 100.0, false);
        placeElement(eventId, actorId, InfrastructureType.STAGE, "Main Festival Stage", 60, 68, 24.0, 10.0, null, false);
        placeElement(eventId, actorId, InfrastructureType.AMBULANCE_LANE, "East Ambulance Lane", 101, 46, 4.5, 68.0, null, false);
        long stall3Id = placeElement(eventId, actorId, InfrastructureType.FOOD_STALL, "Food Stall #3 (Intruding)", 98, 44, 5.0, 4.0, null, false);
        placeElement(eventId, actorId, InfrastructureType.FOOD_STALL, "Food Stall #1", 28, 38, 4.0, 4.0, null, false);
        long medPostId = placeElement(eventId, actorId, InfrastructureType.MEDICAL_POST, "Main Medical Post", 91, 45, 4.0, 4.0, null, false);
        long swExitId = placeElement(eventId, actorId, InfrastructureType.EXIT_GATE, "South-West Exit Gate", 38, 80, 6.0, 2.0, 110.0, false);
        long barricadeId = placeElement(eventId, actorId, InfrastructureType.BARRICADE, "Misplaced Crowd Barricade", 38, 78, 5.0, 1.2, null, false);
        long eastEmExitId = placeElement(eventId, actorId, InfrastructureType.EMERGENCY_EXIT, "East Emergency Exit", 108, 46, 4.0, 2.0, 100.0, false);

        infrastructureService.runConflictCheck(eventId, true, true);

        // Pedestrian network V1
        long entryNode = createNode(eventId, PathwayNodeType.ENTRY_GATE, "North Main Entry Node", 60, 12, null, entryGateId);
        long secNode = createNode(eventId, PathwayNodeType.SECURITY_CHECKPOINT, "Security Screening Node", 60, 20, null, secId);
        long queueNode = createNode(eventId, PathwayNodeType.INTERSECTION, "North Queue Funnel", 60, 28, zones.queueId(), null);
        long pinchNode = createNode(eventId, PathwayNodeType.INTERSECTION, "Central Pinch Merge", 60, 36, null, null);
        long audNode = createNode(eventId, PathwayNodeType.ZONE_HUB, "Central Lawn Audience Hub", 60, 46, zones.audienceId(), null);
        long foodNode = createNode(eventId, PathwayNodeType.ZONE_HUB, "West Food Court Hub", 29, 46, zones.foodId(), null);
        long medNode = createNode(eventId, PathwayNodeType.ZONE_HUB, "East Medical Bay Hub", 91, 45, zones.medId(), medPostId);
        long swExitNode = createNode(eventId, PathwayNodeType.EXIT_GATE, "South-West Exit Node", 38, 80, null, swExitId);
        long eastEmNode = createNode(eventId, PathwayNodeType.EMERGENCY_EXIT, "East Emergency Exit Node", 108, 46, null, eastEmExitId);

        createEdge(eventId, entryNode, secNode, "North Entry Funnel", NetworkLayer.NORMAL_ENTRY, 4.0, PathwayDirection.ONE_WAY);
        createEdge(eventId, secNode, queueNode, "Screening Exit Lane", NetworkLayer.NORMAL_ENTRY, 4.0, PathwayDirection.ONE_WAY);
        long pinchEdge1 = createEdge(eventId, queueNode, pinchNode, "Central Pinch Neck A", NetworkLayer.INTERNAL_CIRCULATION, 1.8, PathwayDirection.BIDIRECTIONAL);
        createEdge(eventId, foodNode, pinchNode, "West Food Walkway", NetworkLayer.INTERNAL_CIRCULATION, 2.5, PathwayDirection.BIDIRECTIONAL);
        createEdge(eventId, medNode, pinchNode, "East Medical Walkway", NetworkLayer.INTERNAL_CIRCULATION, 2.5, PathwayDirection.BIDIRECTIONAL);
        long pinchEdge2 = createEdge(eventId, pinchNode, audNode, "Central Pinch Neck B", NetworkLayer.INTERNAL_CIRCULATION, 1.8, PathwayDirection.BIDIRECTIONAL);
        createEdge(eventId, audNode, swExitNode, "South-West Egress Path", NetworkLayer.NORMAL_EXIT, 3.8, PathwayDirection.ONE_WAY);
        long crossingExitEdge = createEdge(eventId, audNode, entryNode, "Crossing North Exit Path", NetworkLayer.NORMAL_EXIT, 2.4, PathwayDirection.ONE_WAY);
        createEdge(eventId, medNode, eastEmNode, "East Emergency Corridor", NetworkLayer.EMERGENCY_ACCESS, 4.0, PathwayDirection.BIDIRECTIONAL);

        networkService.runScreening(eventId, true, true);
        return new V1Ids(stall3Id, barricadeId, pinchEdge1, pinchEdge2, crossingExitEdge, audNode, entryNode);
    }

    private void remediateToLayoutV2(Long eventId, ZoneIds zones, V1Ids v1, Long actorId) {
        // 1. Relocate Food Stall #3 out of East Ambulance Lane into West Food Court
        infrastructureService.updateElement(eventId, v1.stall3Id(), new InfrastructureDtos.UpdateElementRequest(
                "Food Stall #3 (Relocated)", 28.0, 52.0, 4.0, 4.0, 0.0,
                null, true, null, false, "Moved from East Ambulance Lane to West Food Court", "MOVE"),
                actorId, true, true);

        // 2. Relocate Barricade clear of South-West Exit apron
        infrastructureService.updateElement(eventId, v1.barricadeId(), new InfrastructureDtos.UpdateElementRequest(
                "Queue Guide Barricade", 46.0, 72.0, 5.0, 1.0, 0.0,
                null, true, null, true, "Aligned parallel to SW Exit concourse leaving >3m apron clear", "MOVE"),
                actorId, true, true);

        // 3. Add dedicated North-East Exit Gate to eliminate crossing entry/exit contra-flow
        long neExitId = placeElement(eventId, actorId, InfrastructureType.EXIT_GATE, "North-East Dedicated Exit", 82, 12, 6.0, 2.0, 120.0, false);
        infrastructureService.runConflictCheck(eventId, true, true);

        // 4. Widen central promenade to 4.5m and separate North-East exit edge
        networkService.updateEdge(eventId, v1.pinchEdge1(), new NetworkDtos.UpdateEdgeRequest(
                "Widened Central Promenade North", NetworkLayer.INTERNAL_CIRCULATION, 4.5,
                PathwayDirection.BIDIRECTIONAL, null, true, false, "Widened from 1.8m to 4.5m"), true, true);
        networkService.updateEdge(eventId, v1.pinchEdge2(), new NetworkDtos.UpdateEdgeRequest(
                "Widened Central Promenade South", NetworkLayer.INTERNAL_CIRCULATION, 4.5,
                PathwayDirection.BIDIRECTIONAL, null, true, false, "Widened from 1.8m to 4.5m"), true, true);

        networkService.deleteEdge(eventId, v1.crossingExitEdge(), true, true);
        long neExitNode = createNode(eventId, PathwayNodeType.EXIT_GATE, "North-East Exit Node", 82, 12, null, neExitId);
        createEdge(eventId, v1.audNode(), neExitNode, "North-East Dedicated Egress", NetworkLayer.NORMAL_EXIT, 4.5, PathwayDirection.ONE_WAY);

        networkService.runScreening(eventId, true, true);
    }

    private void setupLiveMonitoringSample(Long eventId, ZoneIds zones, AppUser organizer, AppUser fieldUser) {
        liveMonitoringService.upsertAlertRule(eventId,
                new MonitoringDtos.AlertRuleRequest(null, 80.0, 95.0, 15), true, false);

        Long staffId = fieldUser != null ? fieldUser.getId() : organizer.getId();
        String staffName = fieldUser != null ? fieldUser.getFullName() : organizer.getFullName();

        liveMonitoringService.recordManualCount(eventId, staffId, staffName,
                new MonitoringDtos.ManualCountRequest(zones.audienceId(), 1420, "Pre-headliner lawn count"), true, false);
        liveMonitoringService.recordManualCount(eventId, staffId, staffName,
                new MonitoringDtos.ManualCountRequest(zones.foodId(), 210, "West food court occupancy"), true, false);
        liveMonitoringService.recordManualCount(eventId, staffId, staffName,
                new MonitoringDtos.ManualCountRequest(zones.queueId(), 185, "North holding pen steady"), true, false);

        MonitoringDtos.ControlCentreDto afterBatch = liveMonitoringService.generatePassBatch(
                eventId, new MonitoringDtos.GeneratePassBatchRequest(12, "TechnoVyuha Guest"), true, false);
        List<MonitoringDtos.AttendeePassDto> passes = afterBatch.recentPasses();
        for (int i = 0; i < Math.min(6, passes.size()); i++) {
            String code = passes.get(i).passCode();
            liveMonitoringService.scanPass(eventId, staffId, staffName,
                    new MonitoringDtos.ScanPassRequest(code, PassScanAction.CHECK_IN, null, zones.audienceId()),
                    true, false);
            if (i < 2) {
                liveMonitoringService.scanPass(eventId, staffId, staffName,
                        new MonitoringDtos.ScanPassRequest(code, PassScanAction.CHECK_OUT, null, zones.exitId()),
                        true, false);
            }
        }
    }

    private long placeElement(Long eventId,
                              Long actorId,
                              InfrastructureType type,
                              String label,
                              double cx,
                              double cy,
                              double w,
                              double h,
                              Double throughput,
                              boolean linearAligned) {
        InfrastructureDtos.InfrastructureWorkspaceDto ws = infrastructureService.createElement(
                eventId,
                new InfrastructureDtos.CreateElementRequest(type, label, cx, cy, w, h, 0.0,
                        throughput, true, null, linearAligned, null),
                actorId, true, true);
        return ws.lastModifiedElementId();
    }

    private long createNode(Long eventId, PathwayNodeType type, String label, double x, double y, Long zoneId, Long infraId) {
        NetworkDtos.NetworkWorkspaceDto ws = networkService.createNode(
                eventId,
                new NetworkDtos.CreateNodeRequest(type, label, x, y, zoneId, infraId, null),
                true, true);
        return ws.lastModifiedNodeId();
    }

    private long createEdge(Long eventId,
                            long fromNodeId,
                            long toNodeId,
                            String label,
                            NetworkLayer layer,
                            double widthM,
                            PathwayDirection dir) {
        NetworkDtos.NetworkWorkspaceDto ws = networkService.createEdge(
                eventId,
                new NetworkDtos.CreateEdgeRequest(fromNodeId, toNodeId, label, layer, widthM, dir, null, true, null),
                true, true);
        return ws.lastModifiedEdgeId();
    }

    private AppUser resolveUser(String email, Role role, Long fallbackUserId) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseGet(() -> userRepository.findByRoleAndEnabledTrueOrderByFullNameAsc(role).stream()
                        .findFirst()
                        .orElseGet(() -> fallbackUserId != null
                                ? userRepository.findById(fallbackUserId).orElseThrow()
                                : userRepository.findAll().stream().findFirst().orElseThrow()));
    }

    private byte[] renderCampusBlueprintPng() {
        int w = 1200;
        int h = 900;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xF8, 0xFA, 0xFC));
            g.fillRect(0, 0, w, h);

            // 10m light grid (100 px)
            g.setColor(new Color(0xE2, 0xE8, 0xF0));
            for (int x = 0; x <= w; x += 100) g.drawLine(x, 0, x, h);
            for (int y = 0; y <= h; y += 100) g.drawLine(0, y, w, y);

            // Outer quadrangle perimeter (8..112, 10..82) -> (80..1120, 100..820)
            g.setColor(new Color(0xFF, 0xFF, 0xFF));
            g.fillRect(80, 100, 1040, 720);
            g.setColor(new Color(0x47, 0x55, 0x69));
            g.setStroke(new BasicStroke(3f));
            g.drawRect(80, 100, 1040, 720);

            // Buildings & Stage landmarks
            g.setColor(new Color(0xCB, 0xD5, 0xE1));
            g.fillRect(140, 140, 340, 140); // Main Academic Hall
            g.fillRect(120, 340, 120, 340); // Science Block
            g.setColor(new Color(0xE2, 0xE8, 0xF0));
            g.fillRect(460, 620, 280, 140); // Auditorium Stage Apron
            g.fillRect(960, 100, 100, 720); // East Ambulance Road

            g.setColor(new Color(0x33, 0x41, 0x55));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            g.drawString("MAIN ACADEMIC HALL (NORTH)", 175, 215);
            g.drawString("SCIENCE BLOCK", 125, 510);
            g.drawString("AUDITORIUM STAGE DECK", 500, 695);
            g.drawString("EAST ROAD", 968, 460);
            g.drawString("CENTRAL CAMPUS QUADRANGLE — ORTHOGRAPHIC SITE PLAN (120 m x 90 m)", 260, 58);

            // Calibration fiducial ticks
            g.setColor(new Color(0x0F, 0x17, 0x2A));
            g.fillOval(74, 94, 12, 12);
            g.fillOval(1114, 94, 12, 12);
            g.fillOval(74, 814, 12, 12);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to generate campus quadrangle blueprint PNG.", ex);
        }
    }

    private record ZoneIds(
            long entryId,
            long secId,
            long queueId,
            long audienceId,
            long foodId,
            long restId,
            long medId,
            long exitId,
            long emcId
    ) {
    }

    private record V1Ids(
            long stall3Id,
            long barricadeId,
            long pinchEdge1,
            long pinchEdge2,
            long crossingExitEdge,
            long audNode,
            long entryNode
    ) {
    }
}
