package com.vyuha.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Phase 1 authentication, role-aware landing, and admin user listing controller.
 */
@Controller
public class AuthController {

    private final UserRepository userRepository;

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/")
    public String home(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return "redirect:/login";
        }
        boolean isFieldStaff = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_FIELD_STAFF".equals(a.getAuthority()));
        return isFieldStaff ? "redirect:/field" : "redirect:/events";
    }

    @GetMapping("/login")
    public String loginPage(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            return home(authentication);
        }
        return "auth/login";
    }

    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminUsers(Model model) {
        model.addAttribute("users", userRepository.findAllByOrderByIdAsc());
        return "admin/users";
    }

    @GetMapping("/access-denied")
    public String accessDenied(HttpServletResponse response, Model model) {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        model.addAttribute("statusCode", HttpStatus.FORBIDDEN.value());
        model.addAttribute("errorTitle", "Access Denied");
        model.addAttribute("errorMessage", "Your current role does not have permission to open this page.");
        return "error/403";
    }
}
