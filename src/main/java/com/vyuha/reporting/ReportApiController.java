package com.vyuha.reporting;

import com.vyuha.auth.CustomUserDetails;
import com.vyuha.common.ApiResponse;
import com.vyuha.event.Event;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Phase 12 structured exports (PDF, XLSX, CSV, PNG) and Campus Festival demo reset.
 */
@RestController
public class ReportApiController {

    private final ReportExportService reportExportService;
    private final CampusFestivalSeedService campusFestivalSeedService;

    public ReportApiController(ReportExportService reportExportService,
                               CampusFestivalSeedService campusFestivalSeedService) {
        this.reportExportService = reportExportService;
        this.campusFestivalSeedService = campusFestivalSeedService;
    }

    @GetMapping("/api/events/{eventId}/reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ApiResponse<ReportDtos.ReportPageSummary> getSummary(@PathVariable("eventId") Long eventId) {
        return ApiResponse.ok(reportExportService.getReportSummary(eventId));
    }

    @GetMapping("/api/events/{eventId}/reports/export.pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<byte[]> exportPdf(@PathVariable("eventId") Long eventId,
                                            @AuthenticationPrincipal CustomUserDetails currentUser) {
        ReportDtos.GeneratedExportFile file = reportExportService.exportPdf(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : null);
        return buildAttachmentResponse(file);
    }

    @GetMapping("/api/events/{eventId}/reports/export.xlsx")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<byte[]> exportXlsx(@PathVariable("eventId") Long eventId,
                                             @AuthenticationPrincipal CustomUserDetails currentUser) {
        ReportDtos.GeneratedExportFile file = reportExportService.exportXlsx(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : null);
        return buildAttachmentResponse(file);
    }

    @GetMapping("/api/events/{eventId}/reports/export.csv")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<byte[]> exportCsv(@PathVariable("eventId") Long eventId,
                                            @RequestParam(value = "dataset", required = false) String dataset,
                                            @AuthenticationPrincipal CustomUserDetails currentUser) {
        ReportDtos.GeneratedExportFile file = reportExportService.exportCsv(
                eventId,
                dataset,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : null);
        return buildAttachmentResponse(file);
    }

    @GetMapping("/api/events/{eventId}/reports/snapshot.png")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<byte[]> exportSnapshotPng(@PathVariable("eventId") Long eventId,
                                                    @RequestParam(value = "view", required = false) String view,
                                                    @AuthenticationPrincipal CustomUserDetails currentUser) {
        ReportDtos.GeneratedExportFile file = reportExportService.exportSnapshotPng(
                eventId,
                view,
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getFullName() : null);
        return buildAttachmentResponse(file);
    }

    @PostMapping("/api/events/demo-reset")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ApiResponse<Map<String, Object>> resetDemoApi(@AuthenticationPrincipal CustomUserDetails currentUser) {
        Long actorId = currentUser != null ? currentUser.getId() : null;
        Event demo = campusFestivalSeedService.resetDemoEvent(actorId);
        return ApiResponse.ok(
                "Campus Festival demo event reset cleanly.",
                Map.of("eventId", demo.getId(), "eventName", demo.getName()));
    }

    private ResponseEntity<byte[]> buildAttachmentResponse(ReportDtos.GeneratedExportFile file) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.bytes().length)
                .body(file.bytes());
    }
}
