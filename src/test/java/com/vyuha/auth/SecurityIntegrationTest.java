package com.vyuha.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Test
    void seededDemoAccountsExistInDatabase() {
        assertThat(userRepository.count()).isEqualTo(4);
        assertThat(userRepository.findByEmailIgnoreCase("admin@vyuha.local")).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase("organizer@vyuha.local")).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase("reviewer@vyuha.local")).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase("staff@vyuha.local")).isPresent();
    }

    @Test
    void unauthenticatedRequestsRedirectToLogin() throws Exception {
        mockMvc.perform(get("/events")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost/login"));
        mockMvc.perform(get("/field")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost/login"));
        mockMvc.perform(get("/admin/users")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost/login"));
        mockMvc.perform(get("/guidelines")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void loginPageAndOfflineStylesheetsAreAccessibleWithoutCdnLinks() throws Exception {
        MvcResult loginResult = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Demo Accounts")))
                .andExpect(content().string(containsString("Decision-Support Workspace")))
                .andExpect(content().string(not(containsString("https://"))))
                .andExpect(content().string(not(containsString("http://"))))
                .andReturn();

        assertThat(loginResult.getResponse().getContentAsString())
                .contains("/css/vyuha-tokens.css")
                .contains("/css/vyuha-base.css");

        mockMvc.perform(get("/css/vyuha-tokens.css")).andExpect(status().isOk());
        mockMvc.perform(get("/css/vyuha-base.css")).andExpect(status().isOk());
        mockMvc.perform(get("/css/vyuha-workspace.css")).andExpect(status().isOk());
        mockMvc.perform(get("/css/vyuha-print.css")).andExpect(status().isOk());
    }

    @Test
    void allFourSeededAccountsCanLogInAndOut() throws Exception {
        mockMvc.perform(formLogin("/login").user("email", "admin@vyuha.local").password("VyuhaAdmin123!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/events"));

        mockMvc.perform(formLogin("/login").user("email", "organizer@vyuha.local").password("VyuhaOrg123!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/events"));

        mockMvc.perform(formLogin("/login").user("email", "reviewer@vyuha.local").password("VyuhaRev123!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/events"));

        mockMvc.perform(formLogin("/login").user("email", "staff@vyuha.local").password("VyuhaStaff123!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/field"));

        mockMvc.perform(formLogin("/login").user("email", "admin@vyuha.local").password("WrongPassword"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        mockMvc.perform(logout("/logout"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void roleBasedAccessControlEnforcesPermissions() throws Exception {
        var admin = userDetailsService.loadUserByUsername("admin@vyuha.local");
        var organizer = userDetailsService.loadUserByUsername("organizer@vyuha.local");
        var reviewer = userDetailsService.loadUserByUsername("reviewer@vyuha.local");
        var staff = userDetailsService.loadUserByUsername("staff@vyuha.local");

        // ADMIN: full access
        mockMvc.perform(get("/events").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Decision-Support Workspace")))
                .andExpect(content().string(not(containsString("https://"))));
        mockMvc.perform(get("/admin/users").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("admin@vyuha.local")))
                .andExpect(content().string(containsString("staff@vyuha.local")))
                .andExpect(content().string(not(containsString("https://"))));
        mockMvc.perform(get("/field").with(user(admin))).andExpect(status().isOk());
        mockMvc.perform(get("/guidelines").with(user(admin))).andExpect(status().isOk());

        // ORGANIZER: events, field, guidelines allowed; /admin/** forbidden
        mockMvc.perform(get("/events").with(user(organizer))).andExpect(status().isOk());
        mockMvc.perform(get("/field").with(user(organizer))).andExpect(status().isOk());
        mockMvc.perform(get("/guidelines").with(user(organizer))).andExpect(status().isOk());
        mockMvc.perform(get("/admin/users").with(user(organizer))).andExpect(status().isForbidden());

        // SAFETY_REVIEWER: events & guidelines allowed; /admin/** and /field/** forbidden
        mockMvc.perform(get("/events").with(user(reviewer))).andExpect(status().isOk());
        mockMvc.perform(get("/guidelines").with(user(reviewer))).andExpect(status().isOk());
        mockMvc.perform(get("/admin/users").with(user(reviewer))).andExpect(status().isForbidden());
        mockMvc.perform(get("/field").with(user(reviewer))).andExpect(status().isForbidden());

        // FIELD_STAFF: field & guidelines allowed; /events/** and /admin/** forbidden
        mockMvc.perform(get("/field").with(user(staff)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Field Operations View")))
                .andExpect(content().string(not(containsString("https://"))));
        mockMvc.perform(get("/guidelines").with(user(staff))).andExpect(status().isOk());
        mockMvc.perform(get("/events").with(user(staff))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/users").with(user(staff))).andExpect(status().isForbidden());
    }
}
