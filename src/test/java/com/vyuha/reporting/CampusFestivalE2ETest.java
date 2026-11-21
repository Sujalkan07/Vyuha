package com.vyuha.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyuha.auth.CustomUserDetailsService;
import com.vyuha.event.Event;
import com.vyuha.event.EventRepository;
import com.vyuha.event.SupportingDocumentService;
import com.vyuha.simulation.SimulationService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
class CampusFestivalE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Autowired
    private CampusFestivalSeedService campusFestivalSeedService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ReportExportRecordRepository exportRecordRepository;

    @Autowired
    private ReportBundleBuilderService bundleBuilderService;

    @Autowired
    private SupportingDocumentService supportingDocumentService;

    @AfterEach
    void cleanup() {
        List<Event> events = eventRepository.findAll();
        for (Event ev : events) {
            supportingDocumentService.deleteEventDirectory(ev.getId());
        }
        eventRepository.deleteAll();
    }

    @Test
    void campusFestivalLifecycleResetComparisonRbacAndAllExportsWorkEndToEnd() throws Exception {
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        UserDetails staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        // 1. Safety Reviewer and Field Staff cannot trigger demo reset; Organizer can
        mockMvc.perform(post("/events/demo-reset").with(user(reviewer)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/events/demo-reset").with(user(staff)).with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/events/demo-reset").with(user(organizer)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/events/*"));

        // Idempotent seedIfMissing keeps single demo event without duplicating
        Event seeded = campusFestivalSeedService.seedIfMissing();
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(seeded.getName()).isEqualTo(CampusFestivalSeedService.DEMO_EVENT_NAME);
        assertThat(seeded.getExpectedAttendance()).isEqualTo(3500);

        Long eventId = seeded.getId();

        // 2. Verify full populated bundle & V1 -> V2 Before-and-After improvement
        ReportDtos.EventReportBundle bundle = bundleBuilderService.buildBundle(eventId);
        assertThat(bundle.venueWorkspace().venuePlan().scaleVerified()).isTrue();
        assertThat(bundle.venueWorkspace().venuePlan().distortionWarning()).isFalse();
        assertThat(bundle.venueWorkspace().areaBreakdown().usableAreaSqm()).isGreaterThan(3000.0);
        assertThat(bundle.capacityView().calculation().preliminaryCapacity()).isNotNull();
        assertThat(bundle.infraWorkspace().elements()).isNotEmpty();
        assertThat(bundle.networkWorkspace().edges()).isNotEmpty();
        assertThat(bundle.operationsWorkspace().queueProjection().intervals()).hasSize(4);
        assertThat(bundle.operationsWorkspace().staffMembers()).hasSize(6);
        assertThat(bundle.emergencyWorkspace().contacts()).hasSize(4);
        assertThat(bundle.livePassView().passSummary().totalIssued()).isEqualTo(12);
        assertThat(bundle.livePassView().passSummary().checkedInCount()).isEqualTo(4);

        // Before-and-After comparison from V1 -> V2 must show improved peak density & clearance time
        assertThat(bundle.featuredComparison()).isNotNull();
        assertThat(bundle.featuredComparison().parityMatched()).isTrue();
        assertThat(bundle.featuredComparison().peakDensity().afterValue())
                .isLessThan(bundle.featuredComparison().peakDensity().beforeValue());
        assertThat(bundle.featuredComparison().p95TravelTime().afterValue())
                .isLessThanOrEqualTo(bundle.featuredComparison().p95TravelTime().beforeValue());

        // 3. Reports Hub UI & RBAC
        mockMvc.perform(get("/events/" + eventId + "/reports").with(user(reviewer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Planning Reports &amp; Structured Exports")))
                .andExpect(content().string(containsString("Decision-Support")))
                .andExpect(content().string(containsString("Simplified Planning Simulation")))
                .andExpect(content().string(not(containsString("Stampede-Proof"))))
                .andExpect(content().string(not(containsString("https://"))));

        mockMvc.perform(get("/events/" + eventId + "/reports").with(user(staff)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/events/" + eventId + "/reports/export.pdf").with(user(staff)))
                .andExpect(status().isForbidden());

        // 4. Download & validate PDF export
        MvcResult pdfRes = mockMvc.perform(get("/api/events/" + eventId + "/reports/export.pdf").with(user(reviewer)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        byte[] pdfBytes = pdfRes.getResponse().getContentAsByteArray();
        assertThat(pdfBytes.length).isGreaterThan(5000);
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            String pdfText = new PDFTextStripper().getText(doc);
            assertThat(pdfText).contains("TechnoVyuha Annual Campus Festival");
            assertThat(pdfText).contains("Before-and-After Comparison");
            assertThat(pdfText).doesNotContain("Stampede-Proof");
        }

        // 5. Download & validate XLSX workbook
        MvcResult xlsxRes = mockMvc.perform(get("/api/events/" + eventId + "/reports/export.xlsx").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                .andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsxRes.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(12);
            assertThat(wb.getSheet("Simulation Metrics").getLastRowNum()).isGreaterThanOrEqualTo(3);
            assertThat(wb.getSheet("Area Breakdown").getLastRowNum()).isGreaterThanOrEqualTo(5);
        }

        // 6. Download & validate CSV datasets
        MvcResult csvRes = mockMvc.perform(get("/api/events/" + eventId + "/reports/export.csv")
                        .param("dataset", "simulation-runs")
                        .with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".csv")))
                .andReturn();
        String csvText = new String(csvRes.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csvText).contains("ARRIVAL_RUSH");
        assertThat(csvText).contains("2026");

        // 7. Download & validate PNG layout and comparison snapshots
        MvcResult layoutPngRes = mockMvc.perform(get("/api/events/" + eventId + "/reports/snapshot.png")
                        .param("view", "layout")
                        .with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn();
        var layoutImg = ImageIO.read(new ByteArrayInputStream(layoutPngRes.getResponse().getContentAsByteArray()));
        assertThat(layoutImg.getWidth()).isEqualTo(1200);

        MvcResult cmpPngRes = mockMvc.perform(get("/api/events/" + eventId + "/reports/snapshot.png")
                        .param("view", "comparison")
                        .with(user(reviewer)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn();
        var cmpImg = ImageIO.read(new ByteArrayInputStream(cmpPngRes.getResponse().getContentAsByteArray()));
        assertThat(cmpImg.getWidth()).isEqualTo(1200);

        // 8. Verify export audit trail recorded all 5 exports
        assertThat(exportRecordRepository.findTop20ByEventIdOrderByAuditCreatedAtDescIdDesc(eventId))
                .hasSize(5);
    }
}
