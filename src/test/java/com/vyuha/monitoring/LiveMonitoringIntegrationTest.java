package com.vyuha.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.auth.CustomUserDetails;
import com.vyuha.auth.CustomUserDetailsService;
import com.vyuha.capacity.CapacityReviewRepository;
import com.vyuha.capacity.ZoneDensityAssumptionRepository;
import com.vyuha.event.Event;
import com.vyuha.event.EventCategory;
import com.vyuha.event.EventForm;
import com.vyuha.event.EventRepository;
import com.vyuha.event.EventService;
import com.vyuha.event.EventStatus;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.FunctionalZoneRepository;
import com.vyuha.infrastructure.InfrastructureElementRepository;
import com.vyuha.venue.ExclusionAreaRepository;
import com.vyuha.venue.VenuePlanRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveMonitoringIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private VenuePlanRepository venuePlanRepository;

    @Autowired
    private ExclusionAreaRepository exclusionAreaRepository;

    @Autowired
    private FunctionalZoneRepository functionalZoneRepository;

    @Autowired
    private InfrastructureElementRepository infrastructureElementRepository;

    @Autowired
    private CapacityReviewRepository capacityReviewRepository;

    @Autowired
    private ZoneDensityAssumptionRepository assumptionRepository;

    @Autowired
    private AttendeePassRepository passRepository;

    @Autowired
    private OccupancyReadingRepository readingRepository;

    @Autowired
    private MonitoringAlertRuleRepository ruleRepository;

    @Autowired
    private MonitoringAlertEventRepository alertRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        alertRepository.deleteAll();
        ruleRepository.deleteAll();
        readingRepository.deleteAll();
        passRepository.deleteAll();
        assumptionRepository.deleteAll();
        capacityReviewRepository.deleteAll();
        infrastructureElementRepository.deleteAll();
        functionalZoneRepository.deleteAll();
        exclusionAreaRepository.deleteAll();
        venuePlanRepository.deleteAll();
        eventRepository.deleteAll();
    }

    private Event createSampleEvent() {
        CustomUserDetails organizer = (CustomUserDetails) userDetailsService.loadUserByUsername("organizer@vyuha.local");
        EventForm form = new EventForm();
        form.setName("Phase 10 Live Ops Event");
        form.setCategory(EventCategory.CONCERT);
        form.setStatus(EventStatus.LIVE);
        form.setStartDateTime(LocalDateTime.of(2026, 11, 20, 17, 0));
        form.setEndDateTime(LocalDateTime.of(2026, 11, 20, 22, 0));
        form.setExpectedAttendance(1000);
        form.setLocationName("Main Arena");
        return eventService.createEvent(form, organizer.getId());
    }

    @Test
    void enforcesRbacAndRendersOfflineControlCentreAndFieldMonitoring() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        UserDetails staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        mockMvc.perform(get("/events/" + event.getId() + "/live").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Live Event Monitoring Control Centre")))
                .andExpect(content().string(containsString("Practice Simulation")))
                .andExpect(content().string(containsString("Strict Source Segregation")))
                .andExpect(content().string(not(containsString("Stampede-Proof"))))
                .andExpect(content().string(not(containsString("Certified"))))
                .andExpect(content().string(not(containsString("https://"))));

        mockMvc.perform(get("/events/" + event.getId() + "/live").with(user(reviewer)))
                .andExpect(status().isOk());

        // Reviewer can read Control Centre & SSE stream, but cannot mutate counts/passes/rules/practice
        mockMvc.perform(get("/api/events/" + event.getId() + "/live").with(user(reviewer)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/events/" + event.getId() + "/live/stream").with(user(reviewer)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/live/passes/batch")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"count":5,"labelPrefix":"Test"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/events/" + event.getId() + "/live/rules")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warningThresholdPct":75,"criticalThresholdPct":90,"staleAfterMinutes":10}
                                """))
                .andExpect(status().isForbidden());

        // FIELD_STAFF is blocked from /events/** and /api/events/**, allowed on /field and /api/field/**
        mockMvc.perform(get("/events/" + event.getId() + "/live").with(user(staff)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/events/" + event.getId() + "/live").with(user(staff)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/field?eventId=" + event.getId()).with(user(staff)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Manual Zone Count")))
                .andExpect(content().string(containsString("Attendee Pass Scanner")))
                .andExpect(content().string(not(containsString("https://"))));

        mockMvc.perform(get("/api/field/events/" + event.getId() + "/monitoring").with(user(staff)))
                .andExpect(status().isOk());
    }

    @Test
    void fullMonitoringWorkflowSegregatesSourcesPreventsDuplicateScansAndFiresThresholdAndStaleAlerts()
            throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        UserDetails staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        // 1. Setup 100x80 boundary, AUD-1 (10x10m = 100m² @ 1.0 p/m² = 100 cap), and ENTRY_GATE inside AUD-1
        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/boundary")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"coordinateSpace":"METERS","points":[{"x":0,"y":0},{"x":100,"y":0},{"x":100,"y":80},{"x":0,"y":80}]}
                                """))
                .andExpect(status().isOk());

        MvcResult zRes = mockMvc.perform(post("/api/events/" + event.getId() + "/grid/zones")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneCode":"AUD-1","name":"Arena Floor","zoneType":"AUDIENCE","points":[{"x":10,"y":10},{"x":20,"y":10},{"x":20,"y":20},{"x":10,"y":20}]}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long zoneId = objectMapper.readTree(zRes.getResponse().getContentAsString())
                .path("data").path("zones").get(0).path("id").asLong();

        mockMvc.perform(put("/api/events/" + event.getId() + "/capacity/zones/" + zoneId)
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"densityPersonsPerSqm":1.0,"sourceCitation":"Arena Guide Sec 2"}
                                """))
                .andExpect(status().isOk());

        MvcResult gRes = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"ENTRY_GATE","label":"North Turnstile","centerX":15,"centerY":15,"throughputPersonsPerMin":40}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long gateId = objectMapper.readTree(gRes.getResponse().getContentAsString())
                .path("data").path("lastModifiedElementId").asLong();

        // 2. Field staff records MANUAL_STAFF count = 85 (85% of 100 cap -> fires CAPACITY_WARNING HIGH)
        mockMvc.perform(post("/api/field/events/" + event.getId() + "/readings")
                        .with(user(staff)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":%d,"countValue":85,"note":"Manual tally"}
                                """.formatted(zoneId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.manualZones[0].currentCount").value(85))
                .andExpect(jsonPath("$.data.manualZones[0].band").value("HIGH_LOAD"))
                .andExpect(jsonPath("$.data.liveAlerts[0].alertType").value("CAPACITY_WARNING"));

        // 3. Organizer generates batch of 3 unique passes & fetches ZXing QR PNG for pass #2
        MvcResult batchRes = mockMvc.perform(post("/api/events/" + event.getId() + "/live/passes/batch")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"count":3,"labelPrefix":"VIP"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passSummary.totalIssued").value(3))
                .andReturn();

        JsonNode passesNode = objectMapper.readTree(batchRes.getResponse().getContentAsString())
                .path("data").path("recentPasses");
        String pass1Code = passesNode.get(0).path("passCode").asText();
        long pass2Id = passesNode.get(1).path("id").asLong();
        String pass2Code = passesNode.get(1).path("passCode").asText();

        byte[] qrPng = mockMvc.perform(get("/api/events/" + event.getId() + "/live/passes/" + pass2Id + "/qr")
                        .with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(qrPng[0]).isEqualTo((byte) 0x89);

        // 4. Scan Pass #1 CHECK_IN via Field API -> PASS_SCAN=1 while MANUAL_STAFF stays 85 (zero blending)
        mockMvc.perform(post("/api/field/events/" + event.getId() + "/passes/scan")
                        .with(user(staff)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"passCode":"%s","action":"CHECK_IN","gateId":%d,"zoneId":%d}
                                """.formatted(pass1Code, gateId, zoneId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passSummary.checkedInCount").value(1));

        // Verify strict zero blending in Control Centre API
        mockMvc.perform(get("/api/events/" + event.getId() + "/live?mode=LIVE&source=MANUAL_STAFF").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.manualSummary.totalCount").value(85))
                .andExpect(jsonPath("$.data.passScanSummary.totalCount").value(1))
                .andExpect(jsonPath("$.data.activeChannel.totalCount").value(85));

        mockMvc.perform(get("/api/events/" + event.getId() + "/live?mode=LIVE&source=PASS_SCAN").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeChannel.totalCount").value(1));

        // 5. Duplicate CHECK_IN on Pass #1 is rejected with 409 CONFLICT and persists DUPLICATE_PASS_SCAN alert
        mockMvc.perform(post("/api/field/events/" + event.getId() + "/passes/scan")
                        .with(user(staff)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"passCode":"%s","action":"CHECK_IN","gateId":%d}
                                """.formatted(pass1Code, gateId)))
                .andExpect(status().isConflict());

        assertThat(alertRepository.findByEventIdWithDetails(event.getId()))
                .anyMatch(a -> a.getAlertType() == MonitoringAlertType.DUPLICATE_PASS_SCAN);

        // 6. Scan Pass #2 via uploaded ZXing QR PNG -> checkedInCount becomes 2
        MockMultipartFile qrFile = new MockMultipartFile("file", "pass2.png", "image/png", qrPng);
        mockMvc.perform(multipart("/api/field/events/" + event.getId() + "/passes/scan-qr")
                        .file(qrFile)
                        .param("action", "CHECK_IN")
                        .param("gateId", String.valueOf(gateId))
                        .param("zoneId", String.valueOf(zoneId))
                        .with(user(staff)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passSummary.checkedInCount").value(2));

        // Verify Pass #2 status updated
        assertThat(passRepository.findByEventIdAndPassCodeIgnoreCase(event.getId(), pass2Code).orElseThrow().getStatus())
                .isEqualTo(PassStatus.CHECKED_IN);

        // 7. CHECK_OUT Pass #1 -> checkedIn becomes 1; second CHECK_OUT returns 409 CONFLICT
        mockMvc.perform(post("/api/field/events/" + event.getId() + "/passes/scan")
                        .with(user(staff)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"passCode":"%s","action":"CHECK_OUT","gateId":%d}
                                """.formatted(pass1Code, gateId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passSummary.checkedInCount").value(1))
                .andExpect(jsonPath("$.data.passSummary.checkedOutCount").value(1));

        mockMvc.perform(post("/api/field/events/" + event.getId() + "/passes/scan")
                        .with(user(staff)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"passCode":"%s","action":"CHECK_OUT","gateId":%d}
                                """.formatted(pass1Code, gateId)))
                .andExpect(status().isConflict());

        // 8. Reviewer acknowledges an active alert cleanly
        long alertId = alertRepository.findByEventIdWithDetails(event.getId()).get(0).getId();
        mockMvc.perform(post("/api/events/" + event.getId() + "/live/alerts/" + alertId + "/acknowledge")
                        .with(user(reviewer)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeAlerts[?(@.id==" + alertId + ")].acknowledgedBy").isNotEmpty());

        // 9. Staleness test: seed a MANUAL_STAFF reading aged 25 minutes (> 15 min rule) and run check-staleness
        FunctionalZone zone = functionalZoneRepository.findById(zoneId).orElseThrow();
        OccupancyReading agedReading = new OccupancyReading();
        agedReading.setEvent(event);
        agedReading.setZone(zone);
        agedReading.setCountValue(50);
        agedReading.setDataSource(OccupancyDataSource.MANUAL_STAFF);
        agedReading.setRecordedBy("Earlier Staff");
        agedReading.setRecordedAt(Instant.now().minus(Duration.ofMinutes(25)));
        // Clear newer manual reading so aged reading is the latest for MANUAL_STAFF
        readingRepository.findByEventIdAndDataSourceOrderByRecordedAtDescIdDesc(
                event.getId(), OccupancyDataSource.MANUAL_STAFF).forEach(readingRepository::delete);
        readingRepository.saveAndFlush(agedReading);

        mockMvc.perform(post("/api/events/" + event.getId() + "/live/check-staleness")
                        .with(user(reviewer)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.zones[0].stale").value(true))
                .andExpect(jsonPath("$.data.zones[0].band").value("STALE"))
                .andExpect(jsonPath("$.data.activeAlerts[?(@.alertType=='STALE_DATA')]").isNotEmpty());

        // 10. Practice Simulation channel writes isolated PRACTICE_SIMULATION counts and clears without touching live counts
        mockMvc.perform(post("/api/events/" + event.getId() + "/live/practice/step")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"frameIndex":4,"resetFirst":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.controlCentre.mode").value("PRACTICE"))
                .andExpect(jsonPath("$.data.controlCentre.activeSource").value("PRACTICE_SIMULATION"))
                .andExpect(jsonPath("$.data.controlCentre.practiceSummary.totalCount").value(99))
                .andExpect(jsonPath("$.data.controlCentre.manualSummary.totalCount").value(50))
                .andExpect(jsonPath("$.data.controlCentre.passScanSummary.totalCount").value(1));

        mockMvc.perform(delete("/api/events/" + event.getId() + "/live/practice")
                        .with(user(organizer)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.practiceSummary.totalCount").value(0))
                .andExpect(jsonPath("$.data.manualSummary.totalCount").value(50))
                .andExpect(jsonPath("$.data.passScanSummary.totalCount").value(1));

        // 11. Event deletion cascades cleanly across all 4 Phase 10 tables
        mockMvc.perform(post("/events/" + event.getId() + "/delete").with(user(organizer)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(passRepository.count()).isZero();
        assertThat(readingRepository.count()).isZero();
        assertThat(ruleRepository.count()).isZero();
        assertThat(alertRepository.count()).isZero();
    }
}
