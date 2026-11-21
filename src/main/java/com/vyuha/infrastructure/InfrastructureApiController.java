package com.vyuha.infrastructure;

import com.vyuha.auth.CustomUserDetails;
import com.vyuha.common.ApiResponse;
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
 * REST API for Phase 5 Infrastructure Placement, server-backed Undo/Redo revisions,
 * and spatial conflict detection/review operations.
 */
@RestController
@RequestMapping("/api/events/{eventId}/infrastructure")
public class InfrastructureApiController {

    private final InfrastructureService infrastructureService;

    public InfrastructureApiController(InfrastructureService infrastructureService) {
        this.infrastructureService = infrastructureService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<InfrastructureDtos.InfrastructureWorkspaceDto>> getWorkspace(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(infrastructureService.getWorkspace(
                eventId, hasEditRole(authentication), hasReviewRole(authentication))));
    }

    @PostMapping("/elements")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<InfrastructureDtos.InfrastructureWorkspaceDto>> createElement(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody InfrastructureDtos.CreateElementRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        InfrastructureDtos.InfrastructureWorkspaceDto dto = infrastructureService.createElement(
                eventId,
                request,
                currentUser != null ? currentUser.getId() : null,
                hasEditRole(authentication),
                hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Infrastructure element placed.", dto));
    }

    @PutMapping("/elements/{elementId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<InfrastructureDtos.InfrastructureWorkspaceDto>> updateElement(
            @PathVariable("eventId") Long eventId,
            @PathVariable("elementId") Long elementId,
            @Valid @RequestBody InfrastructureDtos.UpdateElementRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        InfrastructureDtos.InfrastructureWorkspaceDto dto = infrastructureService.updateElement(
                eventId,
                elementId,
                request,
                currentUser != null ? currentUser.getId() : null,
                hasEditRole(authentication),
                hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Infrastructure element updated.", dto));
    }

    @PostMapping("/elements/{elementId}/duplicate")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<InfrastructureDtos.InfrastructureWorkspaceDto>> duplicateElement(
            @PathVariable("eventId") Long eventId,
            @PathVariable("elementId") Long elementId,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        InfrastructureDtos.InfrastructureWorkspaceDto dto = infrastructureService.duplicateElement(
                eventId,
                elementId,
                currentUser != null ? currentUser.getId() : null,
                hasEditRole(authentication),
                hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Infrastructure element duplicated.", dto));
    }

    @DeleteMapping("/elements/{elementId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<InfrastructureDtos.InfrastructureWorkspaceDto>> deleteElement(
            @PathVariable("eventId") Long eventId,
            @PathVariable("elementId") Long elementId,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        InfrastructureDtos.InfrastructureWorkspaceDto dto = infrastructureService.deleteElement(
                eventId,
                elementId,
                currentUser != null ? currentUser.getId() : null,
                hasEditRole(authentication),
                hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Infrastructure element deleted.", dto));
    }

    @PostMapping("/undo")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<InfrastructureDtos.InfrastructureWorkspaceDto>> undo(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        InfrastructureDtos.InfrastructureWorkspaceDto dto = infrastructureService.undo(
                eventId, hasEditRole(authentication), hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Reverted last infrastructure edit.", dto));
    }

    @PostMapping("/redo")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<InfrastructureDtos.InfrastructureWorkspaceDto>> redo(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        InfrastructureDtos.InfrastructureWorkspaceDto dto = infrastructureService.redo(
                eventId, hasEditRole(authentication), hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Reapplied infrastructure edit.", dto));
    }

    @PostMapping("/check")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<InfrastructureDtos.InfrastructureWorkspaceDto>> runConflictCheck(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        InfrastructureDtos.InfrastructureWorkspaceDto dto = infrastructureService.runConflictCheck(
                eventId, hasEditRole(authentication), hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Spatial conflict checks updated.", dto));
    }

    @PostMapping("/findings/{findingId}/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<InfrastructureDtos.InfrastructureWorkspaceDto>> reviewFinding(
            @PathVariable("eventId") Long eventId,
            @PathVariable("findingId") Long findingId,
            @Valid @RequestBody InfrastructureDtos.ReviewFindingRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        InfrastructureDtos.InfrastructureWorkspaceDto dto = infrastructureService.reviewFinding(
                eventId,
                findingId,
                currentUser != null ? currentUser.getId() : null,
                request,
                hasEditRole(authentication),
                hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Conflict finding review saved.", dto));
    }

    public static boolean hasEditRole(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_ORGANIZER".equals(a.getAuthority()));
    }

    public static boolean hasReviewRole(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_SAFETY_REVIEWER".equals(a.getAuthority()));
    }
}
