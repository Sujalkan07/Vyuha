package com.vyuha.monitoring;

import com.vyuha.auth.CustomUserDetails;
import com.vyuha.common.ApiResponse;
import com.vyuha.infrastructure.InfrastructureApiController;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST & SSE API for Phase 10 Live Event Monitoring Control Centre,
 * ZXing Pass Generation/Scanning, Threshold/Staleness Alerts, and Practice Bridge.
 */
@RestController
@RequestMapping("/api/events/{eventId}/live")
public class LiveMonitoringApiController {

    private final LiveMonitoringService monitoringService;
    private final SseHub sseHub;

    public LiveMonitoringApiController(LiveMonitoringService monitoringService, SseHub sseHub) {
        this.monitoringService = monitoringService;
        this.sseHub = sseHub;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.ControlCentreDto>> getControlCentre(
            @PathVariable("eventId") Long eventId,
            @RequestParam(value = "mode", required = false, defaultValue = "LIVE") String mode,
            @RequestParam(value = "source", required = false) OccupancyDataSource source,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(monitoringService.getControlCentre(
                eventId, mode, source, InfrastructureApiController.hasEditRole(authentication), true)));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public SseEmitter streamEvents(@PathVariable("eventId") Long eventId,
                                   Authentication authentication) {
        monitoringService.getControlCentre(
                eventId, "LIVE", null, InfrastructureApiController.hasEditRole(authentication), true);
        return sseHub.subscribe(eventId);
    }

    @PostMapping("/readings")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.ControlCentreDto>> recordManualCount(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody MonitoringDtos.ManualCountRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        MonitoringDtos.ControlCentreDto dto = monitoringService.recordManualCount(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Organizer",
                request,
                InfrastructureApiController.hasEditRole(authentication),
                true);
        return ResponseEntity.ok(ApiResponse.ok("Manual zone count recorded (MANUAL_STAFF).", dto));
    }

    @PostMapping("/passes/batch")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.ControlCentreDto>> generatePassBatch(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody MonitoringDtos.GeneratePassBatchRequest request,
            Authentication authentication) {
        MonitoringDtos.ControlCentreDto dto = monitoringService.generatePassBatch(
                eventId, request, InfrastructureApiController.hasEditRole(authentication), true);
        return ResponseEntity.ok(ApiResponse.ok("Attendee pass batch generated.", dto));
    }

    @PostMapping("/passes/scan")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.ControlCentreDto>> scanPass(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody MonitoringDtos.ScanPassRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        MonitoringDtos.ControlCentreDto dto = monitoringService.scanPass(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Gate Operator",
                request,
                InfrastructureApiController.hasEditRole(authentication),
                true);
        return ResponseEntity.ok(ApiResponse.ok("Attendee pass scan recorded (PASS_SCAN).", dto));
    }

    @PostMapping(value = "/passes/scan-qr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.ControlCentreDto>> scanPassQrImage(
            @PathVariable("eventId") Long eventId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("action") PassScanAction action,
            @RequestParam(value = "gateId", required = false) Long gateId,
            @RequestParam(value = "zoneId", required = false) Long zoneId,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        String decodedPassCode = monitoringService.decodePassQrBytes(readQrUploadBytes(file));
        MonitoringDtos.ControlCentreDto dto = monitoringService.scanPass(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Gate Operator",
                new MonitoringDtos.ScanPassRequest(decodedPassCode, action, gateId, zoneId),
                InfrastructureApiController.hasEditRole(authentication),
                true);
        return ResponseEntity.ok(ApiResponse.ok("QR pass " + decodedPassCode + " scanned.", dto));
    }

    @GetMapping("/passes/{passId}/qr")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<byte[]> getPassQrPng(
            @PathVariable("eventId") Long eventId,
            @PathVariable("passId") Long passId,
            @RequestParam(value = "size", required = false, defaultValue = "220") int sizePx) {
        byte[] png = monitoringService.renderPassQrPng(eventId, passId, sizePx);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(png);
    }

    @PutMapping("/rules")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.ControlCentreDto>> upsertAlertRule(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody MonitoringDtos.AlertRuleRequest request,
            Authentication authentication) {
        MonitoringDtos.ControlCentreDto dto = monitoringService.upsertAlertRule(
                eventId, request, InfrastructureApiController.hasEditRole(authentication), true);
        return ResponseEntity.ok(ApiResponse.ok("Alert threshold rule saved.", dto));
    }

    @DeleteMapping("/rules/{ruleId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.ControlCentreDto>> deleteAlertRule(
            @PathVariable("eventId") Long eventId,
            @PathVariable("ruleId") Long ruleId,
            Authentication authentication) {
        MonitoringDtos.ControlCentreDto dto = monitoringService.deleteAlertRule(
                eventId, ruleId, InfrastructureApiController.hasEditRole(authentication), true);
        return ResponseEntity.ok(ApiResponse.ok("Zone alert rule removed.", dto));
    }

    @PostMapping("/alerts/{alertId}/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.ControlCentreDto>> acknowledgeAlert(
            @PathVariable("eventId") Long eventId,
            @PathVariable("alertId") Long alertId,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        MonitoringDtos.ControlCentreDto dto = monitoringService.acknowledgeAlert(
                eventId,
                alertId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Reviewer",
                InfrastructureApiController.hasEditRole(authentication),
                true);
        return ResponseEntity.ok(ApiResponse.ok("Alert acknowledged.", dto));
    }

    @PostMapping("/alerts/{alertId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.ControlCentreDto>> resolveAlert(
            @PathVariable("eventId") Long eventId,
            @PathVariable("alertId") Long alertId,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        MonitoringDtos.ControlCentreDto dto = monitoringService.resolveAlert(
                eventId,
                alertId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Organizer",
                InfrastructureApiController.hasEditRole(authentication),
                true);
        return ResponseEntity.ok(ApiResponse.ok("Alert resolved.", dto));
    }

    @PostMapping("/check-staleness")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.ControlCentreDto>> checkStaleness(
            @PathVariable("eventId") Long eventId,
            @RequestParam(value = "mode", required = false, defaultValue = "LIVE") String mode,
            @RequestParam(value = "source", required = false) OccupancyDataSource source,
            Authentication authentication) {
        MonitoringDtos.ControlCentreDto dto = monitoringService.checkStaleness(
                eventId, mode, source, InfrastructureApiController.hasEditRole(authentication), true);
        return ResponseEntity.ok(ApiResponse.ok("Staleness and threshold checks evaluated.", dto));
    }

    @PostMapping("/practice/step")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.PracticeStepResultDto>> pushPracticeStep(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody(required = false) MonitoringDtos.PracticeStepRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        MonitoringDtos.PracticeStepResultDto result = monitoringService.pushPracticeSimulationFrame(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : "Organizer",
                request,
                InfrastructureApiController.hasEditRole(authentication),
                true);
        return ResponseEntity.ok(ApiResponse.ok("Practice simulation frame applied to PRACTICE_SIMULATION channel.", result));
    }

    @DeleteMapping("/practice")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<MonitoringDtos.ControlCentreDto>> clearPracticeData(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        MonitoringDtos.ControlCentreDto dto = monitoringService.clearPracticeData(
                eventId, InfrastructureApiController.hasEditRole(authentication), true);
        return ResponseEntity.ok(ApiResponse.ok("Practice simulation readings and alerts cleared.", dto));
    }

    static byte[] readQrUploadBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a QR image file to scan.");
        }
        if (file.getSize() > 5L * 1024L * 1024L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QR image must not exceed 5 MB.");
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read uploaded QR image.", ex);
        }
    }
}
