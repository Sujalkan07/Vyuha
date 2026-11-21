package com.vyuha.grid;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
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
class GridAndCapacityIntegrationTest {

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
    private VenueGridConfigRepository gridConfigRepository;

    @Autowired
    private FunctionalZoneRepository functionalZoneRepository;

    @Autowired
    private CapacityReviewRepository capacityReviewRepository;

    @Autowired
    private ZoneDensityAssumptionRepository assumptionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        assumptionRepository.deleteAll();
        capacityReviewRepository.deleteAll();
        functionalZoneRepository.deleteAll();
        gridConfigRepository.deleteAll();
        exclusionAreaRepository.deleteAll();
        venuePlanRepository.deleteAll();
        eventRepository.deleteAll();
    }

    private Event createSampleEvent() {
        CustomUserDetails organizer = (CustomUserDetails) userDetailsService.loadUserByUsername("organizer@vyuha.local");
        EventForm form = new EventForm();
        form.setName("Phase 4 Quadrangle Grid Event");
        form.setCategory(EventCategory.COLLEGE_FESTIVAL);
        form.setStatus(EventStatus.PLANNING);
        form.setStartDateTime(LocalDateTime.of(2026, 12, 12, 16, 0));
        form.setEndDateTime(LocalDateTime.of(2026, 12, 12, 22, 0));
        form.setExpectedAttendance(1500);
        form.setLocationName("Main Quadrangle");
        return eventService.createEvent(form, organizer.getId());
    }

    @Test
    void enforcesRbacAndRendersOfflineZonesAndCapacityViewsWithoutCertificationLanguage() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        UserDetails staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        mockMvc.perform(get("/events/" + event.getId() + "/venue?mode=zones").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2. Zones &amp; Grid")))
                .andExpect(content().string(containsString("Zone Color Legend")))
                .andExpect(content().string(not(containsString("https://"))));

        mockMvc.perform(get("/events/" + event.getId() + "/capacity").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Preliminary Capacity Review")))
                .andExpect(content().string(containsString("Tightest Governing Constraint")))
                .andExpect(content().string(not(containsString("Stampede-Proof"))))
                .andExpect(content().string(not(containsString("Certified"))))
                .andExpect(content().string(not(containsString("https://"))));

        // FIELD_STAFF denied across grid and capacity routes
        mockMvc.perform(get("/events/" + event.getId() + "/capacity").with(user(staff)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/events/" + event.getId() + "/grid").with(user(staff)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/events/" + event.getId() + "/capacity").with(user(staff)))
                .andExpect(status().isForbidden());

        // SAFETY_REVIEWER cannot mutate grid/zones or parameters
        mockMvc.perform(put("/api/events/" + event.getId() + "/grid/config")
                        .with(user(reviewer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cellSizeMeters":10.0,"originX":0,"originY":0,"rotationDeg":0}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/events/" + event.getId() + "/capacity/parameters")
                        .with(user(reviewer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officialVenueLimit":2000}
                                """))
                .andExpect(status().isForbidden());

        // ORGANIZER cannot verify overall capacity review
        mockMvc.perform(post("/api/events/" + event.getId() + "/capacity/review")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewStatus":"REVIEWER_VERIFIED","reviewerNotes":"Attempt"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void fullGridZonePaintingCapacityCalculationAndReviewerVerificationWorkflow() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");

        // 1. Set 50m x 40m outer venue boundary (2000 m²)
        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/boundary")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "coordinateSpace": "METERS",
                                  "points": [
                                    {"x": 0, "y": 0},
                                    {"x": 50, "y": 0},
                                    {"x": 50, "y": 40},
                                    {"x": 0, "y": 40}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        // 2. Configure 10m grid -> 5x4 = 20 cells
        mockMvc.perform(put("/api/events/" + event.getId() + "/grid/config")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cellSizeMeters":10.0,"originX":0,"originY":0,"rotationDeg":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gridConfig.totalCells").value(20));

        // 3. Paint AUD-1 from 6 grid cells (600 m²) and add ENTRY zone
        MvcResult audRes = mockMvc.perform(post("/api/events/" + event.getId() + "/grid/zones")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "zoneCode": "AUD-1",
                                  "name": "Main Audience Lawn",
                                  "zoneType": "AUDIENCE",
                                  "gridCellRefs": ["C1R1","C2R1","C3R1","C1R2","C2R2","C3R2"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.zones[0].usableAreaSqm").value(600.0))
                .andReturn();

        JsonNode audJson = objectMapper.readTree(audRes.getResponse().getContentAsString());
        long audZoneId = audJson.path("data").path("zones").get(0).path("id").asLong();

        // Add 2 more cells -> 800 m²
        mockMvc.perform(put("/api/events/" + event.getId() + "/grid/zones/" + audZoneId + "/cells")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"ADD","gridCellRefs":["C1R3","C2R3"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.zones[0].usableAreaSqm").value(800.0));

        // 4. Reject density assumption without mandatory source citation, then save valid assumption
        mockMvc.perform(put("/api/events/" + event.getId() + "/capacity/zones/" + audZoneId)
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "densityPersonsPerSqm": 2.0,
                                  "sourceCitation": "   "
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/events/" + event.getId() + "/capacity/zones/" + audZoneId)
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "densityPersonsPerSqm": 2.0,
                                  "sourceCitation": "Campus Safety Advisory 2026 Sec 3",
                                  "approvedByName": "Dean Operations"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calculation.usableSpaceCapacity").value(1600))
                .andExpect(jsonPath("$.data.calculation.effectiveReviewStatus").value("DOCUMENTED"));

        // 5. Undo/Redo layout sync preserving zone ID keeps density assumption intact
        mockMvc.perform(put("/api/events/" + event.getId() + "/grid/layout")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "gridConfig": {"cellSizeMeters":10.0,"originX":0,"originY":0,"rotationDeg":0},
                                  "zones": [
                                    {
                                      "id": %d,
                                      "zoneCode": "AUD-1",
                                      "name": "Main Audience Lawn",
                                      "zoneType": "AUDIENCE",
                                      "polygonWkt": "POLYGON ((10 10, 40 10, 40 30, 10 30, 10 10))",
                                      "gridCellRefs": ["C1R1","C2R1","C3R1","C1R2","C2R2","C3R2"]
                                    }
                                  ]
                                }
                                """.formatted(audZoneId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.zones[0].usableAreaSqm").value(600.0));

        // Density assumption survived layout sync and now yields 600 * 2.0 = 1200
        mockMvc.perform(get("/api/events/" + event.getId() + "/capacity").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calculation.usableSpaceCapacity").value(1200))
                .andExpect(jsonPath("$.data.calculation.exceedsPreliminaryCeiling").value(true));

        // 6. Organizer updates flow and official parameters via MVC form post -> C_flow = 1000 governs
        mockMvc.perform(post("/events/" + event.getId() + "/capacity/parameters")
                        .with(user(organizer))
                        .with(csrf())
                        .param("officialVenueLimit", "1800")
                        .param("officialLimitSource", "Municipal Permit #55")
                        .param("entryThroughputPerMin", "25")
                        .param("ingressWindowMinutes", "40")
                        .param("exitThroughputPerMin", "40")
                        .param("egressWindowMinutes", "45"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/events/*/capacity"));

        mockMvc.perform(get("/api/events/" + event.getId() + "/capacity").with(user(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calculation.flowCapacity").value(1000))
                .andExpect(jsonPath("$.data.calculation.preliminaryCapacity").value(1000))
                .andExpect(jsonPath("$.data.calculation.governingConstraint").value("ENTRY_EXIT_FLOW"));

        // 7. Reviewer cannot verify overall review until individual zone assumption is verified
        mockMvc.perform(post("/api/events/" + event.getId() + "/capacity/review")
                        .with(user(reviewer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewStatus":"REVIEWER_VERIFIED","reviewerNotes":"Premature"}
                                """))
                .andExpect(status().isBadRequest());

        // Reviewer verifies AUD-1 then verifies overall capacity review
        mockMvc.perform(post("/api/events/" + event.getId() + "/capacity/zones/" + audZoneId + "/review")
                        .with(user(reviewer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewStatus":"REVIEWER_VERIFIED","reviewerNote":"Density citation confirmed"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calculation.verifiedCapacityZones").value(1));

        mockMvc.perform(post("/api/events/" + event.getId() + "/capacity/review")
                        .with(user(reviewer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewStatus":"REVIEWER_VERIFIED","reviewerNotes":"All assumptions checked"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calculation.effectiveReviewStatus").value("REVIEWER_VERIFIED"));

        // Ensure Capacity HTML renders cleanly with Reviewer Verified badge
        mockMvc.perform(get("/events/" + event.getId() + "/capacity").with(user(reviewer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reviewer Verified")))
                .andExpect(content().string(containsString("Campus Safety Advisory 2026 Sec 3")));

        // 8. Editing a verified zone density assumption automatically invalidates prior verification
        mockMvc.perform(put("/api/events/" + event.getId() + "/capacity/zones/" + audZoneId)
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "densityPersonsPerSqm": 1.5,
                                  "sourceCitation": "Updated Campus Safety Advisory 2026 Sec 4",
                                  "approvedByName": "Dean Operations"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calculation.usableSpaceCapacity").value(900))
                .andExpect(jsonPath("$.data.calculation.verifiedCapacityZones").value(0))
                .andExpect(jsonPath("$.data.calculation.effectiveReviewStatus").value("DOCUMENTED"));
    }
}
