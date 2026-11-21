package com.vyuha.simulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.auth.AppUser;
import com.vyuha.auth.UserRepository;
import com.vyuha.common.GeometryUtil;
import com.vyuha.emergency.EmergencyAsset;
import com.vyuha.emergency.EmergencyAssetRepository;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.FunctionalZoneRepository;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureElementRepository;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.PathwayEdge;
import com.vyuha.network.PathwayEdgeRepository;
import com.vyuha.network.PathwayNode;
import com.vyuha.network.PathwayNodeRepository;
import com.vyuha.network.PathwayNodeType;
import com.vyuha.operations.ArrivalWaveInterval;
import com.vyuha.operations.ArrivalWaveIntervalRepository;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionAreaRepository;
import com.vyuha.venue.VenuePlan;
import com.vyuha.venue.VenuePlanRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates Phase 9 Layout Snapshot versioning, deterministic Java crowd simulation runs,
 * artifact storage, and side-by-side Before-and-After comparisons.
 */
@Service
public class SimulationService {

    private final EventService eventService;
    private final UserRepository userRepository;
    private final VenuePlanRepository venuePlanRepository;
    private final ExclusionAreaRepository exclusionAreaRepository;
    private final FunctionalZoneRepository functionalZoneRepository;
    private final InfrastructureElementRepository infrastructureElementRepository;
    private final PathwayNodeRepository pathwayNodeRepository;
    private final PathwayEdgeRepository pathwayEdgeRepository;
    private final ArrivalWaveIntervalRepository arrivalWaveIntervalRepository;
    private final EmergencyAssetRepository emergencyAssetRepository;
    private final LayoutSnapshotRepository snapshotRepository;
    private final SimulationRunRepository runRepository;
    private final CrowdSimulationEngine simulationEngine;
    private final ComparisonService comparisonService;
    private final SimulationStorageService storageService;
    private final GeometryUtil geometryUtil;
    private final ObjectMapper objectMapper;

    public SimulationService(EventService eventService,
                             UserRepository userRepository,
                             VenuePlanRepository venuePlanRepository,
                             ExclusionAreaRepository exclusionAreaRepository,
                             FunctionalZoneRepository functionalZoneRepository,
                             InfrastructureElementRepository infrastructureElementRepository,
                             PathwayNodeRepository pathwayNodeRepository,
                             PathwayEdgeRepository pathwayEdgeRepository,
                             ArrivalWaveIntervalRepository arrivalWaveIntervalRepository,
                             EmergencyAssetRepository emergencyAssetRepository,
                             LayoutSnapshotRepository snapshotRepository,
                             SimulationRunRepository runRepository,
                             CrowdSimulationEngine simulationEngine,
                             ComparisonService comparisonService,
                             SimulationStorageService storageService,
                             GeometryUtil geometryUtil,
                             ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.userRepository = userRepository;
        this.venuePlanRepository = venuePlanRepository;
        this.exclusionAreaRepository = exclusionAreaRepository;
        this.functionalZoneRepository = functionalZoneRepository;
        this.infrastructureElementRepository = infrastructureElementRepository;
        this.pathwayNodeRepository = pathwayNodeRepository;
        this.pathwayEdgeRepository = pathwayEdgeRepository;
        this.arrivalWaveIntervalRepository = arrivalWaveIntervalRepository;
        this.emergencyAssetRepository = emergencyAssetRepository;
        this.snapshotRepository = snapshotRepository;
        this.runRepository = runRepository;
        this.simulationEngine = simulationEngine;
        this.comparisonService = comparisonService;
        this.storageService = storageService;
        this.geometryUtil = geometryUtil;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SimulationDtos.SimulationWorkspaceDto getWorkspace(Long eventId,
                                                              boolean canManageSnapshots,
                                                              boolean canRunSimulation) {
        Event event = eventService.getEventOrThrow(eventId);
        SimulationDtos.SerializedLayout currentLayout = buildSerializedLayout(event);
        Readiness readiness = evaluateReadiness(currentLayout);

        List<SimulationDtos.LayoutSnapshotSummaryDto> snapshots = snapshotRepository
                .findByEventIdOrderByVersionNumberDesc(eventId).stream()
                .map(s -> toSnapshotSummary(eventId, s))
                .toList();

        List<SimulationDtos.SimulationRunSummaryDto> runs = runRepository
                .findByEventIdWithSnapshotOrderByCreatedDesc(eventId).stream()
                .map(r -> toRunSummary(eventId, r))
                .toList();

        List<SimulationDtos.ScenarioOptionDto> scenarios = Arrays.stream(SimulationScenarioType.values())
                .map(t -> new SimulationDtos.ScenarioOptionDto(
                        t, t.getDisplayName(), t.getDescription(), t.requiresTargetEdge(), t.requiresTargetExit()))
                .toList();

        return new SimulationDtos.SimulationWorkspaceDto(
                eventId,
                event.getName(),
                event.getExpectedAttendance(),
                canManageSnapshots,
                canRunSimulation,
                SimulationDtos.MANDATORY_DISCLAIMER,
                readiness.ready(),
                readiness.message(),
                currentLayout,
                snapshots,
                runs,
                scenarios
        );
    }

    @Transactional
    public SimulationDtos.LayoutSnapshotDetailDto captureSnapshot(Long eventId,
                                                                  Long userId,
                                                                  SimulationDtos.CreateSnapshotRequest request) {
        Event event = eventService.getEventOrThrow(eventId);
        SimulationDtos.SerializedLayout layout = buildSerializedLayout(event);
        LayoutSnapshot saved = createAndPersistSnapshot(
                event,
                userId,
                layout,
                request != null ? request.label() : null,
                request != null ? request.notes() : null,
                request != null ? request.thumbnailDataUrl() : null);

        return new SimulationDtos.LayoutSnapshotDetailDto(toSnapshotSummary(eventId, saved), layout);
    }

    @Transactional(readOnly = true)
    public SimulationDtos.LayoutSnapshotDetailDto getSnapshotDetail(Long eventId, Long snapshotId) {
        eventService.getEventOrThrow(eventId);
        LayoutSnapshot snap = snapshotRepository.findByIdAndEventId(snapshotId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Layout snapshot not found."));
        SimulationDtos.SerializedLayout layout = deserializeLayout(snap.getSerializedLayoutJson());
        return new SimulationDtos.LayoutSnapshotDetailDto(toSnapshotSummary(eventId, snap), layout);
    }

    @Transactional(readOnly = true)
    public Resource loadSnapshotThumbnail(Long eventId, Long snapshotId) {
        eventService.getEventOrThrow(eventId);
        LayoutSnapshot snap = snapshotRepository.findByIdAndEventId(snapshotId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Layout snapshot not found."));
        return storageService.loadThumbnailResource(eventId, snap.getThumbnailPngPath());
    }

    @Transactional
    public void deleteSnapshot(Long eventId, Long snapshotId) {
        eventService.getEventOrThrow(eventId);
        LayoutSnapshot snap = snapshotRepository.findByIdAndEventId(snapshotId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Layout snapshot not found."));
        List<SimulationRun> childRuns = runRepository.findBySnapshotId(snap.getId());
        for (SimulationRun run : childRuns) {
            storageService.deleteFileIfExists(eventId, run.getFrameStreamPath());
        }
        String thumbPath = snap.getThumbnailPngPath();
        snapshotRepository.delete(snap);
        snapshotRepository.flush();
        storageService.deleteFileIfExists(eventId, thumbPath);
    }

    @Transactional
    public SimulationDtos.SimulationRunDetailDto runSimulation(Long eventId,
                                                               Long userId,
                                                               SimulationDtos.RunSimulationRequest request) {
        Event event = eventService.getEventOrThrow(eventId);
        LayoutSnapshot snapshot;
        SimulationDtos.SerializedLayout layout;
        if (request != null && request.snapshotId() != null) {
            snapshot = snapshotRepository.findByIdAndEventId(request.snapshotId(), eventId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Layout snapshot not found."));
            layout = deserializeLayout(snapshot.getSerializedLayoutJson());
        } else {
            layout = buildSerializedLayout(event);
            snapshot = createAndPersistSnapshot(
                    event, userId, layout, "Baseline Layout", "Auto-captured for simulation run", null);
        }

        SimulationConfig config = SimulationConfig.normalized(
                request != null ? request.scenarioType() : null,
                request != null ? request.randomSeed() : null,
                request != null ? request.agentCount() : null,
                request != null ? request.durationSeconds() : null,
                request != null ? request.gridCellSizeM() : null,
                request != null ? request.highDensityThresholdPsm() : null,
                request != null ? request.freeFlowSpeedMps() : null,
                request != null ? request.targetEdgeUid() : null,
                request != null ? request.targetEdgeId() : null,
                request != null ? request.targetExitNodeUid() : null,
                request != null ? request.targetExitInfraId() : null,
                request != null ? request.disruptionStartSec() : null
        );

        SimulationRun saved = executeAndPersistRun(
                event,
                snapshot,
                layout,
                config,
                userId,
                request != null ? request.label() : null);

        SimulationDtos.SimulationFrameStream frameStream = storageService.loadFrameStream(eventId, saved.getFrameStreamPath());
        return new SimulationDtos.SimulationRunDetailDto(toRunSummary(eventId, saved), layout, frameStream);
    }

    @Transactional(readOnly = true)
    public SimulationDtos.SimulationRunDetailDto getRunDetail(Long eventId, Long runId) {
        eventService.getEventOrThrow(eventId);
        SimulationRun run = runRepository.findByIdAndEventIdWithSnapshot(runId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation run not found."));
        SimulationDtos.SerializedLayout layout = deserializeLayout(run.getSnapshot().getSerializedLayoutJson());
        SimulationDtos.SimulationFrameStream frameStream = storageService.loadFrameStream(eventId, run.getFrameStreamPath());
        return new SimulationDtos.SimulationRunDetailDto(toRunSummary(eventId, run), layout, frameStream);
    }

    @Transactional(readOnly = true)
    public SimulationDtos.SimulationFrameStream getRunFrames(Long eventId, Long runId) {
        eventService.getEventOrThrow(eventId);
        SimulationRun run = runRepository.findByIdAndEventIdWithSnapshot(runId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation run not found."));
        return storageService.loadFrameStream(eventId, run.getFrameStreamPath());
    }

    @Transactional
    public void deleteRun(Long eventId, Long runId) {
        eventService.getEventOrThrow(eventId);
        SimulationRun run = runRepository.findByIdAndEventIdWithSnapshot(runId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation run not found."));
        String framePath = run.getFrameStreamPath();
        runRepository.delete(run);
        runRepository.flush();
        storageService.deleteFileIfExists(eventId, framePath);
    }

    @Transactional
    public SimulationDtos.SimulationComparisonDto runPairedComparison(Long eventId,
                                                                      Long userId,
                                                                      SimulationDtos.CompareSnapshotsRequest request) {
        Event event = eventService.getEventOrThrow(eventId);
        if (request == null || request.beforeSnapshotId() == null || request.afterSnapshotId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Select both a Before snapshot and an After snapshot to run paired comparison.");
        }

        LayoutSnapshot beforeSnap = snapshotRepository.findByIdAndEventId(request.beforeSnapshotId(), eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Before snapshot not found."));
        LayoutSnapshot afterSnap = snapshotRepository.findByIdAndEventId(request.afterSnapshotId(), eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "After snapshot not found."));

        SimulationDtos.SerializedLayout beforeLayout = deserializeLayout(beforeSnap.getSerializedLayoutJson());
        SimulationDtos.SerializedLayout afterLayout = deserializeLayout(afterSnap.getSerializedLayoutJson());

        SimulationConfig sharedConfig = SimulationConfig.normalized(
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

        SimulationRun beforeRun = executeAndPersistRun(
                event, beforeSnap, beforeLayout, sharedConfig, userId,
                "Before V" + beforeSnap.getVersionNumber() + " · " + sharedConfig.scenarioType().getDisplayName());
        SimulationRun afterRun = executeAndPersistRun(
                event, afterSnap, afterLayout, sharedConfig, userId,
                "After V" + afterSnap.getVersionNumber() + " · " + sharedConfig.scenarioType().getDisplayName());

        return comparisonService.compare(
                toRunSummary(eventId, beforeRun),
                toRunSummary(eventId, afterRun),
                beforeLayout,
                afterLayout,
                framesUrl(eventId, beforeRun.getId()),
                framesUrl(eventId, afterRun.getId()));
    }

    @Transactional(readOnly = true)
    public SimulationDtos.SimulationComparisonDto compareExistingRuns(Long eventId, Long beforeRunId, Long afterRunId) {
        eventService.getEventOrThrow(eventId);
        if (beforeRunId == null || afterRunId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both beforeRunId and afterRunId are required.");
        }
        SimulationRun beforeRun = runRepository.findByIdAndEventIdWithSnapshot(beforeRunId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Before simulation run not found."));
        SimulationRun afterRun = runRepository.findByIdAndEventIdWithSnapshot(afterRunId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "After simulation run not found."));

        SimulationDtos.SerializedLayout beforeLayout = deserializeLayout(beforeRun.getSnapshot().getSerializedLayoutJson());
        SimulationDtos.SerializedLayout afterLayout = deserializeLayout(afterRun.getSnapshot().getSerializedLayoutJson());

        return comparisonService.compare(
                toRunSummary(eventId, beforeRun),
                toRunSummary(eventId, afterRun),
                beforeLayout,
                afterLayout,
                framesUrl(eventId, beforeRun.getId()),
                framesUrl(eventId, afterRun.getId()));
    }

    private LayoutSnapshot createAndPersistSnapshot(Event event,
                                                    Long userId,
                                                    SimulationDtos.SerializedLayout layout,
                                                    String rawLabel,
                                                    String notes,
                                                    String thumbnailDataUrl) {
        int nextVersion = snapshotRepository.findMaxVersionNumber(event.getId()) + 1;
        String label = (rawLabel != null && !rawLabel.isBlank())
                ? truncate(rawLabel.trim(), 160)
                : "Layout Snapshot V" + nextVersion;

        String thumbPath = (thumbnailDataUrl != null && !thumbnailDataUrl.isBlank())
                ? storageService.saveThumbnailFromDataUrl(event.getId(), thumbnailDataUrl)
                : storageService.renderFallbackThumbnailPng(event.getId(), layout);

        AppUser user = resolveUser(userId);
        LayoutSnapshot snap = new LayoutSnapshot();
        snap.setEvent(event);
        snap.setVersionNumber(nextVersion);
        snap.setLabel(label);
        snap.setNotes(truncate(trimToNull(notes), 500));
        snap.setSerializedLayoutJson(serializeJson(layout));
        snap.setThumbnailPngPath(thumbPath);
        snap.setCreatedBy(user);
        return snapshotRepository.saveAndFlush(snap);
    }

    private SimulationRun executeAndPersistRun(Event event,
                                               LayoutSnapshot snapshot,
                                               SimulationDtos.SerializedLayout layout,
                                               SimulationConfig config,
                                               Long userId,
                                               String customLabel) {
        CrowdSimulationEngine.SimulationExecutionResult result;
        try {
            result = simulationEngine.simulate(layout, config);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }

        String framePath = storageService.saveFrameStream(event.getId(), result.frameStream());
        AppUser user = resolveUser(userId);

        String runLabel = (customLabel != null && !customLabel.isBlank())
                ? truncate(customLabel.trim(), 160)
                : truncate(String.format(Locale.US, "V%d · %s (seed %d)",
                snapshot.getVersionNumber(), config.scenarioType().getDisplayName(), config.randomSeed()), 160);

        SimulationRun run = new SimulationRun();
        run.setEvent(event);
        run.setSnapshot(snapshot);
        run.setLabel(runLabel);
        run.setScenarioType(config.scenarioType());
        run.setRandomSeed(config.randomSeed());
        run.setDurationSeconds(config.durationSeconds());
        run.setAgentCount(config.agentCount());
        run.setConfigJson(serializeJson(config));
        run.setMetricsSummaryJson(serializeJson(result.metrics()));
        run.setFrameStreamPath(framePath);
        run.setCreatedBy(user);
        return runRepository.saveAndFlush(run);
    }

    public SimulationDtos.SerializedLayout buildSerializedLayout(Event event) {
        Long eventId = event.getId();
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);

        List<ExclusionArea> exclusions = plan != null
                ? exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<FunctionalZone> zones = plan != null
                ? functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<InfrastructureElement> elements = plan != null
                ? infrastructureElementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<PathwayNode> nodes = plan != null
                ? pathwayNodeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<PathwayEdge> edges = plan != null
                ? pathwayEdgeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<ArrivalWaveInterval> waves = arrivalWaveIntervalRepository.findByEventIdWithGate(eventId);
        List<EmergencyAsset> assets = emergencyAssetRepository.findByEventIdOrderByAssetTypeAscIdAsc(eventId);

        Envelope env = new Envelope();
        String boundaryWkt = plan != null ? plan.getBoundaryWkt() : null;
        List<GeometryUtil.Point2D> boundaryPoints = List.of();
        if (boundaryWkt != null && !boundaryWkt.isBlank()) {
            try {
                Polygon boundary = geometryUtil.parsePolygon(boundaryWkt);
                boundaryPoints = geometryUtil.extractOuterRing(boundary);
                env.expandToInclude(boundary.getEnvelopeInternal());
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<SimulationDtos.SnapExclusion> snapExclusions = new ArrayList<>(exclusions.size());
        for (ExclusionArea ex : exclusions) {
            try {
                Polygon poly = geometryUtil.parsePolygon(ex.getPolygonWkt());
                env.expandToInclude(poly.getEnvelopeInternal());
                snapExclusions.add(new SimulationDtos.SnapExclusion(
                        ex.getId(),
                        ex.getLabel(),
                        ex.getExclusionType().name(),
                        ex.getExclusionType().isCorridor(),
                        ex.getExclusionType().getFillColor(),
                        ex.getExclusionType().getStrokeColor(),
                        ex.getPolygonWkt(),
                        geometryUtil.extractOuterRing(poly)
                ));
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<SimulationDtos.SnapZone> snapZones = new ArrayList<>(zones.size());
        for (FunctionalZone z : zones) {
            try {
                Geometry geom = geometryUtil.parsePolygonal(z.getPolygonWkt());
                env.expandToInclude(geom.getEnvelopeInternal());
                snapZones.add(new SimulationDtos.SnapZone(
                        z.getId(),
                        z.getZoneCode(),
                        z.getName(),
                        z.getZoneType(),
                        z.getZoneType().isWalkable(),
                        z.getZoneType().isHatched(),
                        z.getColorHex(),
                        z.getZoneType().getStrokeColor(),
                        z.getPolygonWkt(),
                        geometryUtil.extractPolygonShapes(geom),
                        geometryUtil.interiorPoint(geom)
                ));
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<SimulationDtos.SnapElement> snapElements = new ArrayList<>(elements.size());
        for (InfrastructureElement el : elements) {
            InfrastructureType type = el.getElementType();
            Polygon footprint = parseOrBuildFootprint(el);
            env.expandToInclude(footprint.getEnvelopeInternal());
            snapElements.add(new SimulationDtos.SnapElement(
                    el.getId(),
                    el.getElementUid(),
                    type,
                    el.getLabel(),
                    el.getCenterX(),
                    el.getCenterY(),
                    el.getWidthM(),
                    el.getHeightM(),
                    el.getRotationDeg(),
                    el.getThroughputPersonsPerMin(),
                    el.isActive(),
                    type.getFillColor(),
                    type.getStrokeColor(),
                    geometryUtil.toWkt(footprint),
                    geometryUtil.extractOuterRing(footprint)
            ));
        }

        List<SimulationDtos.SnapNode> snapNodes = new ArrayList<>(nodes.size());
        for (PathwayNode n : nodes) {
            env.expandToInclude(n.getX(), n.getY());
            snapNodes.add(new SimulationDtos.SnapNode(
                    n.getId(),
                    n.getNodeUid(),
                    n.getNodeType(),
                    n.getLabel(),
                    n.getX(),
                    n.getY(),
                    n.getLinkedZone() != null ? n.getLinkedZone().getId() : null,
                    n.getLinkedInfra() != null ? n.getLinkedInfra().getId() : null,
                    n.getNodeType().getFillColor(),
                    n.getNodeType().getStrokeColor()
            ));
        }

        List<SimulationDtos.SnapEdge> snapEdges = new ArrayList<>(edges.size());
        for (PathwayEdge e : edges) {
            List<GeometryUtil.Point2D> pts = extractEdgePoints(e);
            for (GeometryUtil.Point2D pt : pts) {
                env.expandToInclude(pt.x(), pt.y());
            }
            snapEdges.add(new SimulationDtos.SnapEdge(
                    e.getId(),
                    e.getEdgeUid(),
                    e.getFromNode().getNodeUid(),
                    e.getToNode().getNodeUid(),
                    e.getLabel(),
                    e.getNetworkLayer(),
                    e.getWidthM(),
                    e.getLengthM(),
                    e.getDirection(),
                    e.isActive(),
                    e.getNetworkLayer().getColorHex(),
                    e.getNetworkLayer().isDashed(),
                    e.getPolylineWkt(),
                    pts
            ));
        }

        List<SimulationDtos.SnapArrivalWave> snapWaves = waves.stream()
                .map(w -> new SimulationDtos.SnapArrivalWave(
                        w.getStartMinute(),
                        w.getEndMinute(),
                        w.getExpectedArrivals(),
                        w.getTargetGate() != null ? w.getTargetGate().getId() : null,
                        w.getLabel()
                ))
                .toList();

        List<SimulationDtos.SnapEmergencyAsset> snapAssets = assets.stream()
                .map(a -> new SimulationDtos.SnapEmergencyAsset(
                        a.getId(), a.getAssetType(), a.getLabel(), a.getGeometryWkt(), a.getMinWidthM()))
                .toList();

        GeometryUtil.Bounds bounds = !env.isNull()
                ? new GeometryUtil.Bounds(
                geometryUtil.round2(env.getMinX()),
                geometryUtil.round2(env.getMinY()),
                geometryUtil.round2(env.getMaxX()),
                geometryUtil.round2(env.getMaxY()))
                : new GeometryUtil.Bounds(0.0, 0.0, 100.0, 80.0);

        boolean hasBlueprint = plan != null && plan.getBlueprintStoredFilename() != null;
        String blueprintUrl = hasBlueprint ? "/api/events/" + eventId + "/venue/blueprint" : null;

        return new SimulationDtos.SerializedLayout(
                event.getName(),
                event.getExpectedAttendance(),
                plan != null ? plan.getWorkspaceMode().name() : "INTERACTIVE_MAP",
                plan != null ? plan.getSampleMapKey() : null,
                hasBlueprint,
                blueprintUrl,
                plan != null ? plan.getBlueprintWidthPx() : null,
                plan != null ? plan.getBlueprintHeightPx() : null,
                plan != null ? plan.getPixelsPerMeter() : null,
                boundaryWkt,
                boundaryPoints,
                bounds,
                List.copyOf(snapExclusions),
                List.copyOf(snapZones),
                List.copyOf(snapElements),
                List.copyOf(snapNodes),
                List.copyOf(snapEdges),
                snapWaves,
                snapAssets
        );
    }

    private Readiness evaluateReadiness(SimulationDtos.SerializedLayout layout) {
        if (layout == null || layout.nodes() == null || layout.edges() == null) {
            return new Readiness(false, "Draw pedestrian pathways in Venue Plan -> Pathways before running crowd simulations.");
        }
        long entries = layout.nodes().stream().filter(n -> n.nodeType() == PathwayNodeType.ENTRY_GATE).count();
        long exits = layout.nodes().stream()
                .filter(n -> n.nodeType() == PathwayNodeType.EXIT_GATE || n.nodeType() == PathwayNodeType.EMERGENCY_EXIT)
                .count();
        long activeEdges = layout.edges().stream().filter(SimulationDtos.SnapEdge::active).count();

        if (entries == 0 || exits == 0 || activeEdges == 0) {
            return new Readiness(false,
                    "Add at least 1 Entry Gate node, 1 Exit/Emergency Exit node, and 1 active Pathway Edge in Venue Plan -> Pathways to run simulations.");
        }
        return new Readiness(true, String.format(Locale.US,
                "%d entry gates, %d exits, %d active corridors ready for deterministic simulation.",
                entries, exits, activeEdges));
    }

    private SimulationDtos.LayoutSnapshotSummaryDto toSnapshotSummary(Long eventId, LayoutSnapshot snap) {
        SimulationDtos.SerializedLayout layout = deserializeLayout(snap.getSerializedLayoutJson());
        return new SimulationDtos.LayoutSnapshotSummaryDto(
                snap.getId(),
                snap.getVersionNumber(),
                snap.getLabel(),
                snap.getNotes(),
                "/api/events/" + eventId + "/simulation/snapshots/" + snap.getId() + "/thumbnail",
                layout.nodes() != null ? layout.nodes().size() : 0,
                layout.edges() != null ? layout.edges().size() : 0,
                layout.elements() != null ? layout.elements().size() : 0,
                layout.zones() != null ? layout.zones().size() : 0,
                snap.getCreatedBy() != null ? snap.getCreatedBy().getFullName() : "System",
                snap.getAudit().getCreatedAt()
        );
    }

    private SimulationDtos.SimulationRunSummaryDto toRunSummary(Long eventId, SimulationRun run) {
        SimulationConfig cfg = deserializeConfig(run.getConfigJson());
        SimulationDtos.SimulationMetricsSummary metrics = deserializeMetrics(run.getMetricsSummaryJson());
        return new SimulationDtos.SimulationRunSummaryDto(
                run.getId(),
                run.getSnapshot().getId(),
                run.getSnapshot().getVersionNumber(),
                run.getSnapshot().getLabel(),
                run.getLabel(),
                run.getScenarioType(),
                run.getScenarioType().getDisplayName(),
                run.getRandomSeed(),
                run.getDurationSeconds(),
                run.getAgentCount(),
                cfg,
                metrics,
                framesUrl(eventId, run.getId()),
                run.getCreatedBy() != null ? run.getCreatedBy().getFullName() : "System",
                run.getAudit().getCreatedAt()
        );
    }

    private String framesUrl(Long eventId, Long runId) {
        return "/api/events/" + eventId + "/simulation/runs/" + runId + "/frames";
    }

    private Polygon parseOrBuildFootprint(InfrastructureElement el) {
        if (el.getFootprintWkt() != null && !el.getFootprintWkt().isBlank()) {
            try {
                return geometryUtil.parsePolygon(el.getFootprintWkt());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return geometryUtil.buildRotatedRectangle(
                el.getCenterX(), el.getCenterY(), el.getWidthM(), el.getHeightM(), el.getRotationDeg());
    }

    private List<GeometryUtil.Point2D> extractEdgePoints(PathwayEdge edge) {
        if (edge.getPolylineWkt() != null && !edge.getPolylineWkt().isBlank()) {
            try {
                LineString line = geometryUtil.parseLineString(edge.getPolylineWkt());
                List<GeometryUtil.Point2D> pts = geometryUtil.extractLinePoints(line);
                if (pts.size() >= 2) {
                    return pts;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return List.of(
                new GeometryUtil.Point2D(edge.getFromNode().getX(), edge.getFromNode().getY()),
                new GeometryUtil.Point2D(edge.getToNode().getX(), edge.getToNode().getY())
        );
    }

    private AppUser resolveUser(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    private String serializeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize simulation JSON.", ex);
        }
    }

    private SimulationDtos.SerializedLayout deserializeLayout(String json) {
        try {
            return objectMapper.readValue(json, SimulationDtos.SerializedLayout.class);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to parse snapshot layout.", ex);
        }
    }

    private SimulationConfig deserializeConfig(String json) {
        try {
            return objectMapper.readValue(json, SimulationConfig.class);
        } catch (JsonProcessingException ex) {
            return SimulationConfig.defaults();
        }
    }

    private SimulationDtos.SimulationMetricsSummary deserializeMetrics(String json) {
        try {
            return objectMapper.readValue(json, SimulationDtos.SimulationMetricsSummary.class);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to parse simulation metrics.", ex);
        }
    }

    private String trimToNull(String val) {
        if (val == null) return null;
        String trimmed = val.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String val, int maxLen) {
        if (val == null) return null;
        return val.length() > maxLen ? val.substring(0, maxLen) : val;
    }

    private record Readiness(boolean ready, String message) {
    }
}
