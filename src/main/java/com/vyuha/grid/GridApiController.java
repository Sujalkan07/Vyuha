package com.vyuha.grid;

import com.vyuha.common.ApiResponse;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Phase 4 Grid & Zone Layout Designer workspace operations.
 */
@RestController
@RequestMapping("/api/events/{eventId}/grid")
public class GridApiController {

    private final GridZoneService gridZoneService;

    public GridApiController(GridZoneService gridZoneService) {
        this.gridZoneService = gridZoneService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<GridDtos.GridWorkspaceDto>> getGridWorkspace(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(gridZoneService.getGridWorkspace(eventId, hasEditRole(authentication))));
    }

    @PutMapping("/config")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<GridDtos.GridWorkspaceDto>> updateGridConfig(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody GridDtos.GridConfigRequest request,
            Authentication authentication) {
        GridDtos.GridWorkspaceDto dto = gridZoneService.updateGridConfig(
                eventId, request, hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Grid configuration updated.", dto));
    }

    @PostMapping("/zones")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<GridDtos.GridWorkspaceDto>> createZone(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody GridDtos.ZoneCreateRequest request,
            Authentication authentication) {
        GridDtos.GridWorkspaceDto dto = gridZoneService.createZone(
                eventId, request, hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Functional zone created.", dto));
    }

    @PutMapping("/zones/{zoneId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<GridDtos.GridWorkspaceDto>> updateZone(
            @PathVariable("eventId") Long eventId,
            @PathVariable("zoneId") Long zoneId,
            @Valid @RequestBody GridDtos.ZoneUpdateRequest request,
            Authentication authentication) {
        GridDtos.GridWorkspaceDto dto = gridZoneService.updateZone(
                eventId, zoneId, request, hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Functional zone updated.", dto));
    }

    @PutMapping("/zones/{zoneId}/cells")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<GridDtos.GridWorkspaceDto>> mutateZoneCells(
            @PathVariable("eventId") Long eventId,
            @PathVariable("zoneId") Long zoneId,
            @Valid @RequestBody GridDtos.ZoneCellsMutationRequest request,
            Authentication authentication) {
        GridDtos.GridWorkspaceDto dto = gridZoneService.mutateZoneCells(
                eventId, zoneId, request, hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Zone cells updated.", dto));
    }

    @DeleteMapping("/zones/{zoneId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<GridDtos.GridWorkspaceDto>> deleteZone(
            @PathVariable("eventId") Long eventId,
            @PathVariable("zoneId") Long zoneId,
            Authentication authentication) {
        GridDtos.GridWorkspaceDto dto = gridZoneService.deleteZone(
                eventId, zoneId, hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Functional zone deleted.", dto));
    }

    @PutMapping("/layout")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<GridDtos.GridWorkspaceDto>> syncZoneLayout(
            @PathVariable("eventId") Long eventId,
            @RequestBody GridDtos.ZoneLayoutSyncRequest request,
            Authentication authentication) {
        GridDtos.GridWorkspaceDto dto = gridZoneService.syncZoneLayout(
                eventId, request, hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Zone layout synchronized.", dto));
    }

    private boolean hasEditRole(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_ORGANIZER".equals(a.getAuthority()));
    }
}
