package com.vyuha.emergency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import com.vyuha.infrastructure.InfrastructureApiController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
 * MVC controller for the Phase 8 Emergency Preparedness Workspace & Failure-Mode Testing tab.
 */
@Controller
@RequestMapping("/events/{eventId}/emergency")
public class EmergencyController {

    private final EventService eventService;
    private final EmergencyService emergencyService;
    private final ObjectMapper objectMapper;

    public EmergencyController(EventService eventService,
                               EmergencyService emergencyService,
                               ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.emergencyService = emergencyService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public String emergencyWorkspace(@PathVariable("eventId") Long eventId,
                                     Authentication authentication,
                                     Model model) throws JsonProcessingException {
        boolean canEdit = InfrastructureApiController.hasEditRole(authentication);
        Event event = eventService.getEventOrThrow(eventId);
        EmergencyDtos.EmergencyWorkspaceDto workspace = emergencyService.getWorkspace(eventId, canEdit);

        model.addAttribute("event", event);
        model.addAttribute("workspace", workspace);
        model.addAttribute("initialStateJson", objectMapper.writeValueAsString(workspace));
        model.addAttribute("assetTypes", EmergencyAssetType.values());
        model.addAttribute("canEdit", canEdit);
        return "emergency/index";
    }

    @PostMapping("/assets")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String createAsset(@PathVariable("eventId") Long eventId,
                              @RequestParam("assetType") EmergencyAssetType assetType,
                              @RequestParam("label") String label,
                              @RequestParam(value = "geometryWkt", required = false) String geometryWkt,
                              @RequestParam(value = "x", required = false) Double x,
                              @RequestParam(value = "y", required = false) Double y,
                              @RequestParam(value = "minWidthM", required = false) Double minWidthM,
                              @RequestParam(value = "capacityNotes", required = false) String capacityNotes,
                              @RequestParam(value = "notes", required = false) String notes,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        try {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Emergency asset label is required.");
            }
            EmergencyDtos.EmergencyAssetRequest req = new EmergencyDtos.EmergencyAssetRequest(
                    assetType, label, geometryWkt, null, x, y, minWidthM, capacityNotes, notes);
            emergencyService.createAsset(eventId, req, InfrastructureApiController.hasEditRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Emergency asset saved and validated.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/emergency";
    }

    @PostMapping("/assets/{assetId}/delete")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String deleteAsset(@PathVariable("eventId") Long eventId,
                              @PathVariable("assetId") Long assetId,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        try {
            emergencyService.deleteAsset(eventId, assetId, InfrastructureApiController.hasEditRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Emergency asset removed.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/emergency";
    }

    @PostMapping("/contacts")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String createContact(@PathVariable("eventId") Long eventId,
                                @RequestParam("agencyName") String agencyName,
                                @RequestParam("contactName") String contactName,
                                @RequestParam("phone") String phone,
                                @RequestParam("role") String role,
                                @RequestParam(value = "priorityOrder", required = false) Integer priorityOrder,
                                @RequestParam(value = "notes", required = false) String notes,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        try {
            EmergencyDtos.EmergencyContactRequest req = new EmergencyDtos.EmergencyContactRequest(
                    agencyName, contactName, phone, role, priorityOrder, notes);
            emergencyService.createContact(eventId, req, InfrastructureApiController.hasEditRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Emergency contact added to directory.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/emergency";
    }

    @PostMapping("/contacts/{contactId}/update")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String updateContact(@PathVariable("eventId") Long eventId,
                                @PathVariable("contactId") Long contactId,
                                @RequestParam("agencyName") String agencyName,
                                @RequestParam("contactName") String contactName,
                                @RequestParam("phone") String phone,
                                @RequestParam("role") String role,
                                @RequestParam(value = "priorityOrder", required = false) Integer priorityOrder,
                                @RequestParam(value = "notes", required = false) String notes,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        try {
            EmergencyDtos.EmergencyContactRequest req = new EmergencyDtos.EmergencyContactRequest(
                    agencyName, contactName, phone, role, priorityOrder, notes);
            emergencyService.updateContact(
                    eventId, contactId, req, InfrastructureApiController.hasEditRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Emergency contact updated.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/emergency";
    }

    @PostMapping("/contacts/{contactId}/delete")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String deleteContact(@PathVariable("eventId") Long eventId,
                                @PathVariable("contactId") Long contactId,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        try {
            emergencyService.deleteContact(
                    eventId, contactId, InfrastructureApiController.hasEditRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Emergency contact removed.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/emergency";
    }

    private String extractError(Exception ex) {
        if (ex instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        return ex.getMessage() != null ? ex.getMessage() : "Unable to update emergency preparedness item.";
    }
}
