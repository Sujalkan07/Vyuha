package com.vyuha.assistant;

import com.vyuha.auth.CustomUserDetails;
import com.vyuha.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the Phase 11 Planning Assistant Drawer (Explain Findings, What-If Preview/Confirm,
 * Guideline Search, and Assistant Interaction History).
 */
@RestController
@RequestMapping("/api/events/{eventId}/assistant")
@PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
public class AssistantApiController {

    private final PlanningAssistantService assistantService;

    public AssistantApiController(PlanningAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<AssistantDtos.InteractionLogSummaryDto>>> getHistory(
            @PathVariable("eventId") Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(assistantService.getHistory(eventId)));
    }

    @PostMapping("/explain")
    public ResponseEntity<ApiResponse<AssistantDtos.AdvisorResponseDto>> explainFindings(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody(required = false) AssistantDtos.ExplainRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        Long userId = currentUser != null ? currentUser.getId() : null;
        AssistantDtos.AdvisorResponseDto dto = assistantService.explainFindings(eventId, userId, request);
        return ResponseEntity.ok(ApiResponse.ok("Assistant findings explanation ready.", dto));
    }

    @PostMapping("/what-if/preview")
    public ResponseEntity<ApiResponse<AssistantDtos.WhatIfPreviewDto>> previewWhatIf(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody AssistantDtos.WhatIfPreviewRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        Long userId = currentUser != null ? currentUser.getId() : null;
        AssistantDtos.WhatIfPreviewDto dto = assistantService.previewWhatIf(eventId, userId, request);
        return ResponseEntity.ok(ApiResponse.ok("Parsed scenario parameters ready for confirmation.", dto));
    }

    @PostMapping("/what-if/confirm")
    public ResponseEntity<ApiResponse<AssistantDtos.WhatIfExecutionDto>> confirmAndRunWhatIf(
            @PathVariable("eventId") Long eventId,
            @Valid @RequestBody AssistantDtos.WhatIfConfirmRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        Long userId = currentUser != null ? currentUser.getId() : null;
        AssistantDtos.WhatIfExecutionDto dto = assistantService.confirmAndRunWhatIf(eventId, userId, request);
        return ResponseEntity.ok(ApiResponse.ok("Confirmed What-If simulation completed.", dto));
    }

    @GetMapping("/guidelines")
    public ResponseEntity<ApiResponse<GuidelineDocument.GuidelineSearchResult>> searchEventGuidelines(
            @PathVariable("eventId") Long eventId,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "limit", required = false) Integer limit,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        Long userId = currentUser != null ? currentUser.getId() : null;
        GuidelineDocument.GuidelineSearchResult result = assistantService.searchEventGuidelines(
                eventId, userId, query, category, limit);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
