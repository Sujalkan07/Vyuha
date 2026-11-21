package com.vyuha.monitoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.auth.CustomUserDetails;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import com.vyuha.infrastructure.InfrastructureApiController;
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
 * MVC controller for the Phase 10 Live Event Monitoring Control Centre (/events/{id}/live).
 */
@Controller
@RequestMapping("/events/{eventId}/live")
public class LiveMonitoringController {

    private final EventService eventService;
    private final LiveMonitoringService monitoringService;
    private final ObjectMapper objectMapper;

    public LiveMonitoringController(EventService eventService,
                                    LiveMonitoringService monitoringService,
                                    ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.monitoringService = monitoringService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public String controlCentre(@PathVariable("eventId") Long eventId,
                                @RequestParam(value = "mode", required = false, defaultValue = "LIVE") String mode,
                                @RequestParam(value = "source", required = false) OccupancyDataSource source,
                                Authentication authentication,
                                Model model) throws JsonProcessingException {
        boolean canEdit = InfrastructureApiController.hasEditRole(authentication);
        boolean canAcknowledge = true;
        Event event = eventService.getEventOrThrow(eventId);
        MonitoringDtos.ControlCentreDto controlCentre = monitoringService.getControlCentre(
                eventId, mode, source, canEdit, canAcknowledge);

        model.addAttribute("event", event);
        model.addAttribute("controlCentre", controlCentre);
        model.addAttribute("initialStateJson", objectMapper.writeValueAsString(controlCentre));
        model.addAttribute("canEdit", canEdit);
        model.addAttribute("canAcknowledge", canAcknowledge);
        model.addAttribute("scanActions", PassScanAction.values());
        return "monitor/index";
    }

    @PostMapping("/readings")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String submitManualCount(@PathVariable("eventId") Long eventId,
                                    @RequestParam("zoneId") Long zoneId,
                                    @RequestParam("countValue") Integer countValue,
                                    @RequestParam(value = "note", required = false) String note,
                                    @AuthenticationPrincipal CustomUserDetails currentUser,
                                    Authentication authentication,
                                    RedirectAttributes redirectAttributes) {
        try {
            if (countValue == null || countValue < 0) {
                throw new IllegalArgumentException("Occupancy count must be zero or greater.");
            }
            monitoringService.recordManualCount(
                    eventId,
                    currentUser != null ? currentUser.getId() : null,
                    currentUser != null ? currentUser.getFullName() : "Organizer",
                    new MonitoringDtos.ManualCountRequest(zoneId, countValue, note),
                    InfrastructureApiController.hasEditRole(authentication),
                    true);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Manual staff zone count recorded (tagged MANUAL_STAFF).");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/live?mode=LIVE&source=MANUAL_STAFF";
    }

    @PostMapping("/passes/batch")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String generatePassBatch(@PathVariable("eventId") Long eventId,
                                    @RequestParam(value = "count", defaultValue = "10") Integer count,
                                    @RequestParam(value = "labelPrefix", required = false) String labelPrefix,
                                    Authentication authentication,
                                    RedirectAttributes redirectAttributes) {
        try {
            if (count == null || count < 1 || count > 200) {
                throw new IllegalArgumentException("Batch size must be between 1 and 200 passes.");
            }
            monitoringService.generatePassBatch(
                    eventId,
                    new MonitoringDtos.GeneratePassBatchRequest(count, labelPrefix),
                    InfrastructureApiController.hasEditRole(authentication),
                    true);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Generated " + count + " unique attendee passes with offline QR codes.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/live?mode=LIVE&source=PASS_SCAN";
    }

    @PostMapping("/passes/scan")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String scanPass(@PathVariable("eventId") Long eventId,
                           @RequestParam("passCode") String passCode,
                           @RequestParam("action") PassScanAction action,
                           @RequestParam(value = "gateId", required = false) Long gateId,
                           @RequestParam(value = "zoneId", required = false) Long zoneId,
                           @AuthenticationPrincipal CustomUserDetails currentUser,
                           Authentication authentication,
                           RedirectAttributes redirectAttributes) {
        try {
            monitoringService.scanPass(
                    eventId,
                    currentUser != null ? currentUser.getId() : null,
                    currentUser != null ? currentUser.getFullName() : "Gate Operator",
                    new MonitoringDtos.ScanPassRequest(passCode, action, gateId, zoneId),
                    InfrastructureApiController.hasEditRole(authentication),
                    true);
            redirectAttributes.addFlashAttribute("successMessage",
                    action.getDisplayName() + " recorded for pass " + passCode.trim().toUpperCase() + ".");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/live?mode=LIVE&source=PASS_SCAN";
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String saveRule(@PathVariable("eventId") Long eventId,
                           @RequestParam(value = "zoneId", required = false) Long zoneId,
                           @RequestParam("warningThresholdPct") Double warningThresholdPct,
                           @RequestParam("criticalThresholdPct") Double criticalThresholdPct,
                           @RequestParam("staleAfterMinutes") Integer staleAfterMinutes,
                           Authentication authentication,
                           RedirectAttributes redirectAttributes) {
        try {
            monitoringService.upsertAlertRule(
                    eventId,
                    new MonitoringDtos.AlertRuleRequest(zoneId, warningThresholdPct, criticalThresholdPct, staleAfterMinutes),
                    InfrastructureApiController.hasEditRole(authentication),
                    true);
            redirectAttributes.addFlashAttribute("successMessage", "Monitoring alert & staleness thresholds updated.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/live";
    }

    @PostMapping("/alerts/{alertId}/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public String acknowledgeAlert(@PathVariable("eventId") Long eventId,
                                   @PathVariable("alertId") Long alertId,
                                   @AuthenticationPrincipal CustomUserDetails currentUser,
                                   Authentication authentication,
                                   RedirectAttributes redirectAttributes) {
        try {
            monitoringService.acknowledgeAlert(
                    eventId,
                    alertId,
                    currentUser != null ? currentUser.getId() : null,
                    currentUser != null ? currentUser.getFullName() : "Reviewer",
                    InfrastructureApiController.hasEditRole(authentication),
                    true);
            redirectAttributes.addFlashAttribute("successMessage", "Alert acknowledged.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/live";
    }

    @PostMapping("/alerts/{alertId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String resolveAlert(@PathVariable("eventId") Long eventId,
                               @PathVariable("alertId") Long alertId,
                               @AuthenticationPrincipal CustomUserDetails currentUser,
                               Authentication authentication,
                               RedirectAttributes redirectAttributes) {
        try {
            monitoringService.resolveAlert(
                    eventId,
                    alertId,
                    currentUser != null ? currentUser.getId() : null,
                    currentUser != null ? currentUser.getFullName() : "Organizer",
                    InfrastructureApiController.hasEditRole(authentication),
                    true);
            redirectAttributes.addFlashAttribute("successMessage", "Alert resolved.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/live";
    }

    private String extractError(Exception ex) {
        if (ex instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        return ex.getMessage() != null ? ex.getMessage() : "Monitoring operation failed.";
    }
}
