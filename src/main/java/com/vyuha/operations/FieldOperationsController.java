package com.vyuha.operations;

import com.vyuha.auth.CustomUserDetails;
import com.vyuha.common.ApiResponse;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.monitoring.LiveMonitoringService;
import com.vyuha.monitoring.MonitoringDtos;
import com.vyuha.monitoring.PassScanAction;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Field-staff mobile operations controller serving /field and /api/field/**
 * for shift lookup, manual zone counts, attendee pass scanning, and incident logging.
 */
@Controller
public class FieldOperationsController {

    private final OperationsService operationsService;
    private final LiveMonitoringService liveMonitoringService;

    public FieldOperationsController(OperationsService operationsService,
                                     LiveMonitoringService liveMonitoringService) {
        this.operationsService = operationsService;
        this.liveMonitoringService = liveMonitoringService;
    }

    @GetMapping("/field")
    @PreAuthorize("hasAnyRole('FIELD_STAFF', 'ADMIN', 'ORGANIZER')")
    public String fieldLanding(@RequestParam(value = "eventId", required = false) Long eventId,
                               @AuthenticationPrincipal CustomUserDetails currentUser,
                               Authentication authentication,
                               Model model) {
        boolean isFieldStaffOnly = isFieldStaffOnly(authentication);
        OperationsDtos.FieldOperationsViewDto fieldView = operationsService.getFieldOperationsView(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Field Staff",
                currentUser != null ? currentUser.getRoleDisplayName() : "Field Staff",
                isFieldStaffOnly
        );

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("fieldView", fieldView);
        model.addAttribute("fieldMonitoring", fieldView.selectedEventId() != null
                ? liveMonitoringService.getFieldMonitoringData(fieldView.selectedEventId())
                : null);
        model.addAttribute("scanActions", PassScanAction.values());
        model.addAttribute("severities", FindingSeverity.values());
        model.addAttribute("incidentStatuses", IncidentStatus.values());
        return "ops/field";
    }

    @PostMapping("/field/events/{eventId}/incidents")
    @PreAuthorize("hasAnyRole('FIELD_STAFF', 'ADMIN', 'ORGANIZER')")
    public String createFieldIncident(@PathVariable("eventId") Long eventId,
                                      @RequestParam("severity") FindingSeverity severity,
                                      @RequestParam(value = "zoneId", required = false) Long zoneId,
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
                    severity, zoneId, null, description, responseTaken);
            operationsService.createFieldIncident(
                    eventId,
                    currentUser != null ? currentUser.getId() : null,
                    currentUser != null ? currentUser.getFullName() : "Field Staff",
                    currentUser != null ? currentUser.getRoleDisplayName() : "Field Staff",
                    isFieldStaffOnly(authentication),
                    req);
            redirectAttributes.addFlashAttribute("successMessage", "Field incident reported.");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            String msg = ex instanceof ResponseStatusException rse && rse.getReason() != null
                    ? rse.getReason()
                    : ex.getMessage();
            redirectAttributes.addFlashAttribute("errorMessage", msg);
        }
        return "redirect:/field?eventId=" + eventId;
    }

    @PostMapping("/field/events/{eventId}/incidents/{incidentId}")
    @PreAuthorize("hasAnyRole('FIELD_STAFF', 'ADMIN', 'ORGANIZER')")
    public String updateFieldIncident(@PathVariable("eventId") Long eventId,
                                      @PathVariable("incidentId") Long incidentId,
                                      @RequestParam("status") IncidentStatus status,
                                      @RequestParam(value = "responseTaken", required = false) String responseTaken,
                                      @AuthenticationPrincipal CustomUserDetails currentUser,
                                      Authentication authentication,
                                      RedirectAttributes redirectAttributes) {
        try {
            OperationsDtos.UpdateIncidentRequest req = new OperationsDtos.UpdateIncidentRequest(
                    status, null, null, responseTaken);
            operationsService.updateFieldIncident(
                    eventId, incidentId,
                    currentUser != null ? currentUser.getId() : null,
                    currentUser != null ? currentUser.getFullName() : "Field Staff",
                    currentUser != null ? currentUser.getRoleDisplayName() : "Field Staff",
                    isFieldStaffOnly(authentication),
                    req);
            redirectAttributes.addFlashAttribute("successMessage", "Incident response updated.");
        } catch (ResponseStatusException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getReason());
        }
        return "redirect:/field?eventId=" + eventId;
    }

    @GetMapping("/api/field/events/{eventId}/operations")
    @ResponseBody
    @PreAuthorize("hasAnyRole('FIELD_STAFF', 'ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.FieldOperationsViewDto>> getFieldApi(
            @PathVariable("eventId") Long eventId,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        OperationsDtos.FieldOperationsViewDto dto = operationsService.getFieldOperationsView(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Field Staff",
                currentUser != null ? currentUser.getRoleDisplayName() : "Field Staff",
                isFieldStaffOnly(authentication));
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @PostMapping("/api/field/events/{eventId}/incidents")
    @ResponseBody
    @PreAuthorize("hasAnyRole('FIELD_STAFF', 'ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.FieldOperationsViewDto>> createFieldIncidentApi(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody OperationsDtos.CreateIncidentRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        OperationsDtos.FieldOperationsViewDto dto = operationsService.createFieldIncident(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Field Staff",
                currentUser != null ? currentUser.getRoleDisplayName() : "Field Staff",
                isFieldStaffOnly(authentication),
                request);
        return ResponseEntity.ok(ApiResponse.ok("Field incident recorded.", dto));
    }

    @PutMapping("/api/field/events/{eventId}/incidents/{incidentId}")
    @ResponseBody
    @PreAuthorize("hasAnyRole('FIELD_STAFF', 'ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.FieldOperationsViewDto>> updateFieldIncidentApi(
            @PathVariable("eventId") Long eventId,
            @PathVariable("incidentId") Long incidentId,
            @Valid @RequestBody OperationsDtos.UpdateIncidentRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        OperationsDtos.FieldOperationsViewDto dto = operationsService.updateFieldIncident(
                eventId, incidentId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Field Staff",
                currentUser != null ? currentUser.getRoleDisplayName() : "Field Staff",
                isFieldStaffOnly(authentication),
                request);
        return ResponseEntity.ok(ApiResponse.ok("Field incident updated.", dto));
    }

    @PostMapping("/field/events/{eventId}/readings")
    @PreAuthorize("hasAnyRole('FIELD_STAFF', 'ADMIN', 'ORGANIZER')")
    public String submitFieldManualCount(@PathVariable("eventId") Long eventId,
                                         @RequestParam("zoneId") Long zoneId,
                                         @RequestParam("countValue") Integer countValue,
                                         @RequestParam(value = "note", required = false) String note,
                                         @AuthenticationPrincipal CustomUserDetails currentUser,
                                         RedirectAttributes redirectAttributes) {
        try {
            if (countValue == null || countValue < 0) {
                throw new IllegalArgumentException("Zone headcount cannot be negative.");
            }
            liveMonitoringService.recordManualCount(
                    eventId,
                    currentUser != null ? currentUser.getId() : null,
                    currentUser != null ? currentUser.getFullName() : "Field Staff",
                    new MonitoringDtos.ManualCountRequest(zoneId, countValue, note),
                    false,
                    false);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Zone count " + countValue + " saved (tagged MANUAL_STAFF).");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/field?eventId=" + eventId;
    }

    @PostMapping("/field/events/{eventId}/passes/scan")
    @PreAuthorize("hasAnyRole('FIELD_STAFF', 'ADMIN', 'ORGANIZER')")
    public String scanFieldPass(@PathVariable("eventId") Long eventId,
                                @RequestParam("passCode") String passCode,
                                @RequestParam("action") PassScanAction action,
                                @RequestParam(value = "gateId", required = false) Long gateId,
                                @RequestParam(value = "zoneId", required = false) Long zoneId,
                                @AuthenticationPrincipal CustomUserDetails currentUser,
                                RedirectAttributes redirectAttributes) {
        try {
            liveMonitoringService.scanPass(
                    eventId,
                    currentUser != null ? currentUser.getId() : null,
                    currentUser != null ? currentUser.getFullName() : "Field Staff",
                    new MonitoringDtos.ScanPassRequest(passCode, action, gateId, zoneId),
                    false,
                    false);
            redirectAttributes.addFlashAttribute("successMessage",
                    action.getDisplayName() + " completed for pass " + passCode.trim().toUpperCase() + ".");
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", extractError(ex));
        }
        return "redirect:/field?eventId=" + eventId;
    }

    @GetMapping("/api/field/events/{eventId}/monitoring")
    @ResponseBody
    @PreAuthorize("hasAnyRole('FIELD_STAFF', 'ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.FieldMonitoringDto>> getFieldMonitoringApi(
            @PathVariable("eventId") Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(liveMonitoringService.getFieldMonitoringData(eventId)));
    }

    @PostMapping("/api/field/events/{eventId}/readings")
    @ResponseBody
    @PreAuthorize("hasAnyRole('FIELD_STAFF', 'ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.FieldMonitoringDto>> submitFieldManualCountApi(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody MonitoringDtos.ManualCountRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        liveMonitoringService.recordManualCount(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Field Staff",
                request,
                false,
                false);
        return ResponseEntity.ok(ApiResponse.ok(
                "Manual zone count recorded (MANUAL_STAFF).",
                liveMonitoringService.getFieldMonitoringData(eventId)));
    }

    @PostMapping("/api/field/events/{eventId}/passes/scan")
    @ResponseBody
    @PreAuthorize("hasAnyRole('FIELD_STAFF', 'ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.FieldMonitoringDto>> scanFieldPassApi(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody MonitoringDtos.ScanPassRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        liveMonitoringService.scanPass(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Field Staff",
                request,
                false,
                false);
        return ResponseEntity.ok(ApiResponse.ok(
                "Attendee pass scan recorded (PASS_SCAN).",
                liveMonitoringService.getFieldMonitoringData(eventId)));
    }

    @PostMapping(value = "/api/field/events/{eventId}/passes/scan-qr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    @PreAuthorize("hasAnyRole('FIELD_STAFF', 'ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.FieldMonitoringDto>> scanFieldPassQrApi(
            @PathVariable("eventId") Long eventId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("action") PassScanAction action,
            @RequestParam(value = "gateId", required = false) Long gateId,
            @RequestParam(value = "zoneId", required = false) Long zoneId,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        String decodedPassCode = liveMonitoringService.decodePassQrBytes(readQrUploadBytes(file));
        liveMonitoringService.scanPass(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Field Staff",
                new MonitoringDtos.ScanPassRequest(decodedPassCode, action, gateId, zoneId),
                false,
                false);
        return ResponseEntity.ok(ApiResponse.ok(
                "QR pass " + decodedPassCode + " recorded (PASS_SCAN).",
                liveMonitoringService.getFieldMonitoringData(eventId)));
    }

    private byte[] readQrUploadBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Select a QR image file to scan.");
        }
        if (file.getSize() > 5L * 1024L * 1024L) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "QR image must not exceed 5 MB.");
        }
        try {
            return file.getBytes();
        } catch (java.io.IOException ex) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Unable to read uploaded QR image.", ex);
        }
    }

    private String extractError(Exception ex) {
        if (ex instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        return ex.getMessage() != null ? ex.getMessage() : "Unable to update field operations record.";
    }

    private boolean isFieldStaffOnly(Authentication authentication) {
        if (authentication == null) {
            return true;
        }
        boolean hasOrganizerOrAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_ORGANIZER".equals(a.getAuthority()));
        return !hasOrganizerOrAdmin;
    }
}
