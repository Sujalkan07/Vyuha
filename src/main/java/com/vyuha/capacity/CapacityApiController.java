package com.vyuha.capacity;

import com.vyuha.auth.CustomUserDetails;
import com.vyuha.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Phase 4 Preliminary Capacity Review calculations, citations, and reviewer sign-offs.
 */
@RestController
@RequestMapping("/api/events/{eventId}/capacity")
public class CapacityApiController {

    private final CapacityService capacityService;

    public CapacityApiController(CapacityService capacityService) {
        this.capacityService = capacityService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<CapacityDtos.CapacityPageView>> getCapacity(
            @PathVariable("eventId") Long eventId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(capacityService.getCapacityView(
                eventId,
                CapacityController.hasEditRole(authentication),
                CapacityController.hasVerifyRole(authentication))));
    }

    @PutMapping("/parameters")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<CapacityDtos.CapacityPageView>> updateParameters(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody CapacityDtos.CapacityParametersRequest request,
            Authentication authentication) {
        CapacityDtos.CapacityPageView view = capacityService.updateParameters(
                eventId, request,
                CapacityController.hasEditRole(authentication),
                CapacityController.hasVerifyRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Capacity parameters updated.", view));
    }

    @PutMapping("/zones/{zoneId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public ResponseEntity<ApiResponse<CapacityDtos.CapacityPageView>> saveZoneAssumption(
            @PathVariable("eventId") Long eventId,
            @PathVariable("zoneId") Long zoneId,
            @Valid @RequestBody CapacityDtos.ZoneAssumptionRequest request,
            Authentication authentication) {
        CapacityDtos.CapacityPageView view = capacityService.saveZoneAssumption(
                eventId, zoneId, request,
                CapacityController.hasEditRole(authentication),
                CapacityController.hasVerifyRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Zone density assumption saved.", view));
    }

    @PostMapping("/zones/{zoneId}/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<CapacityDtos.CapacityPageView>> reviewZoneAssumption(
            @PathVariable("eventId") Long eventId,
            @PathVariable("zoneId") Long zoneId,
            @Valid @RequestBody CapacityDtos.ReviewerAssumptionAction action,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        CapacityDtos.CapacityPageView view = capacityService.reviewZoneAssumption(
                eventId, zoneId, currentUser.getId(), action,
                CapacityController.hasEditRole(authentication),
                CapacityController.hasVerifyRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Zone assumption review status updated.", view));
    }

    @PostMapping("/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'SAFETY_REVIEWER')")
    public ResponseEntity<ApiResponse<CapacityDtos.CapacityPageView>> reviewOverallCapacity(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody CapacityDtos.ReviewerOverallAction action,
            @AuthenticationPrincipal CustomUserDetails currentUser,
            Authentication authentication) {
        CapacityDtos.CapacityPageView view = capacityService.reviewOverallCapacity(
                eventId, currentUser.getId(), action,
                CapacityController.hasEditRole(authentication),
                CapacityController.hasVerifyRole(authentication));
        return ResponseEntity.ok(ApiResponse.ok("Overall capacity review updated.", view));
    }
}
