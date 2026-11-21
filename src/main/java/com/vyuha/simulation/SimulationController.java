package com.vyuha.simulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import com.vyuha.infrastructure.InfrastructureApiController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * MVC controller serving the Phase 9 Crowd Simulation Playback & Before-and-After Comparison workspace
 * mounted inside fragments/workspace-shell.html.
 */
@Controller
@RequestMapping("/events/{eventId}/simulation")
public class SimulationController {

    private final EventService eventService;
    private final SimulationService simulationService;
    private final ObjectMapper objectMapper;

    public SimulationController(EventService eventService,
                                SimulationService simulationService,
                                ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.simulationService = simulationService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public String simulationWorkspace(@PathVariable("eventId") Long eventId,
                                      Authentication authentication,
                                      Model model) throws JsonProcessingException {
        boolean canManageSnapshots = InfrastructureApiController.hasEditRole(authentication);
        boolean canRunSimulation = true;
        Event event = eventService.getEventOrThrow(eventId);
        SimulationDtos.SimulationWorkspaceDto workspace =
                simulationService.getWorkspace(eventId, canManageSnapshots, canRunSimulation);

        model.addAttribute("event", event);
        model.addAttribute("canManageSnapshots", canManageSnapshots);
        model.addAttribute("canRunSimulation", canRunSimulation);
        model.addAttribute("scenarios", SimulationScenarioType.values());
        model.addAttribute("workspace", workspace);
        model.addAttribute("initialStateJson", objectMapper.writeValueAsString(workspace));
        return "sim/index";
    }
}
