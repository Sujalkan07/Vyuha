package com.vyuha.operations;

import com.vyuha.auth.CustomUserDetails;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureApiController;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * MVC form controller for Phase 7 sub-tabs under Capacity & Staff:
 * Arrival Waves & Queues (?tab=arrivals) and Staff & Incidents (?tab=staff).
 */
@Controller
@RequestMapping("/events/{eventId}/capacity")
public class OperationsController {

    private final OperationsService operationsService;

    public OperationsController(OperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @PostMapping("/waves")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String createWave(@PathVariable("eventId") Long eventId,
                             @RequestParam(value = "label", required = false) String label,
                             @RequestParam("startMinute") Integer startMinute,
                             @RequestParam("endMinute") Integer endMinute,
                             @RequestParam("expectedArrivals") Integer expectedArrivals,
                             @RequestParam(value = "targetGateId", required = false) Long targetGateId,
                             @RequestParam(value = "notes", required = false) String notes,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            OperationsDtos.ArrivalWaveRequest req = new OperationsDtos.ArrivalWaveRequest(
                    label, startMinute, endMinute, expectedArrivals, targetGateId, notes);
            operationsService.createWave(
                    eventId, req,
                    InfrastructureApiController.hasEditRole(authentication),
                    InfrastructureApiController.hasReviewRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Arrival wave interval added.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/capacity?tab=arrivals";
    }

    @PostMapping("/waves/{waveId}/update")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String updateWave(@PathVariable("eventId") Long eventId,
                             @PathVariable("waveId") Long waveId,
                             @RequestParam(value = "label", required = false) String label,
                             @RequestParam("startMinute") Integer startMinute,
                             @RequestParam("endMinute") Integer endMinute,
                             @RequestParam("expectedArrivals") Integer expectedArrivals,
                             @RequestParam(value = "targetGateId", required = false) Long targetGateId,
                             @RequestParam(value = "notes", required = false) String notes,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            OperationsDtos.ArrivalWaveRequest req = new OperationsDtos.ArrivalWaveRequest(
                    label, startMinute, endMinute, expectedArrivals, targetGateId, notes);
            operationsService.updateWave(
                    eventId, waveId, req,
                    InfrastructureApiController.hasEditRole(authentication),
                    InfrastructureApiController.hasReviewRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Arrival wave interval updated.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/capacity?tab=arrivals";
    }

    @PostMapping("/waves/{waveId}/delete")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String deleteWave(@PathVariable("eventId") Long eventId,
                             @PathVariable("waveId") Long waveId,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            operationsService.deleteWave(
                    eventId, waveId,
                    InfrastructureApiController.hasEditRole(authentication),
                    InfrastructureApiController.hasReviewRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Arrival wave interval deleted.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        }
        return "redirect:/events/" + eventId + "/capacity?tab=arrivals";
    }

    @PostMapping("/waves/generate")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String generateWaves(@PathVariable("eventId") Long eventId,
                                @RequestParam("bucketMinutes") Integer bucketMinutes,
                                @RequestParam("totalDurationMinutes") Integer totalDurationMinutes,
                                @RequestParam(value = "totalArrivals", required = false) Integer totalArrivals,
                                @RequestParam(value = "targetGateId", required = false) Long targetGateId,
                                @RequestParam(value = "replaceExisting", defaultValue = "true") boolean replaceExisting,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        try {
            OperationsDtos.GenerateWavesRequest req = new OperationsDtos.GenerateWavesRequest(
                    bucketMinutes, totalDurationMinutes, totalArrivals, targetGateId, replaceExisting);
            operationsService.generateWaveBuckets(
                    eventId, req,
                    InfrastructureApiController.hasEditRole(authentication),
                    InfrastructureApiController.hasReviewRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Arrival wave schedule buckets generated.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        }
        return "redirect:/events/" + eventId + "/capacity?tab=arrivals";
    }

    @PostMapping("/staff")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String createStaffMember(@PathVariable("eventId") Long eventId,
                                    @RequestParam("name") String name,
                                    @RequestParam("roleType") StaffRoleType roleType,
                                    @RequestParam(value = "contactPhone", required = false) String contactPhone,
                                    @RequestParam(value = "certifications", required = false) String certifications,
                                    @RequestParam(value = "notes", required = false) String notes,
                                    @RequestParam(value = "userId", required = false) Long userId,
                                    Authentication authentication,
                                    RedirectAttributes redirectAttributes) {
        try {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Staff member name is required.");
            }
            OperationsDtos.StaffMemberRequest req = new OperationsDtos.StaffMemberRequest(
                    name, roleType, contactPhone, certifications, notes, userId);
            operationsService.createStaffMember(
                    eventId, req,
                    InfrastructureApiController.hasEditRole(authentication),
                    InfrastructureApiController.hasReviewRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Staff member registered.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/capacity?tab=staff";
    }

    @PostMapping("/staff/{staffId}/update")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String updateStaffMember(@PathVariable("eventId") Long eventId,
                                    @PathVariable("staffId") Long staffId,
                                    @RequestParam("name") String name,
                                    @RequestParam("roleType") StaffRoleType roleType,
                                    @RequestParam(value = "contactPhone", required = false) String contactPhone,
                                    @RequestParam(value = "certifications", required = false) String certifications,
                                    @RequestParam(value = "notes", required = false) String notes,
                                    @RequestParam(value = "userId", required = false) Long userId,
                                    Authentication authentication,
                                    RedirectAttributes redirectAttributes) {
        try {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Staff member name is required.");
            }
            OperationsDtos.StaffMemberRequest req = new OperationsDtos.StaffMemberRequest(
                    name, roleType, contactPhone, certifications, notes, userId);
            operationsService.updateStaffMember(
                    eventId, staffId, req,
                    InfrastructureApiController.hasEditRole(authentication),
                    InfrastructureApiController.hasReviewRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Staff member updated.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/capacity?tab=staff";
    }

    @PostMapping("/staff/{staffId}/delete")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String deleteStaffMember(@PathVariable("eventId") Long eventId,
                                    @PathVariable("staffId") Long staffId,
                                    Authentication authentication,
                                    RedirectAttributes redirectAttributes) {
        try {
            operationsService.deleteStaffMember(
                    eventId, staffId,
                    InfrastructureApiController.hasEditRole(authentication),
                    InfrastructureApiController.hasReviewRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Staff member removed from roster.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        }
        return "redirect:/events/" + eventId + "/capacity?tab=staff";
    }

    @PostMapping("/assignments")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String createAssignment(@PathVariable("eventId") Long eventId,
                                   @RequestParam("staffId") Long staffId,
                                   @RequestParam(value = "zoneId", required = false) Long zoneId,
                                   @RequestParam(value = "infraId", required = false) Long infraId,
                                   @RequestParam(value = "postLabel", required = false) String postLabel,
                                   @RequestParam("shiftStart")
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime shiftStart,
                                   @RequestParam("shiftEnd")
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime shiftEnd,
                                   @RequestParam(value = "notes", required = false) String notes,
                                   Authentication authentication,
                                   RedirectAttributes redirectAttributes) {
        try {
            OperationsDtos.StaffAssignmentRequest req = new OperationsDtos.StaffAssignmentRequest(
                    staffId, zoneId, infraId, postLabel, shiftStart, shiftEnd, notes);
            operationsService.createAssignment(
                    eventId, req,
                    InfrastructureApiController.hasEditRole(authentication),
                    InfrastructureApiController.hasReviewRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Shift assignment scheduled.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/capacity?tab=staff";
    }

    @PostMapping("/assignments/{assignmentId}/delete")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String deleteAssignment(@PathVariable("eventId") Long eventId,
                                   @PathVariable("assignmentId") Long assignmentId,
                                   Authentication authentication,
                                   RedirectAttributes redirectAttributes) {
        try {
            operationsService.deleteAssignment(
                    eventId, assignmentId,
                    InfrastructureApiController.hasEditRole(authentication),
                    InfrastructureApiController.hasReviewRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Shift assignment removed.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        }
        return "redirect:/events/" + eventId + "/capacity?tab=staff";
    }

    @PostMapping("/staffing-targets")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String updateStaffingTargets(@PathVariable("eventId") Long eventId,
                                        @RequestParam("minSecurityPerActiveGate") Integer minSecurityPerActiveGate,
                                        @RequestParam("minStaffPerPublicZone") Integer minStaffPerPublicZone,
                                        @RequestParam("minMedicalStaffTotal") Integer minMedicalStaffTotal,
                                        @RequestParam("minSupervisorsTotal") Integer minSupervisorsTotal,
                                        @RequestParam("attendeesPerStaffRatio") Integer attendeesPerStaffRatio,
                                        @RequestParam("shiftWindowMinutes") Integer shiftWindowMinutes,
                                        @RequestParam(value = "notes", required = false) String notes,
                                        Authentication authentication,
                                        RedirectAttributes redirectAttributes) {
        try {
            if (minSecurityPerActiveGate < 0 || minStaffPerPublicZone < 0 || minMedicalStaffTotal < 0
                    || minSupervisorsTotal < 0 || attendeesPerStaffRatio < 0 || shiftWindowMinutes < 15) {
                throw new IllegalArgumentException("Staffing target values must be non-negative (shift window >= 15 min).");
            }
            OperationsDtos.StaffingTargetsRequest req = new OperationsDtos.StaffingTargetsRequest(
                    minSecurityPerActiveGate, minStaffPerPublicZone, minMedicalStaffTotal,
                    minSupervisorsTotal, attendeesPerStaffRatio, shiftWindowMinutes, notes);
            operationsService.updateStaffingTargets(
                    eventId, req,
                    InfrastructureApiController.hasEditRole(authentication),
                    InfrastructureApiController.hasReviewRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage",
                    "Minimum staffing targets updated and coverage gaps recalculated.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/capacity?tab=staff";
    }

    @PostMapping("/incidents")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String createIncident(@PathVariable("eventId") Long eventId,
                                 @RequestParam("severity") FindingSeverity severity,
                                 @RequestParam(value = "zoneId", required = false) Long zoneId,
                                 @RequestParam(value = "assignedStaffId", required = false) Long assignedStaffId,
                                 @RequestParam("description") String description,
                                 @RequestParam(value = "responseTaken", required = false) String responseTaken,
                                 @AuthenticationPrincipal CustomUserDetails currentUser,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        try {
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Incident description is required.");
            }
            OperationsDtos.CreateIncidentRequest req = new OperationsDtos.CreateIncidentRequest(
                    severity, zoneId, assignedStaffId, description, responseTaken);
            operationsService.createIncident(
                    eventId,
                    currentUser != null ? currentUser.getId() : null,
                    req,
                    InfrastructureApiController.hasEditRole(authentication),
                    InfrastructureApiController.hasReviewRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Incident logged.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/capacity?tab=staff";
    }

    @PostMapping("/incidents/{incidentId}/update")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String updateIncident(@PathVariable("eventId") Long eventId,
                                 @PathVariable("incidentId") Long incidentId,
                                 @RequestParam("status") IncidentStatus status,
                                 @RequestParam(value = "severity", required = false) FindingSeverity severity,
                                 @RequestParam(value = "assignedStaffId", required = false) Long assignedStaffId,
                                 @RequestParam(value = "responseTaken", required = false) String responseTaken,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        try {
            OperationsDtos.UpdateIncidentRequest req = new OperationsDtos.UpdateIncidentRequest(
                    status, severity, assignedStaffId, responseTaken);
            operationsService.updateIncident(
                    eventId, incidentId, req,
                    InfrastructureApiController.hasEditRole(authentication),
                    InfrastructureApiController.hasReviewRole(authentication));
            redirectAttributes.addFlashAttribute("successMessage", "Incident response updated.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/events/" + eventId + "/capacity?tab=staff";
    }

    private String extractError(Exception ex) {
        if (ex instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        return ex.getMessage() != null ? ex.getMessage() : "Unable to save changes.";
    }
}
