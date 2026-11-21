package com.vyuha.event;

import com.vyuha.auth.CustomUserDetails;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * MVC controller for Phase 2 Event Management, Category Checklists,
 * Safety Review Logs, and Supporting Document uploads.
 */
@Controller
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;
    private final ChecklistTemplateService checklistTemplateService;
    private final SafetyReviewService safetyReviewService;
    private final SupportingDocumentService supportingDocumentService;

    public EventController(EventService eventService,
                           ChecklistTemplateService checklistTemplateService,
                           SafetyReviewService safetyReviewService,
                           SupportingDocumentService supportingDocumentService) {
        this.eventService = eventService;
        this.checklistTemplateService = checklistTemplateService;
        this.safetyReviewService = safetyReviewService;
        this.supportingDocumentService = supportingDocumentService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public String listEvents(@RequestParam(value = "q", required = false) String query,
                             @RequestParam(value = "category", required = false) EventCategory category,
                             @RequestParam(value = "status", required = false) EventStatus status,
                             @RequestParam(value = "fromDate", required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                             @RequestParam(value = "toDate", required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                             @AuthenticationPrincipal CustomUserDetails currentUser,
                             Model model) {
        EventSearchCriteria criteria = new EventSearchCriteria(query, category, status, fromDate, toDate);
        List<Event> events = eventService.searchEvents(criteria);
        Map<Long, SafetyReviewStatus> latestReviews = safetyReviewService.getLatestReviewStatuses(events);

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("events", events);
        model.addAttribute("latestReviews", latestReviews);
        model.addAttribute("criteria", criteria);
        model.addAttribute("categories", EventCategory.values());
        model.addAttribute("statuses", EventStatus.values());
        return "event/index";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String newEventForm(Model model) {
        model.addAttribute("eventForm", new EventForm());
        model.addAttribute("eventId", null);
        model.addAttribute("isEdit", false);
        populateFormOptions(model);
        return "event/form";
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String createEvent(@Valid @ModelAttribute("eventForm") EventForm form,
                              BindingResult bindingResult,
                              @RequestParam(value = "saveAsDraft", defaultValue = "false") boolean saveAsDraft,
                              @AuthenticationPrincipal CustomUserDetails currentUser,
                              Model model,
                              RedirectAttributes redirectAttributes) {
        if (saveAsDraft) {
            form.setStatus(EventStatus.DRAFT);
        }
        eventService.validateEventForm(form, bindingResult);
        if (bindingResult.hasErrors()) {
            model.addAttribute("eventId", null);
            model.addAttribute("isEdit", false);
            populateFormOptions(model);
            return "event/form";
        }

        Event created = eventService.createEvent(form, currentUser.getId());
        redirectAttributes.addFlashAttribute("successMessage",
                "Event \"" + created.getName() + "\" created with "
                        + created.getCategory().getDisplayName() + " safety checklist.");
        return "redirect:/events/" + created.getId();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public String overview(@PathVariable("id") Long id,
                           @AuthenticationPrincipal CustomUserDetails currentUser,
                           Model model) {
        Event event = eventService.getEventOrThrow(id);
        List<EventChecklistItem> checklist = eventService.getChecklist(id);
        ChecklistProgress progress = checklistTemplateService.computeProgress(checklist);
        List<SafetyReviewRecord> reviews = safetyReviewService.getReviewsForEvent(id);
        List<SupportingDocument> documents = supportingDocumentService.getDocumentsForEvent(id);

        SafetyReviewStatus latestReviewStatus = reviews.isEmpty() ? null : reviews.get(0).getReviewStatus();

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("event", event);
        model.addAttribute("checklist", checklist);
        model.addAttribute("progress", progress);
        model.addAttribute("checklistStatuses", ChecklistItemStatus.values());
        model.addAttribute("reviews", reviews);
        model.addAttribute("latestReviewStatus", latestReviewStatus);
        model.addAttribute("reviewStatuses", SafetyReviewStatus.values());
        model.addAttribute("documents", documents);
        return "event/overview";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String editEventForm(@PathVariable("id") Long id, Model model) {
        Event event = eventService.getEventOrThrow(id);
        model.addAttribute("eventForm", EventForm.fromEntity(event));
        model.addAttribute("eventId", id);
        model.addAttribute("eventName", event.getName());
        model.addAttribute("isEdit", true);
        populateFormOptions(model);
        return "event/form";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String updateEvent(@PathVariable("id") Long id,
                              @Valid @ModelAttribute("eventForm") EventForm form,
                              BindingResult bindingResult,
                              @RequestParam(value = "saveAsDraft", defaultValue = "false") boolean saveAsDraft,
                              Model model,
                              RedirectAttributes redirectAttributes) {
        if (saveAsDraft) {
            form.setStatus(EventStatus.DRAFT);
        }
        eventService.validateEventForm(form, bindingResult);
        if (bindingResult.hasErrors()) {
            model.addAttribute("eventId", id);
            model.addAttribute("isEdit", true);
            populateFormOptions(model);
            return "event/form";
        }

        Event updated = eventService.updateEvent(id, form);
        redirectAttributes.addFlashAttribute("successMessage",
                "Event \"" + updated.getName() + "\" updated.");
        return "redirect:/events/" + id;
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String deleteEvent(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        Event event = eventService.getEventOrThrow(id);
        String name = event.getName();
        eventService.deleteEvent(id);
        redirectAttributes.addFlashAttribute("successMessage", "Event \"" + name + "\" deleted.");
        return "redirect:/events";
    }

    @PostMapping("/{id}/checklist/{itemId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String updateChecklistItem(@PathVariable("id") Long id,
                                      @PathVariable("itemId") Long itemId,
                                      @RequestParam("status") ChecklistItemStatus status,
                                      @RequestParam(value = "assigneeName", required = false) String assigneeName,
                                      @RequestParam(value = "evidenceNotes", required = false) String evidenceNotes,
                                      RedirectAttributes redirectAttributes) {
        eventService.updateChecklistItem(id, itemId, status, assigneeName, evidenceNotes);
        redirectAttributes.addFlashAttribute("successMessage", "Checklist item updated.");
        return "redirect:/events/" + id;
    }

    @PostMapping("/{id}/checklist/custom")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String addCustomChecklistItem(@PathVariable("id") Long id,
                                         @RequestParam(value = "sectionName", required = false) String sectionName,
                                         @RequestParam("title") String title,
                                         @RequestParam(value = "guidanceNote", required = false) String guidanceNote,
                                         RedirectAttributes redirectAttributes) {
        try {
            eventService.addCustomChecklistItem(id, sectionName, title, guidanceNote);
            redirectAttributes.addFlashAttribute("successMessage", "Custom checklist item added.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        }
        return "redirect:/events/" + id;
    }

    @PostMapping("/{id}/checklist/{itemId}/signoff")
    @PreAuthorize("hasAnyRole('ADMIN', 'SAFETY_REVIEWER')")
    public String toggleChecklistSignOff(@PathVariable("id") Long id,
                                         @PathVariable("itemId") Long itemId,
                                         @RequestParam("signedOff") boolean signedOff,
                                         @AuthenticationPrincipal CustomUserDetails currentUser,
                                         RedirectAttributes redirectAttributes) {
        eventService.toggleChecklistSignOff(id, itemId, currentUser.getId(), signedOff);
        redirectAttributes.addFlashAttribute("successMessage",
                signedOff ? "Checklist item signed off by reviewer." : "Checklist reviewer sign-off cleared.");
        return "redirect:/events/" + id;
    }

    @PostMapping("/{id}/reviews")
    @PreAuthorize("hasAnyRole('ADMIN', 'SAFETY_REVIEWER')")
    public String issueSafetyReview(@PathVariable("id") Long id,
                                    @RequestParam("reviewStatus") SafetyReviewStatus reviewStatus,
                                    @RequestParam("findingsSummary") String findingsSummary,
                                    @AuthenticationPrincipal CustomUserDetails currentUser,
                                    RedirectAttributes redirectAttributes) {
        try {
            safetyReviewService.issueReview(id, currentUser.getId(), reviewStatus, findingsSummary);
            redirectAttributes.addFlashAttribute("successMessage", "Formal safety review entry recorded.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        }
        return "redirect:/events/" + id;
    }

    @PostMapping("/{id}/reviews/{reviewId}/respond")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String respondToReview(@PathVariable("id") Long id,
                                  @PathVariable("reviewId") Long reviewId,
                                  @RequestParam("organizerResponse") String organizerResponse,
                                  @AuthenticationPrincipal CustomUserDetails currentUser,
                                  RedirectAttributes redirectAttributes) {
        try {
            safetyReviewService.addOrganizerResponse(id, reviewId, currentUser.getId(), organizerResponse);
            redirectAttributes.addFlashAttribute("successMessage", "Organizer response recorded on review entry.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        }
        return "redirect:/events/" + id;
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String uploadDocument(@PathVariable("id") Long id,
                                 @RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "documentNote", required = false) String documentNote,
                                 @AuthenticationPrincipal CustomUserDetails currentUser,
                                 RedirectAttributes redirectAttributes) {
        try {
            SupportingDocument saved = supportingDocumentService.storeDocument(
                    id, currentUser.getId(), file, documentNote);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Uploaded \"" + saved.getOriginalFilename() + "\".");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        }
        return "redirect:/events/" + id;
    }

    @GetMapping("/{id}/documents/{docId}/download")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<Resource> downloadDocument(@PathVariable("id") Long id,
                                                     @PathVariable("docId") Long docId) {
        SupportingDocumentService.DocumentDownload download = supportingDocumentService.loadDocument(id, docId);
        SupportingDocument meta = download.metadata();

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(meta.getOriginalFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(meta.getContentType()))
                .contentLength(meta.getFileSizeBytes())
                .body(download.resource());
    }

    @PostMapping("/{id}/documents/{docId}/delete")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String deleteDocument(@PathVariable("id") Long id,
                                 @PathVariable("docId") Long docId,
                                 RedirectAttributes redirectAttributes) {
        supportingDocumentService.deleteDocument(id, docId);
        redirectAttributes.addFlashAttribute("successMessage", "Supporting document removed.");
        return "redirect:/events/" + id;
    }

    private void populateFormOptions(Model model) {
        model.addAttribute("categories", EventCategory.values());
        model.addAttribute("statuses", EventStatus.values());
        model.addAttribute("reviewers", eventService.getAvailableReviewers());
    }
}
