package com.vyuha.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.vyuha.event.SupportingDocumentService;
import com.vyuha.grid.FunctionalZoneRepository;
import com.vyuha.infrastructure.ConflictFindingRepository;
import com.vyuha.infrastructure.InfrastructureElementRepository;
import com.vyuha.infrastructure.LayoutRevisionRepository;
import com.vyuha.network.BottleneckFindingRepository;
import com.vyuha.network.PathwayEdgeRepository;
import com.vyuha.network.PathwayNodeRepository;
import com.vyuha.simulation.LayoutSnapshotRepository;
import com.vyuha.simulation.SimulationRunRepository;
import com.vyuha.venue.ExclusionAreaRepository;
import com.vyuha.venue.VenuePlanRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanningAssistantIntegrationTest {

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
    private CapacityReviewRepository capacityReviewRepository;

    @Autowired
    private ZoneDensityAssumptionRepository assumptionRepository;

    @Autowired
    private InfrastructureElementRepository infrastructureElementRepository;

    @Autowired
    private LayoutRevisionRepository layoutRevisionRepository;

    @Autowired
    private ConflictFindingRepository conflictFindingRepository;

    @Autowired
    private PathwayEdgeRepository pathwayEdgeRepository;

    @Autowired
    private PathwayNodeRepository pathwayNodeRepository;

    @Autowired
    private BottleneckFindingRepository bottleneckFindingRepository;

    @Autowired
    private LayoutSnapshotRepository layoutSnapshotRepository;

    @Autowired
    private SimulationRunRepository simulationRunRepository;

    @Autowired
    private AssistantInteractionLogRepository assistantLogRepository;

    @Autowired
    private SupportingDocumentService supportingDocumentService;

    @Autowired
    private ObjectMapper objectMapper;

    private Long createdEventId;

    @AfterEach
    void cleanup() {
        assistantLogRepository.deleteAll();
        simulationRunRepository.deleteAll();
        layoutSnapshotRepository.deleteAll();
        bottleneckFindingRepository.deleteAll();
        pathwayEdgeRepository.deleteAll();
        pathwayNodeRepository.deleteAll();
        conflictFindingRepository.deleteAll();
        layoutRevisionRepository.deleteAll();
        infrastructureElementRepository.deleteAll();
        assumptionRepository.deleteAll();
        capacityReviewRepository.deleteAll();
        functionalZoneRepository.deleteAll();
        exclusionAreaRepository.deleteAll();
        venuePlanRepository.deleteAll();
        eventRepository.deleteAll();
        if (createdEventId != null) {
            supportingDocumentService.deleteEventDirectory(createdEventId);
            createdEventId = null;
        }
    }

    private Event createSampleEvent() {
        CustomUserDetails organizer = (CustomUserDetails) userDetailsService.loadUserByUsername("organizer@vyuha.local");
        EventForm form = new EventForm();
        form.setName("Phase 11 Planning Assistant Event");
        form.setCategory(EventCategory.COLLEGE_FESTIVAL);
        form.setStatus(EventStatus.PLANNING);
        form.setStartDateTime(LocalDateTime.of(2026, 12, 30, 16, 0));
        form.setEndDateTime(LocalDateTime.of(2026, 12, 30, 22, 0));
        form.setExpectedAttendance(1800);
        form.setLocationName("North Quadrangle");
        Event saved = eventService.createEvent(form, organizer.getId());
        createdEventId = saved.getId();
        return saved;
    }

    @Test
    void enforcesGuidelinesAccessAcrossAllRolesAndBlocksFieldStaffFromEventAssistantApi() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        UserDetails staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        // All authenticated roles (including FIELD_STAFF) can search the offline guideline reference
        mockMvc.perform(get("/guidelines").param("q", "ambulance lane").with(user(staff)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Safety Guidelines Reference")))
                .andExpect(content().string(containsString(AssistantConstants.ATTRIBUTION_TAG)))
                .andExpect(content().string(containsString("3.5")))
                .andExpect(content().string(not(containsString("https://"))));

        mockMvc.perform(get("/api/guidelines/search").param("q", "contra-flow").with(user(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.attributionTag").value(AssistantConstants.ATTRIBUTION_TAG))
                .andExpect(jsonPath("$.data.hits[0].sectionNumber").value("Section 3.2"));

        // FIELD_STAFF is denied event assistant endpoints
        mockMvc.perform(post("/api/events/" + event.getId() + "/assistant/explain")
                        .with(user(staff)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // Event overview, infrastructure, and simulation pages render Planning Assistant drawer without external CDNs
        mockMvc.perform(get("/events/" + event.getId()).with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("planningAssistantDrawer")))
                .andExpect(content().string(containsString("assistant-drawer.js")))
                .andExpect(content().string(containsString(AssistantConstants.ATTRIBUTION_TAG)))
                .andExpect(content().string(not(containsString("https://"))));

        mockMvc.perform(get("/events/" + event.getId() + "/venue").param("mode", "infrastructure").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("planningAssistantDrawer")))
                .andExpect(content().string(containsString("data-assistant-toggle=\"true\"")))
                .andExpect(content().string(not(containsString("https://"))));

        mockMvc.perform(get("/events/" + event.getId() + "/simulation").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("planningAssistantDrawer")))
                .andExpect(content().string(containsString("data-assistant-toggle=\"true\"")))
                .andExpect(content().string(not(containsString("https://"))));
    }

    @Test
    void explainsRealFindingsWithCitationsAndExecutesWhatIfOnlyAfterExplicitConfirmationWithoutMutatingLayout()
            throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");

        // 1. Setup boundary, audience zone, ambulance lane + intruding food stall (conflict),
        //    entry gate, two exits (Exit Gate A, Exit Gate B), and narrow corridor C2 (bottleneck).
        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/boundary")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"polygonWkt":"POLYGON ((0 0, 120 0, 120 90, 0 90, 0 0))"}
                                """))
                .andExpect(status().isOk());

        MvcResult zoneRes = mockMvc.perform(post("/api/events/" + event.getId() + "/grid/zones")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneCode":"AUD-1","name":"Main Lawn","zoneType":"AUDIENCE","polygonWkt":"POLYGON ((40 25, 80 25, 80 65, 40 65, 40 25))"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long zoneId = objectMapper.readTree(zoneRes.getResponse().getContentAsString())
                .path("data").path("zones").get(0).path("id").asLong();

        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"AMBULANCE_LANE","label":"East Ambulance Lane","centerX":95,"centerY":45,"widthM":4.0,"heightM":40.0}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"FOOD_STALL","label":"Food Stall #3","centerX":95,"centerY":45,"widthM":4.0,"heightM":4.0}
                                """))
                .andExpect(status().isOk());

        MvcResult entryRes = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"ENTRY_GATE","label":"North Gate","centerX":15,"centerY":45,"widthM":6.0,"heightM":2.0,"throughputPersonsPerMin":180}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long entryGateId = objectMapper.readTree(entryRes.getResponse().getContentAsString())
                .path("data").path("lastModifiedElementId").asLong();

        MvcResult exitARes = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"EXIT_GATE","label":"Exit Gate A","centerX":108,"centerY":20,"widthM":5.0,"heightM":2.0,"throughputPersonsPerMin":140}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long exitAId = objectMapper.readTree(exitARes.getResponse().getContentAsString())
                .path("data").path("lastModifiedElementId").asLong();

        MvcResult exitBRes = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"EXIT_GATE","label":"Exit Gate B","centerX":108,"centerY":70,"widthM":5.0,"heightM":2.0,"throughputPersonsPerMin":140}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long exitBId = objectMapper.readTree(exitBRes.getResponse().getContentAsString())
                .path("data").path("lastModifiedElementId").asLong();

        MvcResult seedRes = mockMvc.perform(post("/api/events/" + event.getId() + "/network/seed")
                        .with(user(organizer)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode nodesArr = objectMapper.readTree(seedRes.getResponse().getContentAsString()).path("data").path("nodes");
        long entryNodeId = -1;
        long hubNodeId = -1;
        long exitANodeId = -1;
        long exitBNodeId = -1;
        for (JsonNode n : nodesArr) {
            long li = n.path("linkedInfraId").asLong(-1);
            if (li == entryGateId) entryNodeId = n.path("id").asLong();
            if (n.path("linkedZoneId").asLong(-1) == zoneId) hubNodeId = n.path("id").asLong();
            if (li == exitAId) exitANodeId = n.path("id").asLong();
            if (li == exitBId) exitBNodeId = n.path("id").asLong();
        }
        assertThat(entryNodeId).isPositive();
        assertThat(hubNodeId).isPositive();
        assertThat(exitANodeId).isPositive();
        assertThat(exitBNodeId).isPositive();

        mockMvc.perform(post("/api/events/" + event.getId() + "/network/edges")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromNodeId":%d,"toNodeId":%d,"label":"North Approach","networkLayer":"NORMAL_ENTRY","widthM":4.0,"direction":"BIDIRECTIONAL"}
                                """.formatted(entryNodeId, hubNodeId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/network/edges")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromNodeId":%d,"toNodeId":%d,"label":"Main Corridor C2","networkLayer":"NORMAL_EXIT","widthM":1.4,"direction":"BIDIRECTIONAL"}
                                """.formatted(hubNodeId, exitANodeId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/network/edges")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromNodeId":%d,"toNodeId":%d,"label":"South Exit Route","networkLayer":"NORMAL_EXIT","widthM":4.0,"direction":"BIDIRECTIONAL"}
                                """.formatted(hubNodeId, exitBNodeId)))
                .andExpect(status().isOk());

        long elementCountBefore = infrastructureElementRepository.count();
        long edgeCountBefore = pathwayEdgeRepository.count();
        long zoneCountBefore = functionalZoneRepository.count();

        // 2. Explain Findings (read-only, SAFETY_REVIEWER allowed)
        MvcResult explainRes = mockMvc.perform(post("/api/events/" + event.getId() + "/assistant/explain")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"focusArea":"ALL","prompt":"Explain our conflicts and bottlenecks"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributionTag").value(AssistantConstants.ATTRIBUTION_TAG))
                .andExpect(jsonPath("$.data.verifiedMetrics.activeConflictCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.verifiedMetrics.activeBottleneckCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andReturn();

        JsonNode explainData = objectMapper.readTree(explainRes.getResponse().getContentAsString()).path("data");
        assertThat(explainData.path("recommendations").toString()).contains("Food Stall #3");
        assertThat(explainData.path("recommendations").toString()).contains("Main Corridor C2");
        assertThat(explainData.path("citations")).isNotEmpty();

        // Layout counts are untouched after explain
        assertThat(infrastructureElementRepository.count()).isEqualTo(elementCountBefore);
        assertThat(pathwayEdgeRepository.count()).isEqualTo(edgeCountBefore);
        assertThat(functionalZoneRepository.count()).isEqualTo(zoneCountBefore);
        assertThat(simulationRunRepository.count()).isZero();

        // 3. Preview What-If (must NOT run simulation yet)
        MvcResult previewRes = mockMvc.perform(post("/api/events/" + event.getId() + "/assistant/what-if/preview")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"What happens if Exit Gate B closes during a sudden arrival rush?","agentCount":120,"durationSeconds":180,"randomSeed":77}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributionTag").value(AssistantConstants.ATTRIBUTION_TAG))
                .andExpect(jsonPath("$.data.requiresConfirmation").value(true))
                .andExpect(jsonPath("$.data.parsedScenarioType").value("CLOSED_EXIT"))
                .andExpect(jsonPath("$.data.matchedTargetLabel").value("Exit Gate B"))
                .andReturn();

        assertThat(simulationRunRepository.count()).isZero();
        JsonNode previewData = objectMapper.readTree(previewRes.getResponse().getContentAsString()).path("data");
        long previewLogId = previewData.path("interactionLogId").asLong();
        String targetExitUid = previewData.path("proposedConfig").path("targetExitNodeUid").asText();

        // 4. Reject confirm when confirmed=false
        mockMvc.perform(post("/api/events/" + event.getId() + "/assistant/what-if/confirm")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"interactionLogId":%d,"confirmed":false,"scenarioType":"CLOSED_EXIT","targetExitNodeUid":"%s","targetExitInfraId":%d,"agentCount":120,"durationSeconds":180,"randomSeed":77}
                                """.formatted(previewLogId, targetExitUid, exitBId)))
                .andExpect(status().isBadRequest());
        assertThat(simulationRunRepository.count()).isZero();

        // 5. Confirm & Run -> executes real Java simulation + baseline comparison + zero layout mutation
        mockMvc.perform(post("/api/events/" + event.getId() + "/assistant/what-if/confirm")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"interactionLogId":%d,"confirmed":true,"scenarioType":"CLOSED_EXIT","targetExitNodeUid":"%s","targetExitInfraId":%d,"agentCount":120,"durationSeconds":180,"randomSeed":77}
                                """.formatted(previewLogId, targetExitUid, exitBId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributionTag").value(AssistantConstants.ATTRIBUTION_TAG))
                .andExpect(jsonPath("$.data.disclaimer").value(containsString("Simplified Planning Simulation")))
                .andExpect(jsonPath("$.data.whatIfRun.scenarioType").value("CLOSED_EXIT"))
                .andExpect(jsonPath("$.data.baselineRun.scenarioType").value("NORMAL_OPERATIONS"))
                .andExpect(jsonPath("$.data.whatIfRun.randomSeed").value(77))
                .andExpect(jsonPath("$.data.baselineRun.randomSeed").value(77))
                .andExpect(jsonPath("$.data.whatIfRun.agentCount").value(120))
                .andExpect(jsonPath("$.data.baselineRun.agentCount").value(120))
                .andExpect(jsonPath("$.data.comparison.peakDensity.beforeValue").isNumber())
                .andExpect(jsonPath("$.data.comparison.peakDensity.afterValue").isNumber())
                .andExpect(jsonPath("$.data.replayUrl").value(containsString("/simulation?runId=")))
                .andExpect(jsonPath("$.data.compareUrl").value(containsString("beforeRunId=")));

        assertThat(simulationRunRepository.count()).isEqualTo(2);
        assertThat(infrastructureElementRepository.count()).isEqualTo(elementCountBefore);
        assertThat(pathwayEdgeRepository.count()).isEqualTo(edgeCountBefore);
        assertThat(functionalZoneRepository.count()).isEqualTo(zoneCountBefore);
        assertThat(assistantLogRepository.findById(previewLogId).orElseThrow().isConfirmedByUser()).isTrue();

        // 6. History endpoint records EXPLAIN + WHAT_IF_PREVIEW + WHAT_IF_EXECUTED
        mockMvc.perform(get("/api/events/" + event.getId() + "/assistant/history").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }
}
