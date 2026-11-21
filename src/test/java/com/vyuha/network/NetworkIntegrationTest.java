package com.vyuha.network;

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
import com.vyuha.grid.FunctionalZoneRepository;
import com.vyuha.infrastructure.ConflictFindingRepository;
import com.vyuha.infrastructure.InfrastructureElementRepository;
import com.vyuha.infrastructure.LayoutRevisionRepository;
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
class NetworkIntegrationTest {

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
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
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
    }

    private Event createSampleEvent() {
        CustomUserDetails organizer = (CustomUserDetails) userDetailsService.loadUserByUsername("organizer@vyuha.local");
        EventForm form = new EventForm();
        form.setName("Phase 6 Movement Network Festival");
        form.setCategory(EventCategory.COLLEGE_FESTIVAL);
        form.setStatus(EventStatus.PLANNING);
        form.setStartDateTime(LocalDateTime.of(2026, 12, 20, 16, 0));
        form.setEndDateTime(LocalDateTime.of(2026, 12, 20, 22, 0));
        form.setExpectedAttendance(1800);
        form.setLocationName("Quadrangle Ground");
        return eventService.createEvent(form, organizer.getId());
    }

    @Test
    void enforcesRbacAndRendersOfflinePathwaysModeWithoutCertificationLanguage() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        UserDetails staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        mockMvc.perform(get("/events/" + event.getId() + "/venue?mode=pathways").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("4. Pathways")))
                .andExpect(content().string(containsString("Auto-Seed Nodes from Layout")))
                .andExpect(content().string(containsString("network-editor.js")))
                .andExpect(content().string(containsString("Layout Screening Warning vs. Professional Safety Verdict")))
                .andExpect(content().string(not(containsString("Stampede-Proof"))))
                .andExpect(content().string(not(containsString("Certified"))))
                .andExpect(content().string(not(containsString("https://"))));

        // Earlier Venue Plan modes now link cleanly to 4. Pathways
        mockMvc.perform(get("/events/" + event.getId() + "/venue?mode=infrastructure").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mode=pathways")));

        // Reviewer opens Pathways in read-only mode
        mockMvc.perform(get("/events/" + event.getId() + "/venue?mode=pathways").with(user(reviewer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reviewer Read-Only")));

        // FIELD_STAFF denied
        mockMvc.perform(get("/events/" + event.getId() + "/venue?mode=pathways").with(user(staff)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/events/" + event.getId() + "/network").with(user(staff)))
                .andExpect(status().isForbidden());

        // SAFETY_REVIEWER cannot mutate network
        mockMvc.perform(post("/api/events/" + event.getId() + "/network/seed")
                        .with(user(reviewer))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/events/" + event.getId() + "/network/nodes")
                        .with(user(reviewer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeType":"INTERSECTION","x":20,"y":20}
                                """))
                .andExpect(status().isForbidden());

        // Organizer cannot submit safety reviewer note on bottleneck finding
        MvcResult nodeRes = mockMvc.perform(post("/api/events/" + event.getId() + "/network/nodes")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeType":"ZONE_HUB","label":"Isolated Hall","x":40,"y":40}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeFindings.length()").value(2))
                .andReturn();

        long findingId = objectMapper.readTree(nodeRes.getResponse().getContentAsString())
                .path("data").path("activeFindings").get(0).path("id").asLong();

        mockMvc.perform(post("/api/events/" + event.getId() + "/network/findings/" + findingId + "/review")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewStatus":"REVIEWER_NOTED","reviewerNote":"Unauthorized"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void fullNetworkSeedingBottleneckScreeningResolutionHistorySyncAndCascadeDelete() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");

        // 1. Setup 100m x 80m boundary + AUD-1 zone + Entry Gate, Exit Gate, Emergency Exit
        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/boundary")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"coordinateSpace":"METERS","points":[{"x":0,"y":0},{"x":100,"y":0},{"x":100,"y":80},{"x":0,"y":80}]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/grid/zones")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneCode":"AUD-1","name":"Main Lawn","zoneType":"AUDIENCE","points":[{"x":30,"y":20},{"x":70,"y":20},{"x":70,"y":60},{"x":30,"y":60}]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"ENTRY_GATE","label":"North Gate","centerX":10,"centerY":40,"throughputPersonsPerMin":60}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"EXIT_GATE","label":"South Exit","centerX":90,"centerY":40,"throughputPersonsPerMin":80}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"EMERGENCY_EXIT","label":"East Emergency Exit","centerX":50,"centerY":75,"throughputPersonsPerMin":100}
                                """))
                .andExpect(status().isOk());

        // 2. Idempotent Auto-Seed creates 4 nodes (3 gates + 1 zone hub); re-seeding keeps count = 4
        MvcResult seedRes = mockMvc.perform(post("/api/events/" + event.getId() + "/network/seed")
                        .with(user(organizer)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(4))
                .andReturn();

        mockMvc.perform(post("/api/events/" + event.getId() + "/network/seed")
                        .with(user(organizer)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(4));

        JsonNode seedNodes = objectMapper.readTree(seedRes.getResponse().getContentAsString())
                .path("data").path("nodes");
        long entryNodeId = 0, exitNodeId = 0, emNodeId = 0, audNodeId = 0;
        String entryUid = null, exitUid = null, emUid = null, audUid = null;
        for (JsonNode n : seedNodes) {
            switch (n.path("nodeType").asText()) {
                case "ENTRY_GATE" -> { entryNodeId = n.path("id").asLong(); entryUid = n.path("nodeUid").asText(); }
                case "EXIT_GATE" -> { exitNodeId = n.path("id").asLong(); exitUid = n.path("nodeUid").asText(); }
                case "EMERGENCY_EXIT" -> { emNodeId = n.path("id").asLong(); emUid = n.path("nodeUid").asText(); }
                case "ZONE_HUB" -> { audNodeId = n.path("id").asLong(); audUid = n.path("nodeUid").asText(); }
            }
        }
        assertThat(entryNodeId).isPositive();
        assertThat(audNodeId).isPositive();

        // 3. Connect narrow 1.8m NORMAL_ENTRY edge (triggers NARROW_WALKWAY HIGH), plus normal exit & emergency access
        MvcResult narrowEdgeRes = mockMvc.perform(post("/api/events/" + event.getId() + "/network/edges")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromNodeId":%d,"toNodeId":%d,"label":"Entry Funnel","networkLayer":"NORMAL_ENTRY","widthM":1.8,"direction":"ONE_WAY"}
                                """.formatted(entryNodeId, audNodeId)))
                .andExpect(status().isOk())
                .andReturn();
        long entryEdgeId = objectMapper.readTree(narrowEdgeRes.getResponse().getContentAsString())
                .path("data").path("lastModifiedEdgeId").asLong();

        mockMvc.perform(post("/api/events/" + event.getId() + "/network/edges")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromNodeId":%d,"toNodeId":%d,"label":"Exit Path","networkLayer":"NORMAL_EXIT","widthM":4.0,"direction":"ONE_WAY"}
                                """.formatted(audNodeId, exitNodeId)))
                .andExpect(status().isOk());

        MvcResult fullNetRes = mockMvc.perform(post("/api/events/" + event.getId() + "/network/edges")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromNodeId":%d,"toNodeId":%d,"label":"Emergency Lane","networkLayer":"EMERGENCY_ACCESS","widthM":4.0,"direction":"BIDIRECTIONAL"}
                                """.formatted(audNodeId, emNodeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeFindings.length()").value(1))
                .andExpect(jsonPath("$.data.activeFindings[0].findingType").value("NARROW_WALKWAY"))
                .andExpect(jsonPath("$.data.summary.connectedComponents").value(1))
                .andReturn();

        long narrowFindingId = objectMapper.readTree(fullNetRes.getResponse().getContentAsString())
                .path("data").path("activeFindings").get(0).path("id").asLong();

        // 4. Reviewer documents note on the narrow walkway finding
        mockMvc.perform(post("/api/events/" + event.getId() + "/network/findings/" + narrowFindingId + "/review")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewStatus":"REVIEWER_NOTED","reviewerNote":"Widen Entry Funnel to >= 3.5 m"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeFindings[0].reviewStatus").value("REVIEWER_NOTED"));

        // 5. Organizer moves Entry Gate node slightly and widens Entry Funnel to 3.5 m -> length recomputes, finding resolves into history
        mockMvc.perform(put("/api/events/" + event.getId() + "/network/nodes/" + entryNodeId)
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"x":20,"y":40}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.edges[0].lengthM").value(30.0));

        mockMvc.perform(put("/api/events/" + event.getId() + "/network/edges/" + entryEdgeId)
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"widthM":3.5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeFindings.length()").value(0))
                .andExpect(jsonPath("$.data.resolvedHistoryFindings.length()").value(1))
                .andExpect(jsonPath("$.data.resolvedHistoryFindings[0].reviewerNote")
                        .value("Widen Entry Funnel to >= 3.5 m"));

        // 6. Sync layout (Undo simulation) restores narrow 1.8m edge and cleanly reopens finding
        mockMvc.perform(put("/api/events/" + event.getId() + "/network/layout")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nodes": [
                                    {"nodeUid":"%s","nodeType":"ENTRY_GATE","label":"North Gate","x":10,"y":40},
                                    {"nodeUid":"%s","nodeType":"ZONE_HUB","label":"Main Lawn","x":50,"y":40},
                                    {"nodeUid":"%s","nodeType":"EXIT_GATE","label":"South Exit","x":90,"y":40},
                                    {"nodeUid":"%s","nodeType":"EMERGENCY_EXIT","label":"East Emergency Exit","x":50,"y":75}
                                  ],
                                  "edges": [
                                    {"edgeUid":"e-restored-1","fromNodeUid":"%s","toNodeUid":"%s","networkLayer":"NORMAL_ENTRY","widthM":1.8,"direction":"ONE_WAY"},
                                    {"edgeUid":"e-restored-2","fromNodeUid":"%s","toNodeUid":"%s","networkLayer":"NORMAL_EXIT","widthM":4.0,"direction":"ONE_WAY"},
                                    {"edgeUid":"e-restored-3","fromNodeUid":"%s","toNodeUid":"%s","networkLayer":"EMERGENCY_ACCESS","widthM":4.0,"direction":"BIDIRECTIONAL"}
                                  ]
                                }
                                """.formatted(entryUid, audUid, exitUid, emUid,
                                entryUid, audUid, audUid, exitUid, audUid, emUid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeFindings.length()").value(1))
                .andExpect(jsonPath("$.data.activeFindings[0].findingType").value("NARROW_WALKWAY"));

        // 7. Deleting a node removes its incident edges cleanly
        MvcResult afterSync = mockMvc.perform(get("/api/events/" + event.getId() + "/network").with(user(organizer)))
                .andExpect(status().isOk())
                .andReturn();
        long restoredEntryId = objectMapper.readTree(afterSync.getResponse().getContentAsString())
                .path("data").path("nodes").get(0).path("id").asLong();

        mockMvc.perform(delete("/api/events/" + event.getId() + "/network/nodes/" + restoredEntryId)
                        .with(user(organizer)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(3))
                .andExpect(jsonPath("$.data.edges.length()").value(2));
    }
}
