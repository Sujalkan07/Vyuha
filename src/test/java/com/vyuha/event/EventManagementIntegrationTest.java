package com.vyuha.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyuha.auth.CustomUserDetailsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventManagementIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventChecklistItemRepository checklistRepository;

    @Autowired
    private SafetyReviewRecordRepository reviewRepository;

    @Autowired
    private SupportingDocumentRepository documentRepository;

    @AfterEach
    void cleanupDataAndFiles() throws Exception {
        documentRepository.deleteAll();
        reviewRepository.deleteAll();
        checklistRepository.deleteAll();
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

    @Test
    void organizerCanSaveIncompleteDraftAndRejectsIncompletePlanningEvent() throws Exception {
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");

        // Incomplete non-draft fails validation and stays on form
        mockMvc.perform(post("/events")
                        .with(user(organizer))
                        .with(csrf())
                        .param("name", "Incomplete Planning Event")
                        .param("category", "CONCERT")
                        .param("status", "PLANNING"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Start date/time is required")))
                .andExpect(content().string(containsString("Expected attendance is required")));

        assertThat(eventRepository.count()).isZero();

        // Incomplete Draft succeeds via saveAsDraft=true
        MvcResult draftResult = mockMvc.perform(post("/events")
                        .with(user(organizer))
                        .with(csrf())
                        .param("name", "Early Draft Gathering")
                        .param("category", "RELIGIOUS_GATHERING")
                        .param("status", "PLANNING")
                        .param("saveAsDraft", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/events/*"))
                .andReturn();

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        Event draft = events.get(0);
        assertThat(draft.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(draftResult.getResponse().getRedirectedUrl()).isEqualTo("/events/" + draft.getId());

        List<EventChecklistItem> items = checklistRepository.findByEventIdWithSigner(draft.getId());
        assertThat(items).anyMatch(i -> "REL_QUEUE_SEPARATION".equals(i.getItemKey()));
    }

    @Test
    void fullEventLifecycleSearchReviewsChecklistDocumentsAndRbac() throws Exception {
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        UserDetails reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        UserDetails staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        var reviewerAccount = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        Long reviewerId = ((com.vyuha.auth.CustomUserDetails) reviewerAccount).getId();

        // 1. Organizer creates Campus Festival event
        mockMvc.perform(post("/events")
                        .with(user(organizer))
                        .with(csrf())
                        .param("name", "TechnoVyuha Annual Campus Festival")
                        .param("category", "COLLEGE_FESTIVAL")
                        .param("status", "PLANNING")
                        .param("startDateTime", "2026-12-10T16:00")
                        .param("endDateTime", "2026-12-10T22:00")
                        .param("expectedAttendance", "3500")
                        .param("locationName", "Central Quadrangle")
                        .param("locationAddress", "North Campus")
                        .param("assignedReviewerId", String.valueOf(reviewerId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/events/*"));

        // Create a second event in another category for filter testing
        mockMvc.perform(post("/events")
                        .with(user(organizer))
                        .with(csrf())
                        .param("name", "City Marathon Expo")
                        .param("category", "EXHIBITION")
                        .param("status", "IN_REVIEW")
                        .param("startDateTime", "2027-01-15T07:00")
                        .param("endDateTime", "2027-01-15T18:00")
                        .param("expectedAttendance", "1200")
                        .param("locationName", "Civic Hall"))
                .andExpect(status().is3xxRedirection());

        Event festival = eventRepository.findAll().stream()
                .filter(e -> e.getName().contains("TechnoVyuha"))
                .findFirst()
                .orElseThrow();

        // 2. Dashboard search & filters
        mockMvc.perform(get("/events").with(user(reviewer))
                        .param("q", "Quadrangle")
                        .param("category", "COLLEGE_FESTIVAL"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TechnoVyuha Annual Campus Festival")))
                .andExpect(content().string(not(containsString("City Marathon Expo"))))
                .andExpect(content().string(not(containsString("https://"))));

        // 3. Overview page renders offline with event tabs and zero "Certified"/"Stampede-Proof" badges
        mockMvc.perform(get("/events/" + festival.getId()).with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Overview")))
                .andExpect(content().string(containsString("Venue Plan")))
                .andExpect(content().string(containsString("Verify main stage front-of-house barrier clearance")))
                .andExpect(content().string(not(containsString("Stampede-Proof"))))
                .andExpect(content().string(not(containsString("https://"))));

        // 4. Checklist update & RBAC enforcement
        EventChecklistItem firstItem = checklistRepository.findByEventIdWithSigner(festival.getId()).get(0);

        mockMvc.perform(post("/events/" + festival.getId() + "/checklist/" + firstItem.getId())
                        .with(user(organizer))
                        .with(csrf())
                        .param("status", "COMPLETE")
                        .param("assigneeName", "Aarav Mehta")
                        .param("evidenceNotes", "Measured 4.5m clear egress."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/events/" + festival.getId()));

        // Organizer cannot sign off checklist items or issue formal safety reviews
        mockMvc.perform(post("/events/" + festival.getId() + "/checklist/" + firstItem.getId() + "/signoff")
                        .with(user(organizer))
                        .with(csrf())
                        .param("signedOff", "true"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/events/" + festival.getId() + "/reviews")
                        .with(user(organizer))
                        .with(csrf())
                        .param("reviewStatus", "REVIEW_DOCUMENTED")
                        .param("findingsSummary", "Attempted self-review"))
                .andExpect(status().isForbidden());

        // Reviewer can sign off and issue formal safety reviews; cannot edit event or upload docs
        mockMvc.perform(post("/events/" + festival.getId() + "/checklist/" + firstItem.getId() + "/signoff")
                        .with(user(reviewer))
                        .with(csrf())
                        .param("signedOff", "true"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/events/" + festival.getId() + "/reviews")
                        .with(user(reviewer))
                        .with(csrf())
                        .param("reviewStatus", "REVISIONS_REQUIRED")
                        .param("findingsSummary", "Widen east quadrangle corridor before gate opening."))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/events/new").with(user(reviewer))).andExpect(status().isForbidden());
        mockMvc.perform(get("/events").with(user(staff))).andExpect(status().isForbidden());

        // Organizer responds to the review record
        SafetyReviewRecord reviewRecord = reviewRepository.findByEventIdWithUsers(festival.getId()).get(0);
        mockMvc.perform(post("/events/" + festival.getId() + "/reviews/" + reviewRecord.getId() + "/respond")
                        .with(user(organizer))
                        .with(csrf())
                        .param("organizerResponse", "Relocated food stalls to west lawn; corridor widened."))
                .andExpect(status().is3xxRedirection());

        SafetyReviewRecord updatedRecord = reviewRepository.findById(reviewRecord.getId()).orElseThrow();
        assertThat(updatedRecord.getReviewStatus()).isEqualTo(SafetyReviewStatus.REVISIONS_REQUIRED);
        assertThat(updatedRecord.getOrganizerResponse()).contains("Relocated food stalls");

        // 5. Supporting Document upload & download security
        MockMultipartFile validPdf = new MockMultipartFile(
                "file", "campus-noc.pdf", "application/pdf", "%PDF-1.4\nPermit Draft".getBytes());
        mockMvc.perform(multipart("/events/" + festival.getId() + "/documents")
                        .file(validPdf)
                        .param("documentNote", "Fire safety permit draft")
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<SupportingDocument> docs = documentRepository.findByEventIdWithUploader(festival.getId());
        assertThat(docs).hasSize(1);
        SupportingDocument uploaded = docs.get(0);

        mockMvc.perform(get("/events/" + festival.getId() + "/documents/" + uploaded.getId() + "/download")
                        .with(user(reviewer)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().bytes("%PDF-1.4\nPermit Draft".getBytes()));

        // Disallowed HTML upload is rejected cleanly without creating a document record
        MockMultipartFile disallowedHtml = new MockMultipartFile(
                "file", "payload.html", "text/html", "<html></html>".getBytes());
        mockMvc.perform(multipart("/events/" + festival.getId() + "/documents")
                        .file(disallowedHtml)
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(documentRepository.findByEventIdWithUploader(festival.getId())).hasSize(1);

        // Reviewer cannot upload or delete documents
        mockMvc.perform(multipart("/events/" + festival.getId() + "/documents")
                        .file(validPdf)
                        .with(user(reviewer))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void editDateFilterCustomChecklistAndDeleteFlowWorkCleanly() throws Exception {
        UserDetails organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");

        mockMvc.perform(post("/events")
                        .with(user(organizer))
                        .with(csrf())
                        .param("name", "Spring Music Night")
                        .param("category", "CONCERT")
                        .param("status", "PLANNING")
                        .param("startDateTime", "2027-03-20T18:00")
                        .param("endDateTime", "2027-03-20T23:00")
                        .param("expectedAttendance", "2000")
                        .param("locationName", "Open Amphitheater"))
                .andExpect(status().is3xxRedirection());

        Event event = eventRepository.findAll().get(0);

        // Date range filter matches inside range and excludes outside range
        mockMvc.perform(get("/events").with(user(organizer))
                        .param("fromDate", "2027-03-01")
                        .param("toDate", "2027-03-31"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Spring Music Night")));

        mockMvc.perform(get("/events").with(user(organizer))
                        .param("fromDate", "2027-04-01")
                        .param("toDate", "2027-04-30"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Spring Music Night"))));

        // Edit event metadata
        mockMvc.perform(get("/events/" + event.getId() + "/edit").with(user(organizer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Edit Event Plan")));

        mockMvc.perform(post("/events/" + event.getId())
                        .with(user(organizer))
                        .with(csrf())
                        .param("name", "Spring Music Night Updated")
                        .param("category", "CONCERT")
                        .param("status", "IN_REVIEW")
                        .param("startDateTime", "2027-03-20T18:00")
                        .param("endDateTime", "2027-03-20T23:30")
                        .param("expectedAttendance", "2400")
                        .param("locationName", "Open Amphitheater"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/events/" + event.getId()));

        Event updated = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Spring Music Night Updated");
        assertThat(updated.getStatus()).isEqualTo(EventStatus.IN_REVIEW);
        assertThat(updated.getExpectedAttendance()).isEqualTo(2400);

        // Add custom checklist item
        mockMvc.perform(post("/events/" + event.getId() + "/checklist/custom")
                        .with(user(organizer))
                        .with(csrf())
                        .param("sectionName", "Audio Rigging")
                        .param("title", "Inspect delay tower ballast weights"))
                .andExpect(status().is3xxRedirection());

        assertThat(checklistRepository.findByEventIdWithSigner(event.getId()))
                .anyMatch(item -> "Inspect delay tower ballast weights".equals(item.getTitle()));

        // Upload and delete document
        MockMultipartFile png = new MockMultipartFile("file", "stage-map.png", "image/png", new byte[]{1, 2, 3, 4});
        mockMvc.perform(multipart("/events/" + event.getId() + "/documents")
                        .file(png)
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        SupportingDocument doc = documentRepository.findByEventIdWithUploader(event.getId()).get(0);
        mockMvc.perform(post("/events/" + event.getId() + "/documents/" + doc.getId() + "/delete")
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(documentRepository.findByEventIdWithUploader(event.getId())).isEmpty();

        // Delete event cascades cleanly
        mockMvc.perform(post("/events/" + event.getId() + "/delete")
                        .with(user(organizer))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/events"));
        assertThat(eventRepository.count()).isZero();
    }
}
