package com.vyuha.infrastructure;

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
class InfrastructureIntegrationTest {

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
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
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
        form.setName("Phase 5 Infrastructure Campus Event");
        form.setCategory(EventCategory.COLLEGE_FESTIVAL);
        form.setStatus(EventStatus.PLANNING);
        form.setStartDateTime(LocalDateTime.of(2026, 12, 18, 16, 0));
        form.setEndDateTime(LocalDateTime.of(2026, 12, 18, 22, 0));
        form.setExpectedAttendance(2500);
        form.setLocationName("Quadrangle Ground");
        return eventService.createEvent(form, organizer.getId());
    }

    @Test
    void enforcesRbacAndRendersOfflineInfrastructureModeWithoutCertificationLanguage() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        UserDetails staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        // Organizer renders Infrastructure mode cleanly offline
        mockMvc.perform(get("/events/" + event.getId() + "/venue?mode=infrastructure").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3. Infrastructure")))
                .andExpect(content().string(containsString("Place Infrastructure")))
                .andExpect(content().string(containsString("infra-editor.js")))
                .andExpect(content().string(not(containsString("Stampede-Proof"))))
                .andExpect(content().string(not(containsString("Certified"))))
                .andExpect(content().string(not(containsString("https://"))));

        // Site Plan and Zones pages now link to Infrastructure mode while Pathways remains disabled
        mockMvc.perform(get("/events/" + event.getId() + "/venue?mode=site").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mode=infrastructure")));

        // Reviewer opens Infrastructure in read-only mode
        mockMvc.perform(get("/events/" + event.getId() + "/venue?mode=infrastructure").with(user(reviewer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reviewer Read-Only")));

        // FIELD_STAFF denied
        mockMvc.perform(get("/events/" + event.getId() + "/venue?mode=infrastructure").with(user(staff)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/events/" + event.getId() + "/infrastructure").with(user(staff)))
                .andExpect(status().isForbidden());

        // SAFETY_REVIEWER cannot mutate elements or invoke undo/redo
        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(reviewer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"FOOD_STALL","centerX":20,"centerY":20}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/undo")
                        .with(user(reviewer))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // Organizer places an element creating a conflict (out of bounds after boundary), cannot submit reviewer note
        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/boundary")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "coordinateSpace": "METERS",
                                  "points": [
                                    {"x": 0, "y": 0},
                                    {"x": 80, "y": 0},
                                    {"x": 80, "y": 60},
                                    {"x": 0, "y": 60}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        MvcResult placedRes = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"FOOD_STALL","label":"Outer Stall","centerX":79,"centerY":30}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeFindings.length()").value(1))
                .andReturn();

        long findingId = objectMapper.readTree(placedRes.getResponse().getContentAsString())
                .path("data").path("activeFindings").get(0).path("id").asLong();

        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/findings/" + findingId + "/review")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewStatus":"REVIEWER_NOTED","reviewerNote":"Unauthorized"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void fullPlacementUndoRedoAndConflictReviewHistoryLifecycle() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");

        // 1. Setup 100m x 80m venue boundary + AUD-1 walkable zone
        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/boundary")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "coordinateSpace": "METERS",
                                  "points": [
                                    {"x": 0, "y": 0},
                                    {"x": 100, "y": 0},
                                    {"x": 100, "y": 80},
                                    {"x": 0, "y": 80}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/grid/zones")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "zoneCode": "AUD-1",
                                  "name": "Main Audience",
                                  "zoneType": "AUDIENCE",
                                  "points": [
                                    {"x": 10, "y": 10},
                                    {"x": 60, "y": 10},
                                    {"x": 60, "y": 60},
                                    {"x": 10, "y": 60}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        // 2. Place East Ambulance Lane at (80, 40) 4m x 40m -> Rev 1
        MvcResult laneRes = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "elementType": "AMBULANCE_LANE",
                                  "label": "East Ambulance Lane",
                                  "centerX": 80,
                                  "centerY": 40,
                                  "widthM": 4,
                                  "heightM": 40,
                                  "rotationDeg": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.canUndo").value(true))
                .andExpect(jsonPath("$.data.history.currentRevisionNumber").value(1))
                .andReturn();
        assertThat(objectMapper.readTree(laneRes.getResponse().getContentAsString())
                .path("data").path("activeFindings")).isEmpty();

        // 3. Place Food Stall intruding into East Ambulance Lane at (79, 40) -> CRITICAL finding, Rev 2
        MvcResult stallRes = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "elementType": "FOOD_STALL",
                                  "label": "Food Stall 3",
                                  "centerX": 79,
                                  "centerY": 40,
                                  "widthM": 4,
                                  "heightM": 3,
                                  "rotationDeg": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.criticalCount").value(1))
                .andExpect(jsonPath("$.data.activeFindings[0].conflictType").value("EMERGENCY_LANE_INTRUSION"))
                .andExpect(jsonPath("$.data.history.currentRevisionNumber").value(2))
                .andReturn();

        JsonNode stallJson = objectMapper.readTree(stallRes.getResponse().getContentAsString()).path("data");
        long stallId = stallJson.path("lastModifiedElementId").asLong();
        long findingId = stallJson.path("activeFindings").get(0).path("id").asLong();

        // 4. Reviewer documents note on active finding
        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/findings/" + findingId + "/review")
                        .with(user(reviewer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reviewStatus": "REVIEWER_NOTED",
                                  "reviewerNote": "Relocate Food Stall 3 west of ambulance lane"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeFindings[0].reviewStatus").value("REVIEWER_NOTED"))
                .andExpect(jsonPath("$.data.activeFindings[0].reviewerNote").value("Relocate Food Stall 3 west of ambulance lane"));

        // 5. Organizer moves Food Stall 3 clear to (70, 40) -> active findings = 0, history preserved, Rev 3
        mockMvc.perform(put("/api/events/" + event.getId() + "/infrastructure/elements/" + stallId)
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "label": "Food Stall 3",
                                  "centerX": 70,
                                  "centerY": 40,
                                  "widthM": 4,
                                  "heightM": 3,
                                  "rotationDeg": 0,
                                  "commandType": "MOVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeFindings.length()").value(0))
                .andExpect(jsonPath("$.data.resolvedHistoryFindings.length()").value(1))
                .andExpect(jsonPath("$.data.resolvedHistoryFindings[0].reviewerNote")
                        .value("Relocate Food Stall 3 west of ambulance lane"))
                .andExpect(jsonPath("$.data.history.currentRevisionNumber").value(3));

        // 6. Duplicate Food Stall 3 -> Rev 4 (3 total elements)
        MvcResult dupRes = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements/" + stallId + "/duplicate")
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.elements.length()").value(3))
                .andExpect(jsonPath("$.data.history.currentRevisionNumber").value(4))
                .andReturn();
        long copyId = objectMapper.readTree(dupRes.getResponse().getContentAsString())
                .path("data").path("lastModifiedElementId").asLong();

        // 7. Rotate duplicated stall by 45 deg -> Rev 5
        mockMvc.perform(put("/api/events/" + event.getId() + "/infrastructure/elements/" + copyId)
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "label": "Food Stall 3 (Copy)",
                                  "centerX": 72,
                                  "centerY": 42,
                                  "widthM": 4,
                                  "heightM": 3,
                                  "rotationDeg": 45,
                                  "commandType": "ROTATE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.currentRevisionNumber").value(5));

        // 8. Undo back to Rev 4 (0 deg), Undo back to Rev 3 (2 elements), Undo back to Rev 2 (intrusion reopens!)
        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/undo")
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.currentRevisionNumber").value(4))
                .andExpect(jsonPath("$.data.history.canRedo").value(true));

        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/undo")
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.elements.length()").value(2))
                .andExpect(jsonPath("$.data.history.currentRevisionNumber").value(3));

        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/undo")
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.currentRevisionNumber").value(2))
                .andExpect(jsonPath("$.data.activeFindings.length()").value(1))
                .andExpect(jsonPath("$.data.activeFindings[0].conflictType").value("EMERGENCY_LANE_INTRUSION"));

        // 9. Redo restores Rev 3 (conflict clears again)
        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/redo")
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.currentRevisionNumber").value(3))
                .andExpect(jsonPath("$.data.activeFindings.length()").value(0));

        // 10. Deleting Food Stall 3 at Rev 3 truncates Redo tail (Rev 4 & 5 removed)
        mockMvc.perform(delete("/api/events/" + event.getId() + "/infrastructure/elements/" + stallId)
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.elements.length()").value(1))
                .andExpect(jsonPath("$.data.history.canRedo").value(false))
                .andExpect(jsonPath("$.data.history.currentRevisionNumber").value(4));
    }
}
