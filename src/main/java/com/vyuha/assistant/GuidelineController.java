package com.vyuha.assistant;

import com.vyuha.auth.CustomUserDetails;
import com.vyuha.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * MVC and REST controller for the standalone Safety Guidelines Reference & offline BM25 search API.
 */
@Controller
public class GuidelineController {

    private final GuidelineSearchService guidelineSearchService;

    public GuidelineController(GuidelineSearchService guidelineSearchService) {
        this.guidelineSearchService = guidelineSearchService;
    }

    @GetMapping("/guidelines")
    @PreAuthorize("isAuthenticated()")
    public String guidelinesPage(@RequestParam(value = "q", required = false) String query,
                                 @RequestParam(value = "category", required = false) String category,
                                 @AuthenticationPrincipal CustomUserDetails user,
                                 Model model) {
        GuidelineDocument.GuidelineSearchResult searchResult =
                guidelineSearchService.search(query, category, 25);
        GuidelineDocument.GuidelineCorpusOverview overview =
                guidelineSearchService.getCorpusOverview();

        model.addAttribute("currentUser", user);
        model.addAttribute("query", query != null ? query.trim() : "");
        model.addAttribute("selectedCategory", searchResult.category() != null ? searchResult.category() : "ALL");
        model.addAttribute("searchResult", searchResult);
        model.addAttribute("overview", overview);
        model.addAttribute("attributionTag", AssistantConstants.ATTRIBUTION_TAG);
        return "guidelines/index";
    }

    @GetMapping("/api/guidelines/search")
    @PreAuthorize("isAuthenticated()")
    @ResponseBody
    public ResponseEntity<ApiResponse<GuidelineDocument.GuidelineSearchResult>> searchApi(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(
                guidelineSearchService.search(query, category, limit != null ? limit : 12)));
    }

    @GetMapping("/api/guidelines/documents")
    @PreAuthorize("isAuthenticated()")
    @ResponseBody
    public ResponseEntity<ApiResponse<GuidelineDocument.GuidelineCorpusOverview>> corpusOverview() {
        return ResponseEntity.ok(ApiResponse.ok(guidelineSearchService.getCorpusOverview()));
    }
}
