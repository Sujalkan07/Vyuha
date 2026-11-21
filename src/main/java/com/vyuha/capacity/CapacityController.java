package com.vyuha.capacity;

import com.vyuha.auth.CustomUserDetails;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.operations.IncidentStatus;
import com.vyuha.operations.OperationsDtos;
import com.vyuha.operations.OperationsService;
import com.vyuha.operations.StaffRoleType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * MVC controller for the Capacity & Staff tab (Preliminary Capacity Review,
 * plus Phase 7 Arrival Waves & Queues and Staff & Incidents sub-tabs).
 */
@Controller
@RequestMapping("/events/{eventId}/capacity")
public class CapacityController {

    private final EventService eventService;
    private final CapacityService capacityService;
    private final OperationsService operationsService;

    public CapacityController(EventService eventService,
                              CapacityService capacityService,
                              OperationsService operationsService) {
        this.eventService = eventService;
        this.capacityService = capacityService;
        this.operationsService = operationsService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public String viewCapacity(@PathVariable("eventId") Long eventId,
                               @RequestParam(value = "tab", defaultValue = "review") String tab,
                               Authentication authentication,
                               Model model) {
        Event event = eventService.getEventOrThrow(eventId);
        boolean canEditAssumptions = hasEditRole(authentication);
        boolean canVerifyReview = hasVerifyRole(authentication);
        String activeSubTab = switch (tab == null ? "review" : tab.trim().toLowerCase()) {
            case "arrivals" -> "arrivals";
            case "staff" -> "staff";
            default -> "review";
        };
        CapacityDtos.CapacityPageView view = capacityService.getCapacityView(
                eventId, canEditAssumptions, canVerifyReview);
        OperationsDtos.OperationsWorkspaceDto ops = operationsService.getWorkspace(
                eventId, canEditAssumptions, canVerifyReview);

        model.addAttribute("event", event);
        model.addAttribute("view", view);
        model.addAttribute("ops", ops);
        model.addAttribute("activeSubTab", activeSubTab);
        model.addAttribute("assumptionReviewStatuses", AssumptionReviewStatus.values());
        model.addAttribute("capacityReviewStatuses", CapacityReviewStatus.values());
        model.addAttribute("staffRoles", StaffRoleType.values());
        model.addAttribute("incidentSeverities", FindingSeverity.values());
        model.addAttribute("incidentStatuses", IncidentStatus.values());
        return "capacity/index";
    }

    @PostMapping("/parameters")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String saveParameters(@PathVariable("eventId") Long eventId,
                                 @RequestParam(value = "officialVenueLimit", required = false) Integer officialVenueLimit,
                                 @RequestParam(value = "officialLimitSource", required = false) String officialLimitSource,
                                 @RequestParam(value = "entryThroughputPerMin", required = false) Double entryThroughputPerMin,
                                 @RequestParam(value = "ingressWindowMinutes", required = false) Integer ingressWindowMinutes,
                                 @RequestParam(value = "exitThroughputPerMin", required = false) Double exitThroughputPerMin,
                                 @RequestParam(value = "egressWindowMinutes", required = false) Integer egressWindowMinutes,
                                 @RequestParam(value = "holdingBufferCapacity", required = false) Integer holdingBufferCapacity,
                                 @RequestParam(value = "notes", required = false) String notes,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        try {
            if (officialVenueLimit != null && officialVenueLimit <= 0) {
                throw new IllegalArgumentException("Official venue limit must be a positive integer.");
            }
            if (entryThroughputPerMin != null && entryThroughputPerMin <= 0) {
                throw new IllegalArgumentException("Entry throughput must be greater than zero.");
            }
            if (ingressWindowMinutes != null && ingressWindowMinutes <= 0) {
                throw new IllegalArgumentException("Ingress window must be at least 1 minute.");
            }
            if (exitThroughputPerMin != null && exitThroughputPerMin <= 0) {
                throw new IllegalArgumentException("Exit throughput must be greater than zero.");
            }
            if (egressWindowMinutes != null && egressWindowMinutes <= 0) {
                throw new IllegalArgumentException("Egress window must be at least 1 minute.");
            }
            if (holdingBufferCapacity != null && holdingBufferCapacity < 0) {
                throw new IllegalArgumentException("Holding buffer capacity cannot be negative.");
            }

            CapacityDtos.CapacityParametersRequest req = new CapacityDtos.CapacityParametersRequest(
                    officialVenueLimit, officialLimitSource, entryThroughputPerMin, ingressWindowMinutes,
                    exitThroughputPerMin, egressWindowMinutes, holdingBufferCapacity, notes);
            capacityService.updateParameters(
                    eventId, req, hasEditRole(authentication), hasVerifyRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage",
                    "Capacity parameters updated and governing constraint recalculated.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/capacity";
    }

    @PostMapping("/zones/{zoneId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String saveZoneAssumption(@PathVariable("eventId") Long eventId,
                                     @PathVariable("zoneId") Long zoneId,
                                     @RequestParam("densityPersonsPerSqm") Double densityPersonsPerSqm,
                                     @RequestParam("sourceCitation") String sourceCitation,
                                     @RequestParam(value = "approvedByName", required = false) String approvedByName,
                                     Authentication authentication,
                                     RedirectAttributes redirectAttributes) {
        try {
            CapacityDtos.ZoneAssumptionRequest req = new CapacityDtos.ZoneAssumptionRequest(
                    densityPersonsPerSqm, sourceCitation, approvedByName);
            capacityService.saveZoneAssumption(
                    eventId, zoneId, req, hasEditRole(authentication), hasVerifyRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Zone density assumption saved.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        }
        return "redirect:/events/" + eventId + "/capacity";
    }

    @PostMapping("/zones/{zoneId}/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'SAFETY_REVIEWER')")
    public String reviewZoneAssumption(@PathVariable("eventId") Long eventId,
                                       @PathVariable("zoneId") Long zoneId,
                                       @RequestParam("reviewStatus") AssumptionReviewStatus reviewStatus,
                                       @RequestParam(value = "reviewerNote", required = false) String reviewerNote,
                                       @AuthenticationPrincipal CustomUserDetails currentUser,
                                       Authentication authentication,
                                       RedirectAttributes redirectAttributes) {
        try {
            CapacityDtos.ReviewerAssumptionAction action = new CapacityDtos.ReviewerAssumptionAction(
                    reviewStatus, reviewerNote);
            capacityService.reviewZoneAssumption(
                    eventId, zoneId, currentUser.getId(), action,
                    hasEditRole(authentication), hasVerifyRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage",
                    "Zone assumption review status updated to " + reviewStatus.getDisplayName() + ".");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        }
        return "redirect:/events/" + eventId + "/capacity";
    }

    @PostMapping("/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'SAFETY_REVIEWER')")
    public String reviewOverallCapacity(@PathVariable("eventId") Long eventId,
                                        @RequestParam("reviewStatus") CapacityReviewStatus reviewStatus,
                                        @RequestParam(value = "reviewerNotes", required = false) String reviewerNotes,
                                        @AuthenticationPrincipal CustomUserDetails currentUser,
                                        Authentication authentication,
                                        RedirectAttributes redirectAttributes) {
        try {
            CapacityDtos.ReviewerOverallAction action = new CapacityDtos.ReviewerOverallAction(
                    reviewStatus, reviewerNotes);
            capacityService.reviewOverallCapacity(
                    eventId, currentUser.getId(), action,
                    hasEditRole(authentication), hasVerifyRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage",
                    "Overall capacity review status recorded as " + reviewStatus.getDisplayName() + ".");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        }
        return "redirect:/events/" + eventId + "/capacity";
    }

    static boolean hasEditRole(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_ORGANIZER".equals(a.getAuthority()));
    }

    static boolean hasVerifyRole(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_SAFETY_REVIEWER".equals(a.getAuthority()));
    }
}
