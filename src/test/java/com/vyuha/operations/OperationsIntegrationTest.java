package com.vyuha.operations;

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
import com.vyuha.capacity.CapacityReviewRepository;
import com.vyuha.capacity.ZoneDensityAssumptionRepository;
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
class OperationsIntegrationTest {

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
    private CapacityReviewRepository capacityReviewRepository;

    @Autowired
    private ZoneDensityAssumptionRepository assumptionRepository;

    @Autowired
    private IncidentLogRepository incidentLogRepository;

    @Autowired
    private StaffAssignmentRepository staffAssignmentRepository;

    @Autowired
    private StaffMemberRepository staffMemberRepository;

    @Autowired
    private StaffingTargetRepository staffingTargetRepository;

    @Autowired
    private ArrivalWaveIntervalRepository arrivalWaveIntervalRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        incidentLogRepository.deleteAll();
        staffAssignmentRepository.deleteAll();
        staffMemberRepository.deleteAll();
        staffingTargetRepository.deleteAll();
        arrivalWaveIntervalRepository.deleteAll();
        assumptionRepository.deleteAll();
        capacityReviewRepository.deleteAll();
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
        form.setName("Phase 7 Campus Operations Event");
        form.setCategory(EventCategory.COLLEGE_FESTIVAL);
        form.setStatus(EventStatus.PLANNING);
        form.setStartDateTime(LocalDateTime.of(2026, 12, 22, 16, 0));
        form.setEndDateTime(LocalDateTime.of(2026, 12, 22, 20, 0));
        form.setExpectedAttendance(1200);
        form.setLocationName("Quadrangle Ground");
        return eventService.createEvent(form, organizer.getId());
    }

    @Test
    void enforcesRbacAndRendersOfflineSubTabsAndFieldView() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        UserDetails staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        // Organizer renders Capacity Review, Arrival Waves & Queues, and Staff & Incidents cleanly offline
        mockMvc.perform(get("/events/" + event.getId() + "/capacity").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Arrival Waves &amp; Queues")))
                .andExpect(content().string(containsString("Staff &amp; Incidents")))
                .andExpect(content().string(not(containsString("https://"))));

        mockMvc.perform(get("/events/" + event.getId() + "/capacity?tab=arrivals").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Queue Accumulation vs. Holding Space Timeline")))
                .andExpect(content().string(not(containsString("Stampede-Proof"))))
                .andExpect(content().string(not(containsString("Certified"))))
                .andExpect(content().string(not(containsString("https://"))));

        mockMvc.perform(get("/events/" + event.getId() + "/capacity?tab=staff").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Automated Under-Staffing &amp; Shift Gap Checks")))
                .andExpect(content().string(containsString("Incident &amp; Staff Response Log")))
                .andExpect(content().string(not(containsString("https://"))));

        // SAFETY_REVIEWER can read operations API, but cannot mutate waves, staff, shifts, targets, or incidents
        mockMvc.perform(get("/api/events/" + event.getId() + "/operations").with(user(reviewer)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/operations/waves")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startMinute":0,"endMinute":15,"expectedArrivals":200}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/events/" + event.getId() + "/operations/staff")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test Guard","roleType":"SECURITY"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/events/" + event.getId() + "/operations/incidents")
                        .with(user(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"severity":"HIGH","description":"Unauthorized test"}
                                """))
                .andExpect(status().isForbidden());

        // FIELD_STAFF is denied /events/** and /api/events/**, but permitted on /field and /api/field/**
        mockMvc.perform(get("/events/" + event.getId() + "/capacity?tab=arrivals").with(user(staff)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/events/" + event.getId() + "/operations").with(user(staff)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/field?eventId=" + event.getId()).with(user(staff)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Field Operations View")))
                .andExpect(content().string(containsString("Report Field Incident")))
                .andExpect(content().string(not(containsString("https://"))));
    }

    @Test
    void fullArrivalWavesQueueOverflowStaffingCoverageAndFieldIncidentWorkflow() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        CustomUserDetails staffUser = (CustomUserDetails) userDetailsService.loadUserByUsername("staff@vyuha.local");

        // 1. Setup 100x80 boundary, AUD-1 + QUE-1 (10m x 10m = 100 m²), and active ENTRY_GATE (40 p/min)
        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/boundary")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"coordinateSpace":"METERS","points":[{"x":0,"y":0},{"x":100,"y":0},{"x":100,"y":80},{"x":0,"y":80}]}
                                """))
                .andExpect(status().isOk());

        MvcResult audRes = mockMvc.perform(post("/api/events/" + event.getId() + "/grid/zones")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneCode":"AUD-1","name":"Main Audience","zoneType":"AUDIENCE","points":[{"x":25,"y":20},{"x":75,"y":20},{"x":75,"y":60},{"x":25,"y":60}]}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long audZoneId = objectMapper.readTree(audRes.getResponse().getContentAsString())
                .path("data").path("zones").get(0).path("id").asLong();

        MvcResult queRes = mockMvc.perform(post("/api/events/" + event.getId() + "/grid/zones")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneCode":"QUE-1","name":"Entry Queue Pen","zoneType":"QUEUE","points":[{"x":5,"y":30},{"x":15,"y":30},{"x":15,"y":40},{"x":5,"y":40}]}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode queZones = objectMapper.readTree(queRes.getResponse().getContentAsString()).path("data").path("zones");
        long queZoneId = queZones.get(1).path("id").asLong();

        // Set holding density 2.0 p/m² -> 100 m² * 2.0 = 200 holding capacity
        mockMvc.perform(put("/api/events/" + event.getId() + "/capacity/zones/" + queZoneId)
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"densityPersonsPerSqm":2.0,"sourceCitation":"Queue Manual Sec 4"}
                                """))
                .andExpect(status().isOk());

        MvcResult gateRes = mockMvc.perform(post("/api/events/" + event.getId() + "/infrastructure/elements")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elementType":"ENTRY_GATE","label":"North Gate","centerX":5,"centerY":35,"throughputPersonsPerMin":40}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long gateId = objectMapper.readTree(gateRes.getResponse().getContentAsString())
                .path("data").path("lastModifiedElementId").asLong();

        // 2. Reject invalid wave interval (end <= start)
        mockMvc.perform(post("/api/events/" + event.getId() + "/operations/waves")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startMinute":30,"endMinute":15,"expectedArrivals":200}
                                """))
                .andExpect(status().isBadRequest());

        // 3. Add two arrival waves:
        // 0-15 min: 900 arrivals vs 600 cap -> Q1 = 300 (overflow +100 p vs 200 holding cap -> 50.0 m² deficit, wait 7.5 min)
        // 15-30 min: 300 arrivals vs 600 cap -> Q2 = 0
        mockMvc.perform(post("/events/" + event.getId() + "/capacity/waves")
                        .with(user(organizer)).with(csrf())
                        .param("label", "Opening Surge")
                        .param("startMinute", "0")
                        .param("endMinute", "15")
                        .param("expectedArrivals", "900"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/events/*/capacity?tab=arrivals"));

        mockMvc.perform(post("/api/events/" + event.getId() + "/operations/waves")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Tail Wave","startMinute":15,"endMinute":30,"expectedArrivals":300}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queueProjection.totalExpectedArrivals").value(1200))
                .andExpect(jsonPath("$.data.queueProjection.effectiveHoldingCapacity").value(200))
                .andExpect(jsonPath("$.data.queueProjection.peakQueueLength").value(300))
                .andExpect(jsonPath("$.data.queueProjection.peakWaitMinutes").value(7.5))
                .andExpect(jsonPath("$.data.queueProjection.overflowIntervalCount").value(1))
                .andExpect(jsonPath("$.data.queueProjection.maxHoldingDeficitSqm").value(50.0))
                .andExpect(jsonPath("$.data.queueProjection.residualUnservedQueue").value(0));

        // 4. Register staff (linking Rohan to staff@vyuha.local), configure relaxed targets, assign full shifts
        mockMvc.perform(put("/api/events/" + event.getId() + "/operations/staffing-targets")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"minSecurityPerActiveGate":1,"minStaffPerPublicZone":1,"minMedicalStaffTotal":1,"minSupervisorsTotal":1,"attendeesPerStaffRatio":400,"shiftWindowMinutes":60}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.staffCoverage.criticalFindingCount").isNumber());

        MvcResult secRes = mockMvc.perform(post("/api/events/" + event.getId() + "/operations/staff")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Rohan Verma","roleType":"SECURITY","contactPhone":"+91-90000-11111","userId":%d}
                                """.formatted(staffUser.getId())))
                .andExpect(status().isOk())
                .andReturn();
        long secStaffId = objectMapper.readTree(secRes.getResponse().getContentAsString())
                .path("data").path("staffMembers").get(0).path("id").asLong();

        MvcResult medRes = mockMvc.perform(post("/api/events/" + event.getId() + "/operations/staff")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dr. Ananya","roleType":"MEDICAL","certifications":"EMT-B"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long medStaffId = extractStaffIdByName(medRes, "Dr. Ananya");

        MvcResult supRes = mockMvc.perform(post("/api/events/" + event.getId() + "/operations/staff")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Meera Lead","roleType":"SUPERVISOR"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long supStaffId = extractStaffIdByName(supRes, "Meera Lead");

        // Reject invalid shift (end <= start)
        mockMvc.perform(post("/api/events/" + event.getId() + "/operations/assignments")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"staffId":%d,"zoneId":%d,"shiftStart":"2026-12-22T20:00:00","shiftEnd":"2026-12-22T16:00:00"}
                                """.formatted(secStaffId, audZoneId)))
                .andExpect(status().isBadRequest());

        // Assign shifts covering all posts and full 16:00-20:00 window
        mockMvc.perform(post("/api/events/" + event.getId() + "/operations/assignments")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"staffId":%d,"zoneId":%d,"infraId":%d,"shiftStart":"2026-12-22T16:00:00","shiftEnd":"2026-12-22T20:00:00"}
                                """.formatted(secStaffId, queZoneId, gateId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/operations/assignments")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"staffId":%d,"zoneId":%d,"shiftStart":"2026-12-22T16:00:00","shiftEnd":"2026-12-22T20:00:00"}
                                """.formatted(medStaffId, audZoneId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events/" + event.getId() + "/operations/assignments")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"staffId":%d,"shiftStart":"2026-12-22T16:00:00","shiftEnd":"2026-12-22T20:00:00"}
                                """.formatted(supStaffId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.staffCoverage.totalFindingCount").value(0));

        // 5. Field staff sees linked shift on /field & /api/field and logs + resolves incident
        mockMvc.perform(get("/api/field/events/" + event.getId() + "/operations").with(user(staffUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myShifts.length()").value(1))
                .andExpect(jsonPath("$.data.myShifts[0].staffName").value("Rohan Verma"));

        MvcResult incRes = mockMvc.perform(post("/api/field/events/" + event.getId() + "/incidents")
                        .with(user(staffUser)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"severity":"HIGH","zoneId":%d,"description":"North Gate queue extending past barrier","responseTaken":"Requested overflow lane"}
                                """.formatted(queZoneId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incidents.length()").value(1))
                .andExpect(jsonPath("$.data.incidents[0].status").value("OPEN"))
                .andReturn();

        long incId = objectMapper.readTree(incRes.getResponse().getContentAsString())
                .path("data").path("incidents").get(0).path("id").asLong();

        mockMvc.perform(put("/api/field/events/" + event.getId() + "/incidents/" + incId)
                        .with(user(staffUser)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED","responseTaken":"Overflow lane opened and queue cleared"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incidents[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.incidents[0].resolvedAt").isNotEmpty());

        // Rendered HTML tabs reflect arrivals & staff data cleanly
        mockMvc.perform(get("/events/" + event.getId() + "/capacity?tab=arrivals").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Opening Surge")))
                .andExpect(content().string(containsString("Overflow +100 p")));

        mockMvc.perform(get("/events/" + event.getId() + "/capacity?tab=staff").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rohan Verma")))
                .andExpect(content().string(containsString("North Gate queue extending past barrier")));

        // 6. Generate uniform wave buckets replacing existing & delete cleanly
        mockMvc.perform(post("/api/events/" + event.getId() + "/operations/waves/generate")
                        .with(user(organizer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bucketMinutes":15,"totalDurationMinutes":60,"totalArrivals":1200,"replaceExisting":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queueProjection.intervals.length()").value(4))
                .andExpect(jsonPath("$.data.queueProjection.totalExpectedArrivals").value(1200));

        // 7. Deleting event cascades cleanly across all Phase 7 tables
        mockMvc.perform(post("/events/" + event.getId() + "/delete").with(user(organizer)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(arrivalWaveIntervalRepository.count()).isZero();
        assertThat(staffMemberRepository.count()).isZero();
        assertThat(staffAssignmentRepository.count()).isZero();
        assertThat(staffingTargetRepository.count()).isZero();
        assertThat(incidentLogRepository.count()).isZero();
    }

    private long extractStaffIdByName(MvcResult res, String name) throws Exception {
        JsonNode members = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("staffMembers");
        for (JsonNode m : members) {
            if (name.equals(m.path("name").asText())) {
                return m.path("id").asLong();
            }
        }
        throw new IllegalStateException("Staff member not found in response: " + name);
    }
}
