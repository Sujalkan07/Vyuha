package com.vyuha.emergency;

import com.vyuha.common.ApiResponse;
import com.vyuha.infrastructure.InfrastructureApiController;
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
 * REST API for Phase 8 Emergency Assets, Emergency Contacts Directory,
 * Route Width/Obstruction Validation, and Interactive Outage Failure-Mode Testing.
 */
@RestController
@RequestMapping("/api/events/{eventId}/emergency")
public class EmergencyApiController {

    private final EmergencyService emergencyService;

    public EmergencyApiController(EmergencyService emergencyService) {
        this.emergencyService = emergencyService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<EmergencyDtos.EmergencyWorkspaceDto>> getWorkspace(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(emergencyService.getWorkspace(
                eventId, InfrastructureApiController.hasEditRole(authentication))));
    }

    @PostMapping("/outage-test")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<EmergencyDtos.OutageAnalysisResult>> runOutageTest(
            @PathVariable("eventId") Long eventId,
            @RequestBody(required = false) EmergencyDtos.OutageTestRequest request) {
        EmergencyDtos.OutageAnalysisResult result = emergencyService.runOutageTest(eventId, request);
        return ResponseEntity.ok(ApiResponse.ok("Emergency outage test evaluated.", result));
    }

    @PostMapping("/assets")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<EmergencyDtos.EmergencyWorkspaceDto>> createAsset(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody EmergencyDtos.EmergencyAssetRequest request,
            Authentication authentication) {
        EmergencyDtos.EmergencyWorkspaceDto dto = emergencyService.createAsset(
                eventId, request, InfrastructureApiController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Emergency asset created.", dto));
    }

    @PutMapping("/assets/{assetId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<EmergencyDtos.EmergencyWorkspaceDto>> updateAsset(
            @PathVariable("eventId") Long eventId,
            @PathVariable("assetId") Long assetId,
            @Valid @RequestBody EmergencyDtos.EmergencyAssetRequest request,
            Authentication authentication) {
        EmergencyDtos.EmergencyWorkspaceDto dto = emergencyService.updateAsset(
                eventId, assetId, request, InfrastructureApiController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Emergency asset updated.", dto));
    }

    @DeleteMapping("/assets/{assetId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<EmergencyDtos.EmergencyWorkspaceDto>> deleteAsset(
            @PathVariable("eventId") Long eventId,
            @PathVariable("assetId") Long assetId,
            Authentication authentication) {
        EmergencyDtos.EmergencyWorkspaceDto dto = emergencyService.deleteAsset(
                eventId, assetId, InfrastructureApiController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Emergency asset deleted.", dto));
    }

    @PostMapping("/contacts")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<EmergencyDtos.EmergencyWorkspaceDto>> createContact(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody EmergencyDtos.EmergencyContactRequest request,
            Authentication authentication) {
        EmergencyDtos.EmergencyWorkspaceDto dto = emergencyService.createContact(
                eventId, request, InfrastructureApiController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Emergency contact added.", dto));
    }

    @PutMapping("/contacts/{contactId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<EmergencyDtos.EmergencyWorkspaceDto>> updateContact(
            @PathVariable("eventId") Long eventId,
            @PathVariable("contactId") Long contactId,
            @Valid @RequestBody EmergencyDtos.EmergencyContactRequest request,
            Authentication authentication) {
        EmergencyDtos.EmergencyWorkspaceDto dto = emergencyService.updateContact(
                eventId, contactId, request, InfrastructureApiController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Emergency contact updated.", dto));
    }

    @DeleteMapping("/contacts/{contactId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<EmergencyDtos.EmergencyWorkspaceDto>> deleteContact(
            @PathVariable("eventId") Long eventId,
            @PathVariable("contactId") Long contactId,
            Authentication authentication) {
        EmergencyDtos.EmergencyWorkspaceDto dto = emergencyService.deleteContact(
                eventId, contactId, InfrastructureApiController.hasEditRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Emergency contact deleted.", dto));
    }
}
