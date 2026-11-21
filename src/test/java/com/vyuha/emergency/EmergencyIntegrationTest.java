package com.vyuha.emergency;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
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
class EmergencyIntegrationTest {

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
    private EmergencyAssetRepository emergencyAssetRepository;

    @Autowired
    private EmergencyContactRepository emergencyContactRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        emergencyContactRepository.deleteAll();
        emergencyAssetRepository.deleteAll();
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
        form.setName("Phase 8 Emergency Readiness Event");
        form.setCategory(EventCategory.COLLEGE_FESTIVAL);
        form.setStatus(EventStatus.PLANNING);
        form.setStartDateTime(LocalDateTime.of(2026, 12, 24, 16, 0));
        form.setEndDateTime(LocalDateTime.of(2026, 12, 24, 21, 0));
        form.setExpectedAttendance(2400);
        form.setLocationName("Quadrangle Ground");
        return eventService.createEvent(form, organizer.getId());
    }

    @Test
    void enforcesRbacAndRendersOfflineEmergencyWorkspaceWithAuthorityDeference() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        UserDetails staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        mockMvc.perform(get("/events/" + event.getId() + "/emergency").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Authority Deference Notice")))
                .andExpect(content().string(containsString("statutory authorities")))
                .andExpect(content().string(containsString("emergency-workspace.js")))
                .andExpect(content().string(containsString("mono")))
                .andExpect(content().string(not(containsString("Stampede-Proof"))))
                .andExpect(content().string(not(containsString("Certified"))))
                .andExpect(content().string(not(containsString("https://"))));

        // SAFETY_REVIEWER can view workspace and run contingency outage test, but cannot mutate assets or contacts
        mockMvc.perform(get("/events/" + event.getId() + "/emergency").with(user(reviewer)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/emergency/outage-test")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/events/" + event.getId() + "/emergency/assets")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetType":"EMERGENCY_EXIT","label":"Reviewer Exit","x":10,"y":10}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/events/" + event.getId() + "/emergency/contacts")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agencyName":"Police","contactName":"SI","phone":"100","role":"Police Control"}
                                """))
                .andExpect(status().isForbidden());

        // FIELD_STAFF is denied all /events/{id}/emergency routes
        mockMvc.perform(get("/events/" + event.getId() + "/emergency").with(user(staff)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/events/" + event.getId() + "/emergency").with(user(staff)))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportsEmergencyAssetAndContactCrudWithRouteValidationAndInteractiveOutageTest() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");

        // 1. Setup boundary, AUD-1 zone, Exit Gate, Emergency Exit, Food Stall, and pathway network
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
                                {"zoneCode":"AUD-1","name":"Main Lawn","zoneType":"AUDIENCE","polygonWkt":"POLYGON ((20 20, 60 20, 60 60, 20 60, 20 20))"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long zoneId = objectMapper.readTree(zoneRes.getResponse().getContentAsString())
                .path("data").path("zones").get(0).path("id").asLong();

        MvcResult infraRes1 = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"EXIT_GATE","label":"North Exit Gate","centerX":40,"centerY":5,"widthM":6.0,"heightM":2.0,"throughputPersonsPerMin":80}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long exitGateId = objectMapper.readTree(infraRes1.getResponse().getContentAsString())
                .path("data").path("lastModifiedElementId").asLong();

        MvcResult infraRes2 = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"EMERGENCY_EXIT","label":"East Emergency Exit","centerX":115,"centerY":40,"widthM":6.0,"heightM":2.0,"throughputPersonsPerMin":100}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long emExitId = objectMapper.readTree(infraRes2.getResponse().getContentAsString())
                .path("data").path("lastModifiedElementId").asLong();

        // Place a Food Stall overlapping the ambulance route we will draw along y=75
        mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"FOOD_STALL","label":"Obstructing Food Stall","centerX":50,"centerY":75,"widthM":4.0,"heightM":3.0}
                                """))
                .andExpect(status().isOk());

        // Auto-seed pathway nodes for AUD-1, North Exit Gate, East Emergency Exit, then connect them
        MvcResult seedRes = mockMvc.perform(post("/api/events/" + event.getId() + "/network/seed")
                        .with(user(organizer)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode nodesArr = objectMapper.readTree(seedRes.getResponse().getContentAsString()).path("data").path("nodes");
        long hubNodeId = -1;
        long exitGateNodeId = -1;
        long emExitNodeId = -1;
        for (JsonNode n : nodesArr) {
            if (n.path("linkedZoneId").asLong(-1) == zoneId) hubNodeId = n.path("id").asLong();
            if (n.path("linkedInfraId").asLong(-1) == exitGateId) exitGateNodeId = n.path("id").asLong();
            if (n.path("linkedInfraId").asLong(-1) == emExitId) emExitNodeId = n.path("id").asLong();
        }
        assertThat(hubNodeId).isPositive();
        assertThat(exitGateNodeId).isPositive();
        assertThat(emExitNodeId).isPositive();

        MvcResult edge1Res = mockMvc.perform(post("/api/events/" + event.getId() + "/network/edges")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromNodeId":%d,"toNodeId":%d,"label":"North Exit Path","networkLayer":"NORMAL_EXIT","widthM":4.5,"direction":"ONE_WAY"}
                                """.formatted(hubNodeId, exitGateNodeId)))
                .andExpect(status().isOk())
                .andReturn();
        long northEdgeId = objectMapper.readTree(edge1Res.getResponse().getContentAsString())
                .path("data").path("lastModifiedEdgeId").asLong();

        mockMvc.perform(post("/api/events/" + event.getId() + "/network/edges")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromNodeId":%d,"toNodeId":%d,"label":"East Emergency Corridor","networkLayer":"EMERGENCY_ACCESS","widthM":4.0,"direction":"BIDIRECTIONAL"}
                                """.formatted(hubNodeId, emExitNodeId)))
                .andExpect(status().isOk());

        // 2. Create Emergency Assets: obstructed Ambulance Route (4.0m), narrow Fire Route (2.9m), external Assembly Point
        MvcResult ambRes = mockMvc.perform(post("/api/events/" + event.getId() + "/emergency/assets")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetType":"AMBULANCE_ROUTE","label":"South Ambulance Route","geometryWkt":"LINESTRING (10 75, 110 75)","minWidthM":4.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validation.obstructedRouteCount").value(1))
                .andReturn();
        long ambAssetId = objectMapper.readTree(ambRes.getResponse().getContentAsString())
                .path("data").path("assets").get(0).path("id").asLong();

        mockMvc.perform(post("/api/events/" + event.getId() + "/emergency/assets")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetType":"FIRE_ROUTE","label":"Narrow Fire Lane","geometryWkt":"LINESTRING (10 15, 10 85)","minWidthM":2.9}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validation.narrowRouteCount").value(1))
                .andExpect(jsonPath("$.data.validation.hasCriticalWarnings").value(true));

        mockMvc.perform(post("/events/" + event.getId() + "/emergency/assets")
                        .with(user(organizer)).with(csrf())
                        .param("assetType", "ASSEMBLY_POINT")
                        .param("label", "North External Assembly")
                        .param("geometryWkt", "POLYGON ((10 95, 50 95, 50 115, 10 115, 10 95))")
                        .param("capacityNotes", "Holds 2,500 persons"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/events/*/emergency"));

        // 3. Create, update, and delete Emergency Contact via MVC + REST
        MvcResult contactRes = mockMvc.perform(post("/api/events/" + event.getId() + "/emergency/contacts")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agencyName":"District Police Control","contactName":"Inspector Rao","phone":"100","role":"Police Control","priorityOrder":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contacts[0].agencyName").value("District Police Control"))
                .andReturn();
        long contactId = objectMapper.readTree(contactRes.getResponse().getContentAsString())
                .path("data").path("contacts").get(0).path("id").asLong();

        mockMvc.perform(put("/api/events/" + event.getId() + "/emergency/contacts/" + contactId)
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agencyName":"District Police Control","contactName":"DSP Rao","phone":"112","role":"Police Control","priorityOrder":2,"notes":"Channel 4"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contacts[0].phone").value("112"));

        // 4. Outage Test: close North Exit Gate -> AUD-1 reroutes to East Emergency Exit with detour
        mockMvc.perform(post("/api/events/" + event.getId() + "/emergency/outage-test")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"disabledExitInfraIds":[%d],"emergencyExitsOnly":false}
                                """.formatted(exitGateId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reroutedZoneCount").value(1))
                .andExpect(jsonPath("$.data.newlyIsolatedZoneCount").value(0))
                .andExpect(jsonPath("$.data.zoneImpacts[0].status").value("REROUTED"))
                .andExpect(jsonPath("$.data.zoneImpacts[0].backupExitLabel").value("East Emergency Exit"));

        // 5. Outage Test: close North Exit Path + East Emergency Exit -> AUD-1 becomes NEWLY_ISOLATED
        mockMvc.perform(post("/api/events/" + event.getId() + "/emergency/outage-test")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"disabledExitInfraIds":[%d],"disabledEdgeIds":[%d],"emergencyExitsOnly":false}
                                """.formatted(emExitId, northEdgeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newlyIsolatedZoneCount").value(1))
                .andExpect(jsonPath("$.data.zoneImpacts[0].status").value("NEWLY_ISOLATED"))
                .andExpect(jsonPath("$.data.remainingEgressCapacityPct").value(0.0));

        // 6. Clean delete of asset and contact
        mockMvc.perform(delete("/api/events/" + event.getId() + "/emergency/assets/" + ambAssetId)
                        .with(user(organizer)).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/events/" + event.getId() + "/emergency/contacts/" + contactId)
                        .with(user(organizer)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contacts").isEmpty());
    }
}
