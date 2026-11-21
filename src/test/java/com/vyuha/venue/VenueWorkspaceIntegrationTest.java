package com.vyuha.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
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
class VenueWorkspaceIntegrationTest {

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
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() throws Exception {
        exclusionAreaRepository.deleteAll();
        venuePlanRepository.deleteAll();
        eventRepository.deleteAll();

        Path testUploads = Paths.get("./target/test-uploads");
        if (Files.exists(testUploads)) {
            try (Stream<Path> walk = Files.walk(testUploads)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
    }

    private Event createSampleEvent() {
        CustomUserDetails organizer = (CustomUserDetails) userDetailsService.loadUserByUsername("organizer@vyuha.local");
        EventForm form = new EventForm();
        form.setName("TechnoVyuha Quadrangle Plan");
        form.setCategory(EventCategory.COLLEGE_FESTIVAL);
        form.setStatus(EventStatus.PLANNING);
        form.setStartDateTime(LocalDateTime.of(2026, 12, 12, 16, 0));
        form.setEndDateTime(LocalDateTime.of(2026, 12, 12, 22, 0));
        form.setExpectedAttendance(3500);
        form.setLocationName("Central Quadrangle");
        return eventService.createEvent(form, organizer.getId());
    }

    private byte[] createTestPng(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.DARK_GRAY);
        g.drawRect(10, 10, width - 20, height - 20);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void rendersVenueWorkspaceOfflineAndEnforcesRbac() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        UserDetails staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        mockMvc.perform(get("/events/" + event.getId() + "/venue").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1. Site Plan")))
                .andExpect(content().string(containsString("Gross Site Area")))
                .andExpect(content().string(containsString("Net Usable Gathering Area")))
                .andExpect(content().string(not(containsString("Stampede-Proof"))))
                .andExpect(content().string(not(containsString("https://"))));

        // Reviewer can open Venue Plan in read-only mode
        mockMvc.perform(get("/events/" + event.getId() + "/venue").with(user(reviewer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reviewer Read-Only")));

        // Field staff is denied access to Venue Plan and venue API
        mockMvc.perform(get("/events/" + event.getId() + "/venue").with(user(staff)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/events/" + event.getId() + "/venue").with(user(staff)))
                .andExpect(status().isForbidden());

        // Reviewer cannot mutate venue geometry or mode
        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/mode")
                        .with(user(reviewer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceMode":"BLUEPRINT_UPLOAD","sampleMapKey":"campus-quadrangle"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/boundary")
                        .with(user(reviewer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"polygonWkt":"POLYGON ((0 0, 50 0, 50 40, 0 40, 0 0))"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void interactiveMapSavesAndReloadsBoundaryOverlappingExclusionsAndCustomVectorMap() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");

        // Switch to civic-plaza sample map
        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/mode")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceMode":"INTERACTIVE_MAP","sampleMapKey":"civic-plaza","gridSnapMeters":1.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.venuePlan.sampleMapKey").value("civic-plaza"));

        // Save 100m x 80m boundary = 8000 m²
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.areaBreakdown.grossAreaSqm").value(8000.0))
                .andExpect(jsonPath("$.data.areaBreakdown.usableAreaSqm").value(8000.0));

        // Add overlapping STAGE (400 m²) and BUILDING (400 m²) with 200 m² overlap
        MvcResult firstExRes = mockMvc.perform(post("/api/events/" + event.getId() + "/venue/exclusions")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "label": "Stage Deck",
                                  "exclusionType": "STAGE",
                                  "points": [
                                    {"x": 10, "y": 10},
                                    {"x": 30, "y": 10},
                                    {"x": 30, "y": 30},
                                    {"x": 10, "y": 30}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(firstExRes.getResponse().getContentAsString());
        long stageId = firstJson.path("data").path("exclusions").get(0).path("id").asLong();

        mockMvc.perform(post("/api/events/" + event.getId() + "/venue/exclusions")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "label": "Control Pavilion",
                                  "exclusionType": "BUILDING",
                                  "points": [
                                    {"x": 20, "y": 10},
                                    {"x": 40, "y": 10},
                                    {"x": 40, "y": 30},
                                    {"x": 20, "y": 30}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.areaBreakdown.excludedTotalAreaSqm").value(600.0))
                .andExpect(jsonPath("$.data.areaBreakdown.overlapDeduplicatedSqm").value(200.0))
                .andExpect(jsonPath("$.data.areaBreakdown.usableAreaSqm").value(7400.0));

        // Reload via GET and verify persistence
        mockMvc.perform(get("/api/events/" + event.getId() + "/venue").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exclusions.length()").value(2))
                .andExpect(jsonPath("$.data.areaBreakdown.usableAreaSqm").value(7400.0));

        // Update and delete exclusion
        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/exclusions/" + stageId)
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "label": "Stage Deck Updated",
                                  "exclusionType": "STAGE",
                                  "notes": "Expanded stage wings"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exclusions[0].label").value("Stage Deck Updated"));

        mockMvc.perform(delete("/api/events/" + event.getId() + "/venue/exclusions/" + stageId)
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.areaBreakdown.usableAreaSqm").value(7600.0));

        // Sync layout (Undo/Redo snapshot) restores clean layout state
        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/layout")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "boundaryWkt": "POLYGON ((0 0, 50 0, 50 40, 0 40, 0 0))",
                                  "exclusions": [
                                    {
                                      "label": "Restored Walkway",
                                      "exclusionType": "RESERVED_WALKWAY",
                                      "polygonWkt": "POLYGON ((0 0, 50 0, 50 5, 0 5, 0 0))"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.areaBreakdown.grossAreaSqm").value(2000.0))
                .andExpect(jsonPath("$.data.areaBreakdown.corridorsAreaSqm").value(250.0))
                .andExpect(jsonPath("$.data.areaBreakdown.usableAreaSqm").value(1750.0));

        // Import custom GeoJSON map
        String geoJson = """
                {
                  "type": "FeatureCollection",
                  "features": [
                    {
                      "type": "Feature",
                      "properties": {"role": "outer_boundary", "label": "Custom Hall"},
                      "geometry": {"type": "Polygon", "coordinates": [[[0,0],[60,0],[60,40],[0,40],[0,0]]]}
                    }
                  ]
                }
                """;
        MockMultipartFile vectorFile = new MockMultipartFile(
                "file", "hall.geojson", "application/json", geoJson.getBytes());
        mockMvc.perform(multipart("/api/events/" + event.getId() + "/venue/vector-map")
                        .file(vectorFile)
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.venuePlan.customVectorFilename").value("hall.geojson"));
    }

    @Test
    void blueprintUploadCalibrationDistortionWarningAndPixelBoundaryConversionWorkEndToEnd() throws Exception {
        Event event = createSampleEvent();
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");

        // Reject unsafe SVG containing script tag
        MockMultipartFile unsafeSvg = new MockMultipartFile(
                "file", "bad.svg", "image/svg+xml",
                "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes());
        mockMvc.perform(multipart("/api/events/" + event.getId() + "/venue/blueprint")
                        .file(unsafeSvg)
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        // Upload valid 400x300 PNG blueprint
        MockMultipartFile png = new MockMultipartFile(
                "file", "campus-ortho.png", "image/png", createTestPng(400, 300));
        mockMvc.perform(multipart("/api/events/" + event.getId() + "/venue/blueprint")
                        .file(png)
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.venuePlan.workspaceMode").value("BLUEPRINT_UPLOAD"))
                .andExpect(jsonPath("$.data.venuePlan.blueprintWidthPx").value(400))
                .andExpect(jsonPath("$.data.venuePlan.blueprintHeightPx").value(300));

        // Stream blueprint safely for reviewer
        mockMvc.perform(get("/api/events/" + event.getId() + "/venue/blueprint").with(user(reviewer)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Type", "image/png"));

        // Pixel-space polygon fails before calibration
        mockMvc.perform(put("/api/events/" + event.getId() + "/venue/boundary")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "coordinateSpace": "PIXELS",
                                  "points": [
                                    {"x": 0, "y": 0},
                                    {"x": 400, "y": 0},
                                    {"x": 400, "y": 300},
                                    {"x": 0, "y": 300}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest());

        // Skewed verification calibration (>5% mismatch) raises distortionWarning=true
        mockMvc.perform(post("/api/events/" + event.getId() + "/venue/calibration")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "primaryX1Px": 0, "primaryY1Px": 100, "primaryX2Px": 400, "primaryY2Px": 100,
                                  "primaryRealDistanceMeters": 40.0,
                                  "verifyX1Px": 100, "verifyY1Px": 0, "verifyX2Px": 100, "verifyY2Px": 250,
                                  "verifyRealDistanceMeters": 20.0,
                                  "orthographicConfirmed": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.venuePlan.distortionWarning").value(true))
                .andExpect(jsonPath("$.data.venuePlan.scaleVerified").value(false));

        // Accurate orthographic calibration (10 px/m on both axes => 0% mismatch)
        mockMvc.perform(post("/api/events/" + event.getId() + "/venue/calibration")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "primaryX1Px": 0, "primaryY1Px": 100, "primaryX2Px": 400, "primaryY2Px": 100,
                                  "primaryRealDistanceMeters": 40.0,
                                  "verifyX1Px": 100, "verifyY1Px": 0, "verifyX2Px": 100, "verifyY2Px": 300,
                                  "verifyRealDistanceMeters": 30.0,
                                  "orthographicConfirmed": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.venuePlan.pixelsPerMeter").value(10.0))
                .andExpect(jsonPath("$.data.venuePlan.scaleVerified").value(true))
                .andExpect(jsonPath("$.data.venuePlan.distortionWarning").value(false));

        // Trace pixel-space boundary 400x300 px at 10 px/m = 40m x 30m = 1200 m²
        MvcResult boundaryRes = mockMvc.perform(put("/api/events/" + event.getId() + "/venue/boundary")
                        .with(user(organizer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "coordinateSpace": "PIXELS",
                                  "points": [
                                    {"x": 0, "y": 0},
                                    {"x": 400, "y": 0},
                                    {"x": 400, "y": 300},
                                    {"x": 0, "y": 300}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resNode = objectMapper.readTree(boundaryRes.getResponse().getContentAsString());
        assertThat(resNode.path("data").path("areaBreakdown").path("grossAreaSqm").asDouble())
                .isCloseTo(1200.0, within(0.01));
    }
}
