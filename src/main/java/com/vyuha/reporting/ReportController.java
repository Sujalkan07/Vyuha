package com.vyuha.reporting;

import com.vyuha.auth.CustomUserDetails;
import com.vyuha.event.Event;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * MVC controller for Phase 12 Reports & Exports hub and one-click Campus Festival demo reset.
 */
@Controller
public class ReportController {

    private final ReportExportService reportExportService;
    private final CampusFestivalSeedService campusFestivalSeedService;

    public ReportController(ReportExportService reportExportService,
                            CampusFestivalSeedService campusFestivalSeedService) {
        this.reportExportService = reportExportService;
        this.campusFestivalSeedService = campusFestivalSeedService;
    }

    @GetMapping("/events/{eventId}/reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public String reportsHub(@PathVariable("eventId") Long eventId,
                             @AuthenticationPrincipal CustomUserDetails currentUser,
                             Model model) {
        ReportDtos.ReportPageSummary summary = reportExportService.getReportSummary(eventId);
        boolean canResetDemo = currentUser != null && currentUser.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_ORGANIZER".equals(a.getAuthority()));

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("eventId", eventId);
        model.addAttribute("summary", summary);
        model.addAttribute("canResetDemo", canResetDemo);
        model.addAttribute("decisionSupportNotice", ReportDtos.DECISION_SUPPORT_NOTICE);
        model.addAttribute("simulationDisclaimer", ReportDtos.SIMULATION_DISCLAIMER);
        model.addAttribute("emergencyNotice", ReportDtos.EMERGENCY_DEFERENCE_NOTICE);
        return "report/index";
    }

    @PostMapping("/events/demo-reset")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String resetDemoFromDashboard(@AuthenticationPrincipal CustomUserDetails currentUser,
                                         RedirectAttributes redirectAttributes) {
        Long actorId = currentUser != null ? currentUser.getId() : null;
        Event demo = campusFestivalSeedService.resetDemoEvent(actorId);
        redirectAttributes.addFlashAttribute("successMessage",
                "Demo event \"" + demo.getName() + "\" has been cleanly reset with calibrated blueprint, V1/V2 layouts, simulations, staff, emergency plan, and passes.");
        return "redirect:/events/" + demo.getId();
    }

    @PostMapping("/events/{eventId}/reports/demo-reset")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    public String resetDemoFromReports(@PathVariable("eventId") Long eventId,
                                       @AuthenticationPrincipal CustomUserDetails currentUser,
                                       RedirectAttributes redirectAttributes) {
        Long actorId = currentUser != null ? currentUser.getId() : null;
        Event demo = campusFestivalSeedService.resetDemoEvent(actorId);
        redirectAttributes.addFlashAttribute("successMessage",
                "Demo event \"" + demo.getName() + "\" has been reset cleanly.");
        return "redirect:/events/" + demo.getId() + "/reports";
    }
}
