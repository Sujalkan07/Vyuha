package com.vyuha.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.auth.AppUser;
import com.vyuha.auth.UserRepository;
import com.vyuha.capacity.CapacityDtos;
import com.vyuha.capacity.CapacityService;
import com.vyuha.common.GeometryUtil;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.FunctionalZoneRepository;
import com.vyuha.infrastructure.ConflictDetectionService;
import com.vyuha.infrastructure.FindingReviewStatus;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureElementRepository;
import com.vyuha.infrastructure.InfrastructureService;
import com.vyuha.network.BottleneckScreeningService;
import com.vyuha.network.NetworkDtos;
import com.vyuha.network.NetworkService;
import com.vyuha.network.PathwayEdge;
import com.vyuha.network.PathwayEdgeRepository;
import com.vyuha.network.PathwayNode;
import com.vyuha.network.PathwayNodeRepository;
import com.vyuha.simulation.ComparisonService;
import com.vyuha.simulation.SimulationConfig;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.simulation.SimulationScenarioType;
import com.vyuha.simulation.SimulationService;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionAreaRepository;
import com.vyuha.venue.VenueDtos;
import com.vyuha.venue.VenuePlan;
import com.vyuha.venue.VenuePlanRepository;
import com.vyuha.venue.VenueService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.locationtech.jts.geom.Polygon;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Coordinates Phase 11 Planning Assistant operations:
 * read-only finding explanations, explicit-confirmation What-If simulation runner,
 * guideline searches, and interaction audit logging.
 */
@Service
public class PlanningAssistantService {

    private final EventService eventService;
    private final VenueService venueService;
    private final CapacityService capacityService;
    private final InfrastructureService infrastructureService;
    private final NetworkService networkService;
    private final SimulationService simulationService;
    private final ComparisonService comparisonService;
    private final ConflictDetectionService conflictDetectionService;
    private final BottleneckScreeningService bottleneckScreeningService;
    private final VenuePlanRepository venuePlanRepository;
    private final ExclusionAreaRepository exclusionAreaRepository;
    private final FunctionalZoneRepository functionalZoneRepository;
    private final InfrastructureElementRepository infrastructureElementRepository;
    private final PathwayNodeRepository pathwayNodeRepository;
    private final PathwayEdgeRepository pathwayEdgeRepository;
    private final GeometryUtil geometryUtil;
    private final GuidelineSearchService guidelineSearchService;
    private final AdvisorService advisorService;
    private final ScenarioParserService scenarioParserService;
    private final AssistantProvider assistantProvider;
    private final AssistantInteractionLogRepository logRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public PlanningAssistantService(EventService eventService,
                                    VenueService venueService,
                                    CapacityService capacityService,
                                    InfrastructureService infrastructureService,
                                    NetworkService networkService,
                                    SimulationService simulationService,
                                    ComparisonService comparisonService,
                                    ConflictDetectionService conflictDetectionService,
                                    BottleneckScreeningService bottleneckScreeningService,
                                    VenuePlanRepository venuePlanRepository,
                                    ExclusionAreaRepository exclusionAreaRepository,
                                    FunctionalZoneRepository functionalZoneRepository,
                                    InfrastructureElementRepository infrastructureElementRepository,
                                    PathwayNodeRepository pathwayNodeRepository,
                                    PathwayEdgeRepository pathwayEdgeRepository,
                                    GeometryUtil geometryUtil,
                                    GuidelineSearchService guidelineSearchService,
                                    AdvisorService advisorService,
                                    ScenarioParserService scenarioParserService,
                                    AssistantProvider assistantProvider,
                                    AssistantInteractionLogRepository logRepository,
                                    UserRepository userRepository,
                                    ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.venueService = venueService;
        this.capacityService = capacityService;
        this.infrastructureService = infrastructureService;
        this.networkService = networkService;
        this.simulationService = simulationService;
        this.comparisonService = comparisonService;
        this.conflictDetectionService = conflictDetectionService;
        this.bottleneckScreeningService = bottleneckScreeningService;
        this.venuePlanRepository = venuePlanRepository;
        this.exclusionAreaRepository = exclusionAreaRepository;
        this.functionalZoneRepository = functionalZoneRepository;
        this.infrastructureElementRepository = infrastructureElementRepository;
        this.pathwayNodeRepository = pathwayNodeRepository;
        this.pathwayEdgeRepository = pathwayEdgeRepository;
        this.geometryUtil = geometryUtil;
        this.guidelineSearchService = guidelineSearchService;
        this.advisorService = advisorService;
        this.scenarioParserService = scenarioParserService;
        this.assistantProvider = assistantProvider;
        this.logRepository = logRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AssistantDtos.AdvisorResponseDto explainFindings(Long eventId,
                                                            Long userId,
                                                            AssistantDtos.ExplainRequest request) {
        Event event = eventService.getEventOrThrow(eventId);
        String prompt = request != null && request.prompt() != null ? request.prompt().trim() : "";
        String focusArea = request != null ? request.focusArea() : "ALL";

        VenueDtos.VenueWorkspaceDto venueWorkspace = venueService.getWorkspaceState(eventId, false);
        CapacityDtos.CapacityPageView capacityView = capacityService.getCapacityView(eventId, false, false);
        InfrastructureDtos.InfrastructureWorkspaceDto infraWorkspace = infrastructureService.getWorkspace(eventId, false, false);
        NetworkDtos.NetworkWorkspaceDto networkWorkspace = networkService.getWorkspace(eventId, false, false);
        SimulationDtos.SimulationWorkspaceDto simWorkspace = simulationService.getWorkspace(eventId, false, true);

        List<InfrastructureDtos.ConflictFindingDto> activeConflicts = resolveReadOnlyActiveConflicts(eventId, infraWorkspace);
        List<NetworkDtos.BottleneckFindingDto> activeBottlenecks = resolveReadOnlyActiveBottlenecks(
                eventId, capacityView, networkWorkspace);

        SimulationDtos.SimulationRunSummaryDto latestSim =
                (simWorkspace.runs() != null && !simWorkspace.runs().isEmpty())
                        ? simWorkspace.runs().get(0)
                        : null;

        AssistantDtos.AdvisorResponseDto raw = advisorService.explain(new AdvisorService.AdvisorInput(
                eventId,
                event.getName(),
                event.getExpectedAttendance(),
                venueWorkspace,
                capacityView.calculation(),
                activeConflicts,
                activeBottlenecks,
                latestSim,
                focusArea,
                prompt
        ));

        AssistantInteractionLog log = saveLog(
                event,
                userId,
                AssistantMode.EXPLAIN_FINDINGS,
                prompt.isEmpty() ? "[Explain Layout Findings: " + raw.focusArea() + "]" : prompt,
                raw.executiveSummary(),
                raw.citations(),
                null,
                false
        );

        return new AssistantDtos.AdvisorResponseDto(
                raw.attributionTag(),
                raw.readOnlyNotice(),
                raw.provider(),
                raw.eventId(),
                raw.eventName(),
                raw.focusArea(),
                raw.prompt(),
                raw.executiveSummary(),
                raw.verifiedMetrics(),
                raw.recommendations(),
                raw.citations(),
                log.getId(),
                raw.generatedAt()
        );
    }

    @Transactional
    public AssistantDtos.WhatIfPreviewDto previewWhatIf(Long eventId,
                                                        Long userId,
                                                        AssistantDtos.WhatIfPreviewRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a plain-English What-If scenario prompt.");
        }
        Event event = eventService.getEventOrThrow(eventId);
        SimulationDtos.SimulationWorkspaceDto simWorkspace = simulationService.getWorkspace(eventId, false, true);

        ScenarioParserService.ParsedScenarioResult parsed = scenarioParserService.parse(
                request.prompt(),
                simWorkspace.currentLayout(),
                simWorkspace.snapshots(),
                simWorkspace.runs(),
                request
        );

        Map<String, Object> proposedAction = new LinkedHashMap<>();
        proposedAction.put("scenarioType", parsed.parsedScenarioType().name());
        proposedAction.put("config", parsed.proposedConfig());
        proposedAction.put("targetSnapshotId", parsed.targetSnapshotId());
        proposedAction.put("baselineRunId", parsed.baselineRunId());
        proposedAction.put("matchedTargetLabel", parsed.matchedTargetLabel());

        AssistantInteractionLog log = saveLog(
                event,
                userId,
                AssistantMode.WHAT_IF_PREVIEW,
                request.prompt().trim(),
                parsed.confirmationSummary(),
                List.of(),
                proposedAction,
                false
        );

        return new AssistantDtos.WhatIfPreviewDto(
                AssistantConstants.ATTRIBUTION_TAG,
                AssistantConstants.READ_ONLY_NOTICE,
                assistantProvider.providerName(),
                log.getId(),
                true,
                parsed.layoutReadyForSimulation(),
                parsed.readinessMessage(),
                parsed.parsedScenarioType(),
                parsed.parsedScenarioType().getDisplayName(),
                parsed.proposedConfig(),
                parsed.matchedTargetType(),
                parsed.matchedTargetLabel(),
                parsed.targetAutoSelected(),
                parsed.confidence(),
                parsed.targetSnapshotId(),
                parsed.targetSnapshotLabel(),
                parsed.baselineRunId(),
                parsed.baselineRunLabel(),
                parsed.willCreateBaselineRun(),
                parsed.availableExits(),
                parsed.availableCorridors(),
                parsed.parsingNotes(),
                parsed.confirmationSummary()
        );
    }

    @Transactional
    public AssistantDtos.WhatIfExecutionDto confirmAndRunWhatIf(Long eventId,
                                                              Long userId,
                                                              AssistantDtos.WhatIfConfirmRequest request) {
        if (request == null || !request.confirmed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Explicit confirmation (confirmed=true) is required before executing a What-If simulation.");
        }
        Event event = eventService.getEventOrThrow(eventId);
        SimulationDtos.SimulationWorkspaceDto simWorkspace = simulationService.getWorkspace(eventId, false, true);
        if (!simWorkspace.layoutReadyForSimulation()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, simWorkspace.readinessMessage());
        }

        SimulationConfig requestedConfig = SimulationConfig.normalized(
                request.scenarioType(),
                request.randomSeed(),
                request.agentCount(),
                request.durationSeconds(),
                request.gridCellSizeM(),
                request.highDensityThresholdPsm(),
                request.freeFlowSpeedMps(),
                request.targetEdgeUid(),
                request.targetEdgeId(),
                request.targetExitNodeUid(),
                request.targetExitInfraId(),
                request.disruptionStartSec()
        );

        Long snapshotId = request.snapshotId();
        if (snapshotId == null && simWorkspace.snapshots() != null && !simWorkspace.snapshots().isEmpty()) {
            snapshotId = simWorkspace.snapshots().get(0).id();
        }
        if (snapshotId == null) {
            SimulationDtos.LayoutSnapshotDetailDto autoSnap = simulationService.captureSnapshot(
                    eventId,
                    userId,
                    new SimulationDtos.CreateSnapshotRequest(
                            "Assistant Baseline Snapshot",
                            "Auto-captured on confirmed What-If execution",
                            null
                    )
            );
            snapshotId = autoSnap.summary().id();
        }

        // Resolve or execute baseline NORMAL_OPERATIONS run on the same snapshot & seed/agents/duration
        SimulationDtos.SimulationRunSummaryDto baselineRunSummary = findMatchingBaselineRun(
                simWorkspace.runs(), request.baselineRunId(), snapshotId, requestedConfig);

        if (baselineRunSummary == null) {
            SimulationDtos.SimulationRunDetailDto baselineDetail = simulationService.runSimulation(
                    eventId,
                    userId,
                    new SimulationDtos.RunSimulationRequest(
                            snapshotId,
                            "Baseline · Normal Operations (seed " + requestedConfig.randomSeed() + ")",
                            SimulationScenarioType.NORMAL_OPERATIONS,
                            requestedConfig.randomSeed(),
                            requestedConfig.agentCount(),
                            requestedConfig.durationSeconds(),
                            requestedConfig.gridCellSizeM(),
                            requestedConfig.highDensityThresholdPsm(),
                            requestedConfig.freeFlowSpeedMps(),
                            null, null, null, null, 0
                    )
            );
            baselineRunSummary = baselineDetail.run();
        }

        String whatIfLabel = (request.label() != null && !request.label().isBlank())
                ? request.label().trim()
                : "What-If · " + requestedConfig.scenarioType().getDisplayName()
                  + " (seed " + requestedConfig.randomSeed() + ")";

        SimulationDtos.SimulationRunDetailDto whatIfDetail = simulationService.runSimulation(
                eventId,
                userId,
                new SimulationDtos.RunSimulationRequest(
                        snapshotId,
                        whatIfLabel,
                        requestedConfig.scenarioType(),
                        requestedConfig.randomSeed(),
                        requestedConfig.agentCount(),
                        requestedConfig.durationSeconds(),
                        requestedConfig.gridCellSizeM(),
                        requestedConfig.highDensityThresholdPsm(),
                        requestedConfig.freeFlowSpeedMps(),
                        requestedConfig.targetEdgeUid(),
                        requestedConfig.targetEdgeId(),
                        requestedConfig.targetExitNodeUid(),
                        requestedConfig.targetExitInfraId(),
                        requestedConfig.disruptionStartSec()
                )
        );
        SimulationDtos.SimulationRunSummaryDto whatIfRunSummary = whatIfDetail.run();

        SimulationDtos.SimulationComparisonDto comparison = simulationService.compareExistingRuns(
                eventId, baselineRunSummary.id(), whatIfRunSummary.id());

        ComparisonNarrative narrative = buildComparisonNarrative(whatIfRunSummary, baselineRunSummary, comparison);
        String factsJson = serializeSafely(Map.of(
                "scenario", whatIfRunSummary.scenarioDisplayName(),
                "peakDensityDelta", comparison.peakDensity().delta(),
                "peakQueueDelta", comparison.peakQueueLength().delta(),
                "maxWaitDeltaSec", comparison.maxQueueWaitTime().delta(),
                "unservedDelta", comparison.unservedBlockedAgents().delta()
        ));
        String finalSummary = assistantProvider.polishSummary(
                "Summarize this What-If simulation comparison without changing any numbers.",
                factsJson,
                narrative.summary()
        );

        String citationCode = switch (requestedConfig.scenarioType()) {
            case CLOSED_EXIT -> "CLOSED_EXIT";
            case BLOCKED_CORRIDOR -> "BLOCKED_CORRIDOR";
            case ARRIVAL_RUSH -> "ARRIVAL_RUSH";
            case STAGE_CROWDING -> "STAGE_CROWDING";
            default -> "SIMULATION_INTERPRETATION";
        };
        List<GuidelineDocument.GuidelineHit> citations = guidelineSearchService.findCitationsForCode(
                citationCode, requestedConfig.scenarioType().getDisplayName(), 2);

        if (request.interactionLogId() != null) {
            logRepository.findByIdAndEventId(request.interactionLogId(), eventId).ifPresent(previewLog -> {
                previewLog.setConfirmedByUser(true);
                logRepository.save(previewLog);
            });
        }

        AssistantInteractionLog execLog = saveLog(
                event,
                userId,
                AssistantMode.WHAT_IF_EXECUTED,
                "Confirmed What-If: " + whatIfLabel,
                finalSummary,
                citations,
                Map.of(
                        "whatIfRunId", whatIfRunSummary.id(),
                        "baselineRunId", baselineRunSummary.id(),
                        "scenarioType", requestedConfig.scenarioType().name()
                ),
                true
        );

        String replayUrl = "/events/" + eventId + "/simulation?runId=" + whatIfRunSummary.id();
        String compareUrl = "/events/" + eventId + "/simulation?beforeRunId="
                + baselineRunSummary.id() + "&afterRunId=" + whatIfRunSummary.id();

        return new AssistantDtos.WhatIfExecutionDto(
                AssistantConstants.ATTRIBUTION_TAG,
                SimulationDtos.MANDATORY_DISCLAIMER,
                assistantProvider.providerName(),
                execLog.getId(),
                whatIfRunSummary,
                baselineRunSummary,
                comparison,
                finalSummary,
                narrative.takeaways(),
                replayUrl,
                compareUrl,
                citations,
                Instant.now()
        );
    }

    @Transactional
    public GuidelineDocument.GuidelineSearchResult searchEventGuidelines(Long eventId,
                                                                         Long userId,
                                                                         String query,
                                                                         String category,
                                                                         Integer limit) {
        Event event = eventService.getEventOrThrow(eventId);
        GuidelineDocument.GuidelineSearchResult result = guidelineSearchService.search(query, category, limit);
        if (query != null && !query.isBlank()) {
            saveLog(
                    event,
                    userId,
                    AssistantMode.GUIDELINE_SEARCH,
                    query.trim(),
                    String.format(Locale.US, "Found %d matching guideline section(s).", result.totalMatches()),
                    result.hits(),
                    null,
                    false
            );
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<AssistantDtos.InteractionLogSummaryDto> getHistory(Long eventId) {
        eventService.getEventOrThrow(eventId);
        return logRepository.findByEventIdWithUserOrderByCreatedDesc(eventId).stream()
                .limit(20)
                .map(l -> new AssistantDtos.InteractionLogSummaryDto(
                        l.getId(),
                        l.getMode(),
                        l.getMode().getDisplayName(),
                        l.getPrompt(),
                        truncate(l.getResponseText(), 220),
                        l.isConfirmedByUser(),
                        l.getUser() != null ? l.getUser().getFullName() : "User",
                        l.getAudit().getCreatedAt()
                ))
                .toList();
    }

    private List<InfrastructureDtos.ConflictFindingDto> resolveReadOnlyActiveConflicts(
            Long eventId,
            InfrastructureDtos.InfrastructureWorkspaceDto infraWorkspace) {
        if (infraWorkspace.activeFindings() != null && !infraWorkspace.activeFindings().isEmpty()) {
            return infraWorkspace.activeFindings();
        }
        if (infraWorkspace.elements() == null || infraWorkspace.elements().isEmpty()) {
            return List.of();
        }
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);
        if (plan == null) {
            return List.of();
        }
        Polygon boundary = null;
        if (plan.getBoundaryWkt() != null && !plan.getBoundaryWkt().isBlank()) {
            try {
                boundary = geometryUtil.parsePolygon(plan.getBoundaryWkt());
            } catch (IllegalArgumentException ignored) {
            }
        }
        List<ExclusionArea> exclusions = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<FunctionalZone> zones = functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<InfrastructureElement> elements = infrastructureElementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());

        return conflictDetectionService.detect(boundary, exclusions, zones, elements).stream()
                .map(dc -> new InfrastructureDtos.ConflictFindingDto(
                        null,
                        dc.findingKey(),
                        dc.conflictType(),
                        dc.conflictType().getDisplayName(),
                        dc.severity(),
                        dc.title(),
                        dc.description(),
                        dc.measuredValue(),
                        dc.locationX(),
                        dc.locationY(),
                        dc.involvedElementIds(),
                        true,
                        FindingReviewStatus.OPEN,
                        FindingReviewStatus.OPEN.getDisplayName(),
                        null,
                        null,
                        null,
                        null
                ))
                .toList();
    }

    private List<NetworkDtos.BottleneckFindingDto> resolveReadOnlyActiveBottlenecks(
            Long eventId,
            CapacityDtos.CapacityPageView capacityView,
            NetworkDtos.NetworkWorkspaceDto networkWorkspace) {
        if (networkWorkspace.activeFindings() != null && !networkWorkspace.activeFindings().isEmpty()) {
            return networkWorkspace.activeFindings();
        }
        if ((networkWorkspace.nodes() == null || networkWorkspace.nodes().isEmpty())
                && (networkWorkspace.edges() == null || networkWorkspace.edges().isEmpty())) {
            return List.of();
        }
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);
        if (plan == null) {
            return List.of();
        }
        List<FunctionalZone> zones = functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<InfrastructureElement> elements = infrastructureElementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<PathwayNode> nodes = pathwayNodeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<PathwayEdge> edges = pathwayEdgeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());

        Integer ingressWindow = capacityView != null && capacityView.parameters() != null
                ? capacityView.parameters().ingressWindowMinutes()
                : null;
        BottleneckScreeningService.ScreeningResult result = bottleneckScreeningService.screen(
                zones, elements, nodes, edges, networkWorkspace.expectedAttendance(), ingressWindow, null);

        return result.findings().stream()
                .map(db -> new NetworkDtos.BottleneckFindingDto(
                        null,
                        db.findingKey(),
                        db.findingType(),
                        db.findingType().getDisplayName(),
                        db.severity(),
                        db.title(),
                        db.description(),
                        db.measuredValue(),
                        db.thresholdNote(),
                        db.locationX(),
                        db.locationY(),
                        db.involvedNodeIds(),
                        db.involvedEdgeIds(),
                        true,
                        FindingReviewStatus.OPEN,
                        FindingReviewStatus.OPEN.getDisplayName(),
                        null,
                        null,
                        null,
                        null
                ))
                .toList();
    }

    private SimulationDtos.SimulationRunSummaryDto findMatchingBaselineRun(
            List<SimulationDtos.SimulationRunSummaryDto> runs,
            Long requestedBaselineRunId,
            Long snapshotId,
            SimulationConfig config) {
        if (runs == null || runs.isEmpty()) {
            return null;
        }
        if (requestedBaselineRunId != null) {
            for (SimulationDtos.SimulationRunSummaryDto r : runs) {
                if (requestedBaselineRunId.equals(r.id())) {
                    return r;
                }
            }
        }
        for (SimulationDtos.SimulationRunSummaryDto r : runs) {
            if (r.scenarioType() == SimulationScenarioType.NORMAL_OPERATIONS
                    && snapshotId.equals(r.snapshotId())
                    && r.randomSeed() == config.randomSeed()
                    && r.agentCount() == config.agentCount()
                    && r.durationSeconds() == config.durationSeconds()) {
                return r;
            }
        }
        return null;
    }

    private ComparisonNarrative buildComparisonNarrative(SimulationDtos.SimulationRunSummaryDto whatIfRun,
                                                       SimulationDtos.SimulationRunSummaryDto baselineRun,
                                                       SimulationDtos.SimulationComparisonDto comparison) {
        List<String> takeaways = new ArrayList<>();
        boolean sameCoreInputs = whatIfRun.randomSeed() == baselineRun.randomSeed()
                && whatIfRun.agentCount() == baselineRun.agentCount()
                && whatIfRun.durationSeconds() == baselineRun.durationSeconds();
        if (sameCoreInputs) {
            takeaways.add(String.format(Locale.US,
                    "Controlled comparison using identical seed (%d), %d agents, and %d s duration.",
                    whatIfRun.randomSeed(), whatIfRun.agentCount(), whatIfRun.durationSeconds()));
        }
        if (comparison.narrativeNotes() != null) {
            for (String note : comparison.narrativeNotes()) {
                if (sameCoreInputs && note != null && note.startsWith("Warning: Simulation inputs differ")) {
                    continue;
                }
                takeaways.add(note);
            }
        }
        if (whatIfRun.metrics() != null && whatIfRun.metrics().disruptionAppliedNote() != null
                && !whatIfRun.metrics().disruptionAppliedNote().isBlank()) {
            takeaways.add("Disruption applied: " + whatIfRun.metrics().disruptionAppliedNote());
        }

        String summary = String.format(Locale.US,
                "Confirmed What-If simulation '%s' vs. baseline '%s' (seed %d, %d agents): "
                + "peak local density changed from %.2f to %.2f p/m² (%+.2f p/m²), "
                + "peak queue changed from %.0f to %.0f persons (%+.0f), "
                + "max wait changed from %.1f to %.1f s, and unserved/blocked agents changed from %.0f to %.0f.",
                whatIfRun.label(),
                baselineRun.label(),
                whatIfRun.randomSeed(),
                whatIfRun.agentCount(),
                comparison.peakDensity().beforeValue(),
                comparison.peakDensity().afterValue(),
                comparison.peakDensity().delta(),
                comparison.peakQueueLength().beforeValue(),
                comparison.peakQueueLength().afterValue(),
                comparison.peakQueueLength().delta(),
                comparison.maxQueueWaitTime().beforeValue(),
                comparison.maxQueueWaitTime().afterValue(),
                comparison.unservedBlockedAgents().beforeValue(),
                comparison.unservedBlockedAgents().afterValue());

        return new ComparisonNarrative(summary, List.copyOf(takeaways));
    }

    private AssistantInteractionLog saveLog(Event event,
                                          Long userId,
                                          AssistantMode mode,
                                          String prompt,
                                          String responseText,
                                          Object citations,
                                          Object proposedAction,
                                          boolean confirmedByUser) {
        AppUser user = userId != null ? userRepository.findById(userId).orElse(null) : null;
        AssistantInteractionLog log = new AssistantInteractionLog();
        log.setEvent(event);
        log.setUser(user);
        log.setMode(mode);
        log.setPrompt(truncate(prompt, 2000));
        log.setResponseText(responseText != null ? responseText : "");
        log.setCitationsJson(citations != null ? serializeSafely(citations) : null);
        log.setProposedActionJson(proposedAction != null ? serializeSafely(proposedAction) : null);
        log.setConfirmedByUser(confirmedByUser);
        return logRepository.save(log);
    }

    private String serializeSafely(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String truncate(String val, int maxLen) {
        if (val == null) {
            return "";
        }
        return val.length() > maxLen ? val.substring(0, maxLen) : val;
    }

    private record ComparisonNarrative(String summary, List<String> takeaways) {
    }
}
