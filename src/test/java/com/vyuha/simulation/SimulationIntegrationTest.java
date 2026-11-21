package com.vyuha.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class SimulationIntegrationTest {

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
    private SupportingDocumentService supportingDocumentService;

    @Autowired
    private ObjectMapper objectMapper;

    private Long createdEventId;

    @AfterEach
    void cleanup() {
        simulationRunRepository.deleteAll();
        layoutSnapshotRepository.deleteAll();
        bottleneckFindingRepository.deleteAll();
        pathwayEdgeRepository.deleteAll();
        pathwayNodeRepository.deleteAll();
        conflictFindingRepository.deleteAll();
        layoutRevisionRepository.deleteAll();
        infrastructureElementRepository.deleteAll();
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
        form.setName("Phase 9 Crowd Simulation Event");
        form.setCategory(EventCategory.COLLEGE_FESTIVAL);
        form.setStatus(EventStatus.PLANNING);
        form.setStartDateTime(LocalDateTime.of(2026, 12, 28, 16, 0));
        form.setEndDateTime(LocalDateTime.of(2026, 12, 28, 21, 0));
        form.setExpectedAttendance(2500);
        form.setLocationName("Main Campus Ground");
        Event saved = eventService.createEvent(form, organizer.getId());
        createdEventId = saved.getId();
        return saved;
    }

    @Test
    void enforcesRbacAndRendersOfflineSimulationWorkspaceWithMandatoryDisclaimer() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        UserDetails staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        mockMvc.perform(get("/events/" + event.getId() + "/simulation").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Simplified Planning Simulation")))
                .andExpect(content().string(containsString("relative layout comparison")))
                .andExpect(content().string(containsString("sim-player.js")))
                .andExpect(content().string(containsString("mono")))
                .andExpect(content().string(not(containsString("Stampede-Proof"))))
                .andExpect(content().string(not(containsString("Certified"))))
                .andExpect(content().string(not(containsString("https://"))));

        // SAFETY_REVIEWER can view simulation workspace but cannot create or delete snapshots
        mockMvc.perform(get("/events/" + event.getId() + "/simulation").with(user(reviewer)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/simulation/snapshots")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Reviewer Attempt"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/events/" + event.getId() + "/simulation/snapshots/999")
                        .with(user(reviewer)).with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/events/" + event.getId() + "/simulation/runs/999")
                        .with(user(reviewer)).with(csrf()))
                .andExpect(status().isForbidden());

        // FIELD_STAFF is denied all /events/{id}/simulation routes
        mockMvc.perform(get("/events/" + event.getId() + "/simulation").with(user(staff)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/events/" + event.getId() + "/simulation").with(user(staff)))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportsSnapshotVersioningThumbnailPlaybackAndPairedBeforeAfterComparison() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");

        // 1. Setup boundary, audience zone, entry gate, exit gate, and pathway network with narrow pinch corridor (1.2 m)
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
                                {"zoneCode":"AUD-1","name":"Main Lawn","zoneType":"AUDIENCE","polygonWkt":"POLYGON ((45 25, 85 25, 85 65, 45 65, 45 25))"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long zoneId = objectMapper.readTree(zoneRes.getResponse().getContentAsString())
                .path("data").path("zones").get(0).path("id").asLong();

        MvcResult entryRes = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"ENTRY_GATE","label":"North Entry Gate","centerX":20,"centerY":10,"widthM":8.0,"heightM":2.0,"throughputPersonsPerMin":180}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long entryGateId = objectMapper.readTree(entryRes.getResponse().getContentAsString())
                .path("data").path("lastModifiedElementId").asLong();

        MvcResult exitRes = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"EXIT_GATE","label":"East Exit Gate","centerX":105,"centerY":45,"widthM":6.0,"heightM":2.0,"throughputPersonsPerMin":140}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long exitGateId = objectMapper.readTree(exitRes.getResponse().getContentAsString())
                .path("data").path("lastModifiedElementId").asLong();

        MvcResult seedRes = mockMvc.perform(post("/api/events/" + event.getId() + "/network/seed")
                        .with(user(organizer)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode nodesArr = objectMapper.readTree(seedRes.getResponse().getContentAsString()).path("data").path("nodes");
        long entryNodeId = -1;
        long hubNodeId = -1;
        long exitNodeId = -1;
        for (JsonNode n : nodesArr) {
            if (n.path("linkedInfraId").asLong(-1) == entryGateId) entryNodeId = n.path("id").asLong();
            if (n.path("linkedZoneId").asLong(-1) == zoneId) hubNodeId = n.path("id").asLong();
            if (n.path("linkedInfraId").asLong(-1) == exitGateId) exitNodeId = n.path("id").asLong();
        }
        assertThat(entryNodeId).isPositive();
        assertThat(hubNodeId).isPositive();
        assertThat(exitNodeId).isPositive();

        // Narrow 1.2m entry corridor -> pinch bottleneck
        MvcResult pinchEdgeRes = mockMvc.perform(post("/api/events/" + event.getId() + "/network/edges")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromNodeId":%d,"toNodeId":%d,"label":"Main Entry Pinch","networkLayer":"NORMAL_ENTRY","widthM":1.2,"direction":"BIDIRECTIONAL"}
                                """.formatted(entryNodeId, hubNodeId)))
                .andExpect(status().isOk())
                .andReturn();
        long pinchEdgeId = objectMapper.readTree(pinchEdgeRes.getResponse().getContentAsString())
                .path("data").path("lastModifiedEdgeId").asLong();

        mockMvc.perform(post("/api/events/" + event.getId() + "/network/edges")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromNodeId":%d,"toNodeId":%d,"label":"East Exit Corridor","networkLayer":"NORMAL_EXIT","widthM":4.5,"direction":"BIDIRECTIONAL"}
                                """.formatted(hubNodeId, exitNodeId)))
                .andExpect(status().isOk());

        // 2. Capture Snapshot V1 ("Before — Narrow Pinch") & verify fallback PNG thumbnail
        MvcResult snap1Res = mockMvc.perform(post("/api/events/" + event.getId() + "/simulation/snapshots")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Before — Narrow Pinch V1","notes":"1.2m entry bottleneck"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.versionNumber").value(1))
                .andReturn();
        long snap1Id = objectMapper.readTree(snap1Res.getResponse().getContentAsString())
                .path("data").path("summary").path("id").asLong();

        MvcResult thumbRes = mockMvc.perform(get("/api/events/" + event.getId() + "/simulation/snapshots/" + snap1Id + "/thumbnail")
                        .with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn();
        assertThat(thumbRes.getResponse().getContentAsByteArray().length).isGreaterThan(200);

        // 3. Widen the pinch corridor from 1.2m -> 4.5m and capture Snapshot V2 ("After — Widened Promenade")
        mockMvc.perform(put("/api/events/" + event.getId() + "/network/edges/" + pinchEdgeId)
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Widened Entry Promenade","networkLayer":"NORMAL_ENTRY","widthM":4.5,"direction":"BIDIRECTIONAL","active":true}
                                """))
                .andExpect(status().isOk());

        MvcResult snap2Res = mockMvc.perform(post("/api/events/" + event.getId() + "/simulation/snapshots")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"After — Widened Promenade V2","notes":"Widened to 4.5m"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.versionNumber").value(2))
                .andReturn();
        long snap2Id = objectMapper.readTree(snap2Res.getResponse().getContentAsString())
                .path("data").path("summary").path("id").asLong();

        // 4. Reviewer runs single simulation on V1 and inspects frames
        MvcResult run1Res = mockMvc.perform(post("/api/events/" + event.getId() + "/simulation/runs")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"snapshotId":%d,"scenarioType":"ARRIVAL_RUSH","randomSeed":2026,"agentCount":240,"durationSeconds":300}
                                """.formatted(snap1Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run.randomSeed").value(2026))
                .andExpect(jsonPath("$.data.frameStream.frames").isNotEmpty())
                .andReturn();
        long run1Id = objectMapper.readTree(run1Res.getResponse().getContentAsString())
                .path("data").path("run").path("id").asLong();

        mockMvc.perform(get("/api/events/" + event.getId() + "/simulation/runs/" + run1Id + "/frames")
                        .with(user(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frames[0].t").value(0.0));

        // 5. Run paired Before-and-After comparison across V1 and V2 with identical seed
        MvcResult cmpRes = mockMvc.perform(post("/api/events/" + event.getId() + "/simulation/compare")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"beforeSnapshotId":%d,"afterSnapshotId":%d,"scenarioType":"ARRIVAL_RUSH","randomSeed":2026,"agentCount":240,"durationSeconds":300}
                                """.formatted(snap1Id, snap2Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parityMatched").value(true))
                .andExpect(jsonPath("$.data.peakDensity.trend").value("IMPROVED"))
                .andExpect(jsonPath("$.data.p95TravelTime.trend").value("IMPROVED"))
                .andReturn();

        JsonNode cmpData = objectMapper.readTree(cmpRes.getResponse().getContentAsString()).path("data");
        assertThat(cmpData.path("peakDensity").path("afterValue").asDouble())
                .isLessThan(cmpData.path("peakDensity").path("beforeValue").asDouble());
        assertThat(cmpData.path("p95TravelTime").path("afterValue").asDouble())
                .isLessThan(cmpData.path("p95TravelTime").path("beforeValue").asDouble());

        // 6. Clean deletion of run and snapshot
        mockMvc.perform(delete("/api/events/" + event.getId() + "/simulation/runs/" + run1Id)
                        .with(user(organizer)).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/events/" + event.getId() + "/simulation/snapshots/" + snap1Id)
                        .with(user(organizer)).with(csrf()))
                .andExpect(status().isOk());
    }
}
