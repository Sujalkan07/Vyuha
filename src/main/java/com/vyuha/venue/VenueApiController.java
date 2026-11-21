package com.vyuha.venue;

import com.vyuha.common.ApiResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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

/**
 * REST API for Phase 3 Venue Plan site setup, calibration, exclusion polygons, and space breakdown.
 */
@RestController
@RequestMapping("/api/events/{eventId}/venue")
public class VenueApiController {

    private final VenueService venueService;
    private final SampleMapService sampleMapService;

    public VenueApiController(VenueService venueService, SampleMapService sampleMapService) {
        this.venueService = venueService;
        this.sampleMapService = sampleMapService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<VenueDtos.VenueWorkspaceDto>> getWorkspace(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        boolean canEdit = VenueController.hasEditRole(authentication);
        return ResponseEntity.ok(ApiResponse.ok(venueService.getWorkspaceState(eventId, canEdit)));
    }

    @GetMapping("/sample-maps")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<List<VenueDtos.SampleMapSummary>>> listSampleMaps(
            @PathVariable("eventId") Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(sampleMapService.listSampleMaps()));
    }

    @PutMapping("/mode")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<VenueDtos.VenueWorkspaceDto>> updateMode(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody VenueDtos.ModeUpdateRequest request,
            Authentication authentication) {
        VenueDtos.VenueWorkspaceDto dto = venueService.updateMode(eventId, request, VenueController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Workspace mode updated.", dto));
    }

    @PostMapping("/vector-map")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<VenueDtos.VenueWorkspaceDto>> uploadVectorMap(
            @PathVariable("eventId") Long eventId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "widthMeters", required = false) Double widthMeters,
            Authentication authentication) {
        VenueDtos.VenueWorkspaceDto dto = venueService.importCustomVectorMap(
                eventId, file, widthMeters, VenueController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Custom vector map imported.", dto));
    }

    @PostMapping("/blueprint")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<VenueDtos.VenueWorkspaceDto>> uploadBlueprint(
            @PathVariable("eventId") Long eventId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        VenueDtos.VenueWorkspaceDto dto = venueService.uploadBlueprint(
                eventId, file, VenueController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Blueprint image uploaded. Calibrate scale before tracing.", dto));
    }

    @GetMapping("/blueprint")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<Resource> downloadBlueprint(@PathVariable("eventId") Long eventId) {
        VenueService.BlueprintDownload download = venueService.loadBlueprint(eventId);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(download.originalFilename(), StandardCharsets.UTF_8)
                .build();

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(download.contentType()));

        if ("image/svg+xml".equalsIgnoreCase(download.contentType())) {
            builder.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        }
        if (download.fileSizeBytes() != null) {
            builder.contentLength(download.fileSizeBytes());
        }
        return builder.body(download.resource());
    }

    @PostMapping("/calibration")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<VenueDtos.VenueWorkspaceDto>> applyCalibration(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody VenueDtos.CalibrationRequest request,
            Authentication authentication) {
        VenueDtos.VenueWorkspaceDto dto = venueService.applyCalibration(
                eventId, request, VenueController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Scale calibration calculated.", dto));
    }

    @PutMapping("/boundary")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<VenueDtos.VenueWorkspaceDto>> saveBoundary(
            @PathVariable("eventId") Long eventId,
            @RequestBody VenueDtos.BoundaryUpdateRequest request,
            Authentication authentication) {
        VenueDtos.VenueWorkspaceDto dto = venueService.saveBoundary(
                eventId, request, VenueController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Outer venue boundary saved.", dto));
    }

    @PostMapping("/exclusions")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<VenueDtos.VenueWorkspaceDto>> createExclusion(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody VenueDtos.ExclusionRequest request,
            Authentication authentication) {
        VenueDtos.VenueWorkspaceDto dto = venueService.createExclusion(
                eventId, request, VenueController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Exclusion polygon added.", dto));
    }

    @PutMapping("/exclusions/{exclusionId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<VenueDtos.VenueWorkspaceDto>> updateExclusion(
            @PathVariable("eventId") Long eventId,
            @PathVariable("exclusionId") Long exclusionId,
            @Valid @RequestBody VenueDtos.ExclusionRequest request,
            Authentication authentication) {
        VenueDtos.VenueWorkspaceDto dto = venueService.updateExclusion(
                eventId, exclusionId, request, VenueController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Exclusion polygon updated.", dto));
    }

    @DeleteMapping("/exclusions/{exclusionId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<VenueDtos.VenueWorkspaceDto>> deleteExclusion(
            @PathVariable("eventId") Long eventId,
            @PathVariable("exclusionId") Long exclusionId,
            Authentication authentication) {
        VenueDtos.VenueWorkspaceDto dto = venueService.deleteExclusion(
                eventId, exclusionId, VenueController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Exclusion polygon removed.", dto));
    }

    @PutMapping("/layout")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<VenueDtos.VenueWorkspaceDto>> syncLayout(
            @PathVariable("eventId") Long eventId,
            @RequestBody VenueDtos.LayoutSyncRequest request,
            Authentication authentication) {
        VenueDtos.VenueWorkspaceDto dto = venueService.syncLayout(
                eventId, request, VenueController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Venue layout synchronized.", dto));
    }
}
