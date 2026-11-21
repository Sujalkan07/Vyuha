package com.vyuha.simulation;

import com.vyuha.auth.CustomUserDetails;
import com.vyuha.common.ApiResponse;
import com.vyuha.infrastructure.InfrastructureApiController;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Phase 9 Layout Snapshots, Deterministic Crowd Simulation Runs,
 * Frame Stream Playback, and Before-and-After Comparisons.
 */
@RestController
@RequestMapping("/api/events/{eventId}/simulation")
public class SimulationApiController {

    private final SimulationService simulationService;

    public SimulationApiController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<SimulationDtos.SimulationWorkspaceDto>> getWorkspace(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(simulationService.getWorkspace(
                eventId, InfrastructureApiController.hasEditRole(authentication), true)));
    }

    @PostMapping("/snapshots")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<SimulationDtos.LayoutSnapshotDetailDto>> createSnapshot(
            @PathVariable("eventId") Long eventId,
            @RequestBody(required = false) SimulationDtos.CreateSnapshotRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        SimulationDtos.LayoutSnapshotDetailDto dto = simulationService.captureSnapshot(
                eventId, currentUser != null ? currentUser.getId() : null, request);
        return ResponseEntity.ok(ApiResponse.ok("Layout snapshot captured.", dto));
    }

    @GetMapping("/snapshots/{snapshotId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<SimulationDtos.LayoutSnapshotDetailDto>> getSnapshot(
            @PathVariable("eventId") Long eventId,
            @PathVariable("snapshotId") Long snapshotId) {
        return ResponseEntity.ok(ApiResponse.ok(simulationService.getSnapshotDetail(eventId, snapshotId)));
    }

    @GetMapping("/snapshots/{snapshotId}/thumbnail")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<Resource> getSnapshotThumbnail(
            @PathVariable("eventId") Long eventId,
            @PathVariable("snapshotId") Long snapshotId) {
        Resource resource = simulationService.loadSnapshotThumbnail(eventId, snapshotId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(resource);
    }

    @DeleteMapping("/snapshots/{snapshotId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<SimulationDtos.SimulationWorkspaceDto>> deleteSnapshot(
            @PathVariable("eventId") Long eventId,
            @PathVariable("snapshotId") Long snapshotId,
            Authentication authentication) {
        simulationService.deleteSnapshot(eventId, snapshotId);
        SimulationDtos.SimulationWorkspaceDto workspace = simulationService.getWorkspace(
                eventId, InfrastructureApiController.hasEditRole(authentication), true);
        return ResponseEntity.ok(ApiResponse.ok("Layout snapshot deleted.", workspace));
    }

    @PostMapping("/runs")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<SimulationDtos.SimulationRunDetailDto>> runSimulation(
            @PathVariable("eventId") Long eventId,
            @RequestBody(required = false) SimulationDtos.RunSimulationRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        SimulationDtos.SimulationRunDetailDto dto = simulationService.runSimulation(
                eventId, currentUser != null ? currentUser.getId() : null, request);
        return ResponseEntity.ok(ApiResponse.ok("Simulation completed.", dto));
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<SimulationDtos.SimulationRunDetailDto>> getRunDetail(
            @PathVariable("eventId") Long eventId,
            @PathVariable("runId") Long runId) {
        return ResponseEntity.ok(ApiResponse.ok(simulationService.getRunDetail(eventId, runId)));
    }

    @GetMapping("/runs/{runId}/frames")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<SimulationDtos.SimulationFrameStream>> getRunFrames(
            @PathVariable("eventId") Long eventId,
            @PathVariable("runId") Long runId) {
        return ResponseEntity.ok(ApiResponse.ok(simulationService.getRunFrames(eventId, runId)));
    }

    @DeleteMapping("/runs/{runId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<SimulationDtos.SimulationWorkspaceDto>> deleteRun(
            @PathVariable("eventId") Long eventId,
            @PathVariable("runId") Long runId,
            Authentication authentication) {
        simulationService.deleteRun(eventId, runId);
        SimulationDtos.SimulationWorkspaceDto workspace = simulationService.getWorkspace(
                eventId, InfrastructureApiController.hasEditRole(authentication), true);
        return ResponseEntity.ok(ApiResponse.ok("Simulation run deleted.", workspace));
    }

    @PostMapping("/compare")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<SimulationDtos.SimulationComparisonDto>> runComparison(
            @PathVariable("eventId") Long eventId,
            @RequestBody SimulationDtos.CompareSnapshotsRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        SimulationDtos.SimulationComparisonDto dto = simulationService.runPairedComparison(
                eventId, currentUser != null ? currentUser.getId() : null, request);
        return ResponseEntity.ok(ApiResponse.ok("Paired Before/After simulation comparison completed.", dto));
    }

    @GetMapping("/compare")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<SimulationDtos.SimulationComparisonDto>> compareRuns(
            @PathVariable("eventId") Long eventId,
            @RequestParam("beforeRunId") Long beforeRunId,
            @RequestParam("afterRunId") Long afterRunId) {
        SimulationDtos.SimulationComparisonDto dto =
                simulationService.compareExistingRuns(eventId, beforeRunId, afterRunId);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }
}
