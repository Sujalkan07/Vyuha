package com.vyuha.network;

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
 * REST API for Phase 6 Pedestrian Movement Network & Automated Bottleneck Screening.
 */
@RestController
@RequestMapping("/api/events/{eventId}/network")
public class NetworkApiController {

    private final NetworkService networkService;

    public NetworkApiController(NetworkService networkService) {
        this.networkService = networkService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<NetworkDtos.NetworkWorkspaceDto>> getWorkspace(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(networkService.getWorkspace(
                eventId,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication))));
    }

    @PostMapping("/seed")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<NetworkDtos.NetworkWorkspaceDto>> autoSeedNodes(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        NetworkDtos.NetworkWorkspaceDto dto = networkService.autoSeedNodes(
                eventId,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Seeded pathway nodes from layout.", dto));
    }

    @PostMapping("/nodes")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<NetworkDtos.NetworkWorkspaceDto>> createNode(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody NetworkDtos.CreateNodeRequest request,
            Authentication authentication) {
        NetworkDtos.NetworkWorkspaceDto dto = networkService.createNode(
                eventId,
                request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Pathway node placed.", dto));
    }

    @PutMapping("/nodes/{nodeId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<NetworkDtos.NetworkWorkspaceDto>> updateNode(
            @PathVariable("eventId") Long eventId,
            @PathVariable("nodeId") Long nodeId,
            @Valid @RequestBody NetworkDtos.UpdateNodeRequest request,
            Authentication authentication) {
        NetworkDtos.NetworkWorkspaceDto dto = networkService.updateNode(
                eventId,
                nodeId,
                request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Pathway node updated.", dto));
    }

    @DeleteMapping("/nodes/{nodeId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<NetworkDtos.NetworkWorkspaceDto>> deleteNode(
            @PathVariable("eventId") Long eventId,
            @PathVariable("nodeId") Long nodeId,
            Authentication authentication) {
        NetworkDtos.NetworkWorkspaceDto dto = networkService.deleteNode(
                eventId,
                nodeId,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Pathway node deleted.", dto));
    }

    @PostMapping("/edges")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<NetworkDtos.NetworkWorkspaceDto>> createEdge(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody NetworkDtos.CreateEdgeRequest request,
            Authentication authentication) {
        NetworkDtos.NetworkWorkspaceDto dto = networkService.createEdge(
                eventId,
                request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Pathway edge connected.", dto));
    }

    @PutMapping("/edges/{edgeId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<NetworkDtos.NetworkWorkspaceDto>> updateEdge(
            @PathVariable("eventId") Long eventId,
            @PathVariable("edgeId") Long edgeId,
            @Valid @RequestBody NetworkDtos.UpdateEdgeRequest request,
            Authentication authentication) {
        NetworkDtos.NetworkWorkspaceDto dto = networkService.updateEdge(
                eventId,
                edgeId,
                request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Pathway edge updated.", dto));
    }

    @DeleteMapping("/edges/{edgeId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<NetworkDtos.NetworkWorkspaceDto>> deleteEdge(
            @PathVariable("eventId") Long eventId,
            @PathVariable("edgeId") Long edgeId,
            Authentication authentication) {
        NetworkDtos.NetworkWorkspaceDto dto = networkService.deleteEdge(
                eventId,
                edgeId,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Pathway edge deleted.", dto));
    }

    @PutMapping("/layout")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<NetworkDtos.NetworkWorkspaceDto>> syncLayout(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody NetworkDtos.LayoutSyncRequest request,
            Authentication authentication) {
        NetworkDtos.NetworkWorkspaceDto dto = networkService.syncLayout(
                eventId,
                request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Pathway network synchronized.", dto));
    }

    @PostMapping("/screen")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<NetworkDtos.NetworkWorkspaceDto>> runScreening(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        NetworkDtos.NetworkWorkspaceDto dto = networkService.runScreening(
                eventId,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Bottleneck screening complete.", dto));
    }

    @PostMapping("/findings/{findingId}/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<NetworkDtos.NetworkWorkspaceDto>> reviewFinding(
            @PathVariable("eventId") Long eventId,
            @PathVariable("findingId") Long findingId,
            @Valid @RequestBody NetworkDtos.ReviewBottleneckRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        NetworkDtos.NetworkWorkspaceDto dto = networkService.reviewFinding(
                eventId,
                findingId,
                currentUser != null ? currentUser.getId() : null,
                request,
                InfrastructureApiController.hasEditRole(authentication),
                InfrastructureApiController.hasReviewRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Bottleneck finding review saved.", dto));
    }
}
