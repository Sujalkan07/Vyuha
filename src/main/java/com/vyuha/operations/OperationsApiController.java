package com.vyuha.operations;

import com.vyuha.auth.CustomUserDetails;
import com.vyuha.common.ApiResponse;
import com.vyuha.infrastructure.InfrastructureApiController;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Phase 7 Arrival Waves, Queue Overflow Projection, Staff Roster,
 * Shift Coverage Analysis, and Operational Incident Logs.
 */
@RestController
@RequestMapping("/api/events/{eventId}/operations")
public class OperationsApiController {

    private final OperationsService operationsService;

    public OperationsApiController(OperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> getWorkspace(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(operationsService.getWorkspace(
                eventId,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication))));
    }

    @PostMapping("/waves")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> createWave(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody OperationsDtos.ArrivalWaveRequest request,
            Authentication authentication) {
        OperationsDtos.OperationsWorkspaceDto dto = operationsService.createWave(
                eventId, request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Arrival wave interval added.", dto));
    }

    @PutMapping("/waves/{waveId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> updateWave(
            @PathVariable("eventId") Long eventId,
            @PathVariable("waveId") Long waveId,
            @Valid @RequestBody OperationsDtos.ArrivalWaveRequest request,
            Authentication authentication) {
        OperationsDtos.OperationsWorkspaceDto dto = operationsService.updateWave(
                eventId, waveId, request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Arrival wave interval updated.", dto));
    }

    @DeleteMapping("/waves/{waveId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> deleteWave(
            @PathVariable("eventId") Long eventId,
            @PathVariable("waveId") Long waveId,
            Authentication authentication) {
        OperationsDtos.OperationsWorkspaceDto dto = operationsService.deleteWave(
                eventId, waveId,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Arrival wave interval deleted.", dto));
    }

    @PostMapping("/waves/generate")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> generateWaves(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody OperationsDtos.GenerateWavesRequest request,
            Authentication authentication) {
        OperationsDtos.OperationsWorkspaceDto dto = operationsService.generateWaveBuckets(
                eventId, request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Arrival wave buckets generated.", dto));
    }

    @PostMapping("/staff")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> createStaffMember(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody OperationsDtos.StaffMemberRequest request,
            Authentication authentication) {
        OperationsDtos.OperationsWorkspaceDto dto = operationsService.createStaffMember(
                eventId, request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Staff member registered.", dto));
    }

    @PutMapping("/staff/{staffId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> updateStaffMember(
            @PathVariable("eventId") Long eventId,
            @PathVariable("staffId") Long staffId,
            @Valid @RequestBody OperationsDtos.StaffMemberRequest request,
            Authentication authentication) {
        OperationsDtos.OperationsWorkspaceDto dto = operationsService.updateStaffMember(
                eventId, staffId, request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Staff member updated.", dto));
    }

    @DeleteMapping("/staff/{staffId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> deleteStaffMember(
            @PathVariable("eventId") Long eventId,
            @PathVariable("staffId") Long staffId,
            Authentication authentication) {
        OperationsDtos.OperationsWorkspaceDto dto = operationsService.deleteStaffMember(
                eventId, staffId,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Staff member removed.", dto));
    }

    @PostMapping("/assignments")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> createAssignment(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody OperationsDtos.StaffAssignmentRequest request,
            Authentication authentication) {
        OperationsDtos.OperationsWorkspaceDto dto = operationsService.createAssignment(
                eventId, request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Shift assignment saved.", dto));
    }

    @DeleteMapping("/assignments/{assignmentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> deleteAssignment(
            @PathVariable("eventId") Long eventId,
            @PathVariable("assignmentId") Long assignmentId,
            Authentication authentication) {
        OperationsDtos.OperationsWorkspaceDto dto = operationsService.deleteAssignment(
                eventId, assignmentId,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Shift assignment removed.", dto));
    }

    @PutMapping("/staffing-targets")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> updateStaffingTargets(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody OperationsDtos.StaffingTargetsRequest request,
            Authentication authentication) {
        OperationsDtos.OperationsWorkspaceDto dto = operationsService.updateStaffingTargets(
                eventId, request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Minimum staffing targets updated.", dto));
    }

    @PostMapping("/incidents")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> createIncident(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody OperationsDtos.CreateIncidentRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        OperationsDtos.OperationsWorkspaceDto dto = operationsService.createIncident(
                eventId,
                currentUser != null ? currentUser.getId() : null,
                request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Incident log recorded.", dto));
    }

    @PutMapping("/incidents/{incidentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<OperationsDtos.OperationsWorkspaceDto>> updateIncident(
            @PathVariable("eventId") Long eventId,
            @PathVariable("incidentId") Long incidentId,
            @Valid @RequestBody OperationsDtos.UpdateIncidentRequest request,
            Authentication authentication) {
        OperationsDtos.OperationsWorkspaceDto dto = operationsService.updateIncident(
                eventId, incidentId, request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Incident log updated.", dto));
    }
}
