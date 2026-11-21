package com.vyuha.event;

import com.vyuha.auth.AppUser;
import com.vyuha.auth.Role;
import com.vyuha.auth.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;
import org.springframework.web.server.ResponseStatusException;

/**
 * Core service managing Event CRUD, draft lifecycle, search/filtering, and checklist execution.
 */
@Service
public class EventService {

    private final EventRepository eventRepository;
    private final EventChecklistItemRepository checklistRepository;
    private final UserRepository userRepository;
    private final ChecklistTemplateService checklistTemplateService;
    private final SupportingDocumentService supportingDocumentService;

    public EventService(EventRepository eventRepository,
                        EventChecklistItemRepository checklistRepository,
                        UserRepository userRepository,
                        ChecklistTemplateService checklistTemplateService,
                        SupportingDocumentService supportingDocumentService) {
        this.eventRepository = eventRepository;
        this.checklistRepository = checklistRepository;
        this.userRepository = userRepository;
        this.checklistTemplateService = checklistTemplateService;
        this.supportingDocumentService = supportingDocumentService;
    }

    @Transactional(readOnly = true)
    public List<Event> searchEvents(EventSearchCriteria criteria) {
        String searchPattern = null;
        EventCategory category = null;
        EventStatus status = null;
        LocalDateTime fromDt = null;
        LocalDateTime toDt = null;

        if (criteria != null) {
            if (criteria.query() != null && !criteria.query().isBlank()) {
                searchPattern = "%" + criteria.query().trim().toLowerCase(Locale.ROOT) + "%";
            }
            category = criteria.category();
            status = criteria.status();
            if (criteria.fromDate() != null) {
                fromDt = criteria.fromDate().atStartOfDay();
            }
            if (criteria.toDate() != null) {
                toDt = criteria.toDate().plusDays(1).atStartOfDay().minusNanos(1);
            }
        }
        return eventRepository.searchEvents(searchPattern, category, status, fromDt, toDt);
    }

    @Transactional(readOnly = true)
    public Event getEventOrThrow(Long eventId) {
        return eventRepository.findByIdWithUsers(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found."));
    }

    @Transactional(readOnly = true)
    public List<EventChecklistItem> getChecklist(Long eventId) {
        return checklistRepository.findByEventIdWithSigner(eventId);
    }

    @Transactional(readOnly = true)
    public List<AppUser> getAvailableReviewers() {
        return userRepository.findByRoleAndEnabledTrueOrderByFullNameAsc(Role.SAFETY_REVIEWER);
    }

    public void validateEventForm(EventForm form, BindingResult bindingResult) {
        if (form.getStartDateTime() != null && form.getEndDateTime() != null
                && form.getEndDateTime().isBefore(form.getStartDateTime())) {
            bindingResult.rejectValue("endDateTime", "endBeforeStart",
                    "End date/time must be after start date/time.");
        }

        if (form.getStatus() != EventStatus.DRAFT) {
            if (form.getStartDateTime() == null) {
                bindingResult.rejectValue("startDateTime", "required",
                        "Start date/time is required when status is not Draft.");
            }
            if (form.getEndDateTime() == null) {
                bindingResult.rejectValue("endDateTime", "required",
                        "End date/time is required when status is not Draft.");
            }
            if (form.getExpectedAttendance() == null) {
                bindingResult.rejectValue("expectedAttendance", "required",
                        "Expected attendance is required when status is not Draft.");
            }
            if (form.getLocationName() == null || form.getLocationName().isBlank()) {
                bindingResult.rejectValue("locationName", "required",
                        "Location name is required when status is not Draft.");
            }
        }
    }

    @Transactional
    public Event createEvent(EventForm form, Long organizerUserId) {
        AppUser organizer = userRepository.findById(organizerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Organizer account not found."));

        Event event = new Event();
        event.setOrganizer(organizer);
        applyFormFields(event, form);

        Event saved = eventRepository.save(event);
        List<EventChecklistItem> initialItems = checklistTemplateService.generateInitialItems(saved);
        checklistRepository.saveAll(initialItems);
        return saved;
    }

    @Transactional
    public Event updateEvent(Long eventId, EventForm form) {
        Event event = getEventOrThrow(eventId);
        applyFormFields(event, form);
        return eventRepository.save(event);
    }

    @Transactional
    public void deleteEvent(Long eventId) {
        Event event = getEventOrThrow(eventId);
        eventRepository.delete(event);
        supportingDocumentService.deleteEventDirectory(eventId);
    }

    @Transactional
    public EventChecklistItem updateChecklistItem(Long eventId,
                                                  Long itemId,
                                                  ChecklistItemStatus status,
                                                  String assigneeName,
                                                  String evidenceNotes) {
        EventChecklistItem item = checklistRepository.findByIdAndEventId(itemId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Checklist item not found."));

        item.setStatus(status != null ? status : ChecklistItemStatus.NOT_STARTED);
        item.setAssigneeName(trimToNull(assigneeName));
        item.setEvidenceNotes(trimToNull(evidenceNotes));

        if (item.getStatus() == ChecklistItemStatus.NOT_STARTED || item.getStatus() == ChecklistItemStatus.IN_PROGRESS) {
            item.setReviewerSignedOff(false);
            item.setSignedOffBy(null);
            item.setSignedOffAt(null);
        }
        return checklistRepository.save(item);
    }

    @Transactional
    public EventChecklistItem toggleChecklistSignOff(Long eventId, Long itemId, Long reviewerUserId, boolean signedOff) {
        EventChecklistItem item = checklistRepository.findByIdAndEventId(itemId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Checklist item not found."));
        AppUser reviewer = userRepository.findById(reviewerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Reviewer account not found."));

        item.setReviewerSignedOff(signedOff);
        if (signedOff) {
            item.setSignedOffBy(reviewer);
            item.setSignedOffAt(Instant.now());
        } else {
            item.setSignedOffBy(null);
            item.setSignedOffAt(null);
        }
        return checklistRepository.save(item);
    }

    @Transactional
    public EventChecklistItem addCustomChecklistItem(Long eventId,
                                                     String sectionName,
                                                     String title,
                                                     String guidanceNote) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Checklist item title is required.");
        }
        Event event = getEventOrThrow(eventId);
        int nextOrder = checklistRepository.countByEventId(eventId) + 1;

        EventChecklistItem item = new EventChecklistItem();
        item.setEvent(event);
        item.setItemKey("CUSTOM_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        item.setSectionName(sectionName != null && !sectionName.isBlank() ? sectionName.trim() : "Custom Planning Item");
        item.setTitle(title.trim());
        item.setGuidanceNote(trimToNull(guidanceNote));
        item.setStatus(ChecklistItemStatus.NOT_STARTED);
        item.setSortOrder(nextOrder);
        return checklistRepository.save(item);
    }

    private void applyFormFields(Event event, EventForm form) {
        event.setName(form.getName().trim());
        event.setCategory(form.getCategory());
        event.setStatus(form.getStatus());
        event.setStartDateTime(form.getStartDateTime());
        event.setEndDateTime(form.getEndDateTime());
        event.setScheduleTimeline(trimToNull(form.getScheduleTimeline()));
        event.setExpectedAttendance(form.getExpectedAttendance());
        event.setLocationName(trimToNull(form.getLocationName()));
        event.setLocationAddress(trimToNull(form.getLocationAddress()));
        event.setDescription(trimToNull(form.getDescription()));

        if (form.getAssignedReviewerId() != null) {
            AppUser reviewer = userRepository.findById(form.getAssignedReviewerId())
                    .filter(u -> u.getRole() == Role.SAFETY_REVIEWER || u.getRole() == Role.ADMIN)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Selected user is not an eligible Safety Reviewer."));
            event.setAssignedReviewer(reviewer);
        } else {
            event.setAssignedReviewer(null);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
