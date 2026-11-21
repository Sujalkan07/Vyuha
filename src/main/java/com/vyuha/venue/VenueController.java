package com.vyuha.venue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import com.vyuha.grid.GridDtos;
import com.vyuha.grid.GridZoneService;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.FindingReviewStatus;
import com.vyuha.infrastructure.InfrastructureApiController;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.infrastructure.InfrastructureService;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.NetworkDtos;
import com.vyuha.network.NetworkLayer;
import com.vyuha.network.NetworkService;
import com.vyuha.network.PathwayDirection;
import com.vyuha.network.PathwayNodeType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * MVC controller serving the Venue Plan workspace (Site Plan, Zones & Grid, and Infrastructure modes)
 * mounted inside fragments/workspace-shell.html.
 */
@Controller
@RequestMapping("/events/{eventId}/venue")
public class VenueController {

    private final EventService eventService;
    private final VenueService venueService;
    private final GridZoneService gridZoneService;
    private final InfrastructureService infrastructureService;
    private final NetworkService networkService;
    private final SampleMapService sampleMapService;
    private final ObjectMapper objectMapper;

    public VenueController(EventService eventService,
                           VenueService venueService,
                           GridZoneService gridZoneService,
                           InfrastructureService infrastructureService,
                           NetworkService networkService,
                           SampleMapService sampleMapService,
                           ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.venueService = venueService;
        this.gridZoneService = gridZoneService;
        this.infrastructureService = infrastructureService;
        this.networkService = networkService;
        this.sampleMapService = sampleMapService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER', 'SAFETY_REVIEWER')")
    public String venueWorkspace(@PathVariable("eventId") Long eventId,
                                 @RequestParam(value = "mode", defaultValue = "site") String mode,
                                 Authentication authentication,
                                 Model model) throws JsonProcessingException {
        Event event = eventService.getEventOrThrow(eventId);
        boolean canEdit = hasEditRole(authentication);

        if ("zones".equalsIgnoreCase(mode)) {
            GridDtos.GridWorkspaceDto gridState = gridZoneService.getGridWorkspace(eventId, canEdit);
            model.addAttribute("event", event);
            model.addAttribute("canEdit", canEdit);
            model.addAttribute("zoneTypes", ZoneType.values());
            model.addAttribute("initialState", gridState);
            model.addAttribute("initialStateJson", objectMapper.writeValueAsString(gridState));
            return "venue/zones";
        }

        if ("infrastructure".equalsIgnoreCase(mode)) {
            boolean canReviewFindings = InfrastructureApiController.hasReviewRole(authentication);
            InfrastructureDtos.InfrastructureWorkspaceDto infraState =
                    infrastructureService.getWorkspace(eventId, canEdit, canReviewFindings);
            model.addAttribute("event", event);
            model.addAttribute("canEdit", canEdit);
            model.addAttribute("canReviewFindings", canReviewFindings);
            model.addAttribute("infrastructureTypes", InfrastructureType.values());
            model.addAttribute("reviewStatuses", FindingReviewStatus.values());
            model.addAttribute("initialState", infraState);
            model.addAttribute("initialStateJson", objectMapper.writeValueAsString(infraState));
            return "venue/infrastructure";
        }

        if ("pathways".equalsIgnoreCase(mode)) {
            boolean canReviewFindings = InfrastructureApiController.hasReviewRole(authentication);
            NetworkDtos.NetworkWorkspaceDto netState =
                    networkService.getWorkspace(eventId, canEdit, canReviewFindings);
            model.addAttribute("event", event);
            model.addAttribute("canEdit", canEdit);
            model.addAttribute("canReviewFindings", canReviewFindings);
            model.addAttribute("nodeTypes", PathwayNodeType.values());
            model.addAttribute("networkLayers", NetworkLayer.values());
            model.addAttribute("directions", PathwayDirection.values());
            model.addAttribute("reviewStatuses", FindingReviewStatus.values());
            model.addAttribute("initialState", netState);
            model.addAttribute("initialStateJson", objectMapper.writeValueAsString(netState));
            return "venue/pathways";
        }

        VenueDtos.VenueWorkspaceDto initialState = venueService.getWorkspaceState(eventId, canEdit);
        model.addAttribute("event", event);
        model.addAttribute("canEdit", canEdit);
        model.addAttribute("sampleMaps", sampleMapService.listSampleMaps());
        model.addAttribute("exclusionTypes", ExclusionType.values());
        model.addAttribute("initialState", initialState);
        model.addAttribute("initialStateJson", objectMapper.writeValueAsString(initialState));
        return "venue/workspace";
    }

    static boolean hasEditRole(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_ORGANIZER".equals(a.getAuthority()));
    }
}
