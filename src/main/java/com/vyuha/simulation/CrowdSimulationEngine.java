package com.vyuha.simulation;

import com.vyuha.common.GeometryUtil;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.NetworkLayer;
import com.vyuha.network.PathwayDirection;
import com.vyuha.network.PathwayNodeType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Service;

/**
 * Pure-Java deterministic agent-based crowd simulation engine.
 * Reads real venue boundaries, exclusions, functional zones, infrastructure obstacles/throughputs,
 * and pedestrian movement networks, computing JTS-verified effective corridor widths,
 * Weidmann-inspired density-dependent walking speeds, gate/checkpoint service queues,
 * congestion-weighted Dijkstra rerouting, and reproducible playback frames.
 */
@Service
public class CrowdSimulationEngine {

    private static final double DT_SECONDS = 0.5;
    private static final double MAX_EDGE_JAM_DENSITY_PSM = 4.6;
    private static final double JAM_CREEP_SPEED_MPS = 0.05;
    private static final double CONTRA_FLOW_FACTOR = 0.78;
    private static final double SPECIFIC_FLOW_PERSONS_PER_M_SEC = 1.15;

    private static final int VIS_WALKING = 0;
    private static final int VIS_QUEUING = 1;
    private static final int VIS_CONGESTED = 2;
    private static final int VIS_EXITING = 3;
    private static final int VIS_BLOCKED = 4;

    private enum AgentPhase {
        NOT_SPAWNED,
        ENTRY_QUEUE,
        WALKING_IN,
        CHECKPOINT_QUEUE,
        DWELLING,
        EXITING,
        EXIT_QUEUE,
        EXITED,
        BLOCKED
    }

    public record SimulationExecutionResult(
            SimulationDtos.SimulationMetricsSummary metrics,
            SimulationDtos.SimulationFrameStream frameStream
    ) {
    }

    private final GeometryUtil geometryUtil;

    public CrowdSimulationEngine(GeometryUtil geometryUtil) {
        this.geometryUtil = geometryUtil;
    }

    public SimulationExecutionResult simulate(SimulationDtos.SerializedLayout layout, SimulationConfig rawConfig) {
        if (layout == null) {
            throw new IllegalArgumentException("Layout snapshot data is required to run crowd simulation.");
        }
        SimulationConfig config = rawConfig != null ? rawConfig : SimulationConfig.defaults();

        List<SimulationDtos.SnapNode> rawNodes = sortNodes(layout.nodes());
        List<SimulationDtos.SnapEdge> rawEdges = sortEdges(layout.edges());
        List<SimulationDtos.SnapZone> rawZones = sortZones(layout.zones());
        List<SimulationDtos.SnapElement> rawElements = sortElements(layout.elements());

        if (rawNodes.isEmpty() || rawEdges.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least two connected pathway nodes and one active pathway edge are required to run crowd simulation.");
        }

        // Build deterministic node index
        int nodeCount = rawNodes.size();
        SimNode[] nodes = new SimNode[nodeCount];
        Map<String, Integer> nodeIdxByUid = new HashMap<>();
        Map<Long, Integer> nodeIdxById = new HashMap<>();
        Map<Long, Integer> nodeIdxByInfraId = new HashMap<>();
        Map<Long, Integer> nodeIdxByZoneId = new HashMap<>();

        Map<Long, SimulationDtos.SnapElement> elemById = new HashMap<>();
        for (SimulationDtos.SnapElement el : rawElements) {
            if (el.id() != null) {
                elemById.put(el.id(), el);
            }
        }
        Map<Long, SimulationDtos.SnapZone> zoneById = new HashMap<>();
        for (SimulationDtos.SnapZone z : rawZones) {
            if (z.id() != null) {
                zoneById.put(z.id(), z);
            }
        }

        List<Integer> entryNodeIndices = new ArrayList<>();
        List<Integer> exitNodeIndices = new ArrayList<>();
        List<Integer> zoneHubIndices = new ArrayList<>();

        for (int i = 0; i < nodeCount; i++) {
            SimulationDtos.SnapNode sn = rawNodes.get(i);
            SimulationDtos.SnapElement linkedElem = sn.linkedInfraId() != null ? elemById.get(sn.linkedInfraId()) : null;
            SimulationDtos.SnapZone linkedZone = sn.linkedZoneId() != null ? zoneById.get(sn.linkedZoneId()) : null;
            double ratePerSec = resolveNodeServiceRatePerSec(sn.nodeType(), linkedElem);
            nodes[i] = new SimNode(i, sn, linkedElem, linkedZone, ratePerSec);

            if (sn.nodeUid() != null) {
                nodeIdxByUid.put(sn.nodeUid(), i);
            }
            if (sn.id() != null) {
                nodeIdxById.put(sn.id(), i);
            }
            if (sn.linkedInfraId() != null) {
                nodeIdxByInfraId.putIfAbsent(sn.linkedInfraId(), i);
            }
            if (sn.linkedZoneId() != null) {
                nodeIdxByZoneId.putIfAbsent(sn.linkedZoneId(), i);
            }

            if (sn.nodeType() == PathwayNodeType.ENTRY_GATE) {
                entryNodeIndices.add(i);
            } else if (sn.nodeType() == PathwayNodeType.EXIT_GATE || sn.nodeType() == PathwayNodeType.EMERGENCY_EXIT) {
                exitNodeIndices.add(i);
            } else if (sn.nodeType() == PathwayNodeType.ZONE_HUB) {
                zoneHubIndices.add(i);
            }
        }

        if (entryNodeIndices.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one Entry Gate pathway node is required to run crowd simulation.");
        }
        if (exitNodeIndices.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one Exit Gate or Emergency Exit pathway node is required to run crowd simulation.");
        }

        // Parse boundary & rigid non-portal obstacles for JTS effective width and grid walkable area
        Polygon boundaryPoly = parseBoundaryPolygon(layout.boundaryWkt());
        Geometry obstacleUnion = buildObstacleUnion(rawElements, layout.exclusions());

        // Build active edges and compute JTS obstacle-adjusted effective widths
        List<SimEdge> edgeList = new ArrayList<>();
        Map<String, Integer> edgeIdxByUid = new HashMap<>();
        Map<Long, Integer> edgeIdxById = new HashMap<>();
        List<List<AdjEdge>> adj = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            adj.add(new ArrayList<>());
        }

        for (SimulationDtos.SnapEdge se : rawEdges) {
            if (!se.active()) {
                continue;
            }
            Integer fromIdx = se.fromNodeUid() != null ? nodeIdxByUid.get(se.fromNodeUid()) : null;
            Integer toIdx = se.toNodeUid() != null ? nodeIdxByUid.get(se.toNodeUid()) : null;
            if (fromIdx == null || toIdx == null || fromIdx.equals(toIdx)) {
                continue;
            }
            LineString line = buildEdgeLine(se, nodes[fromIdx], nodes[toIdx]);
            double lengthM = Math.max(0.5, line.getLength());
            double nominalW = Math.max(0.3, se.widthM());
            double effectiveW = computeEffectiveCorridorWidth(line, nominalW, obstacleUnion);
            double flowWidth = Math.max(0.35, effectiveW - (effectiveW < 2.4 ? 0.35 : 0.25));
            double maxFlowPerSec = SPECIFIC_FLOW_PERSONS_PER_M_SEC * flowWidth;

            int numSegs = Math.max(1, (int) Math.round(lengthM / 3.0));
            double segLen = lengthM / numSegs;
            double segArea = Math.max(0.85, segLen * effectiveW);

            int edgeIdx = edgeList.size();
            SimEdge simEdge = new SimEdge(
                    edgeIdx, se, fromIdx, toIdx, line, lengthM,
                    nominalW, effectiveW, maxFlowPerSec, numSegs, segLen, segArea);
            edgeList.add(simEdge);
            if (se.edgeUid() != null) {
                edgeIdxByUid.put(se.edgeUid(), edgeIdx);
            }
            if (se.id() != null) {
                edgeIdxById.put(se.id(), edgeIdx);
            }

            adj.get(fromIdx).add(new AdjEdge(edgeIdx, toIdx, false));
            if (se.direction() != PathwayDirection.ONE_WAY) {
                adj.get(toIdx).add(new AdjEdge(edgeIdx, fromIdx, true));
            }
        }

        if (edgeList.isEmpty()) {
            throw new IllegalArgumentException("At least one active pathway edge is required to run crowd simulation.");
        }
        SimEdge[] edges = edgeList.toArray(new SimEdge[0]);

        // Resolve scenario disruption target
        int blockedEdgeIdx = resolveBlockedEdgeIndex(config, edges, edgeIdxByUid, edgeIdxById);
        int closedExitNodeIdx = resolveClosedExitNodeIndex(config, nodes, exitNodeIndices, nodeIdxByUid, nodeIdxByInfraId);
        String disruptionNote = buildDisruptionNote(config, edges, nodes, blockedEdgeIdx, closedExitNodeIdx);

        // Build spatial density grid
        DensityGrid grid = buildDensityGrid(layout, nodes, rawZones, rawElements, boundaryPoly, obstacleUnion, config.gridCellSizeM());

        // Precompute zone polygon geometries for dispersion points
        Map<Integer, Geometry> hubZoneGeom = new HashMap<>();
        for (int hubIdx : zoneHubIndices) {
            SimulationDtos.SnapZone z = nodes[hubIdx].linkedZone;
            if (z != null && z.polygonWkt() != null && !z.polygonWkt().isBlank()) {
                try {
                    hubZoneGeom.put(hubIdx, geometryUtil.parsePolygonal(z.polygonWkt()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // Schedule deterministic agents
        Random rng = new Random(config.randomSeed());
        SimAgent[] agents = scheduleAgents(
                config, layout, nodes, rawElements, entryNodeIndices, zoneHubIndices, hubZoneGeom, obstacleUnion, rng);

        // Simulation state arrays
        double[] entryTokens = new double[nodeCount];
        double[] cpTokens = new double[nodeCount];
        double[] exitTokens = new double[nodeCount];
        double[] edgeTokens = new double[edges.length];
        for (int i = 0; i < nodeCount; i++) {
            entryTokens[i] = 1.0;
            cpTokens[i] = 1.0;
            exitTokens[i] = 1.0;
        }
        for (int e = 0; e < edges.length; e++) {
            edgeTokens[e] = 1.0;
        }

        int[] cellCounts = new int[grid.cols * grid.rows];
        double[] cellDensities = new double[grid.cols * grid.rows];
        int[] entryQueueRank = new int[nodeCount];

        int totalTicks = Math.max(1, (int) Math.round(config.durationSeconds() / DT_SECONDS));
        double sampleIntervalSec = config.durationSeconds() <= 600 ? 1.0 : 2.0;
        int frameStepTicks = Math.max(1, (int) Math.round(sampleIntervalSec / DT_SECONDS));
        int tsStepTicks = Math.max(1, (int) Math.round(5.0 / DT_SECONDS));

        List<SimulationDtos.SimulationFrame> frames = new ArrayList<>();
        List<SimulationDtos.TimeSeriesSample> timeSeries = new ArrayList<>();

        double peakLocalDensityPsm = 0.0;
        Double peakDensityLocX = null;
        Double peakDensityLocY = null;
        double peakDensityTimeSec = 0.0;
        String peakDensityLabel = "Venue";

        int peakQueueLength = 0;
        double peakQueueTimeSec = 0.0;
        double bottleneckCongestionDurationSec = 0.0;
        boolean disruptionApplied = false;

        for (int tick = 0; tick <= totalTicks; tick++) {
            double simTime = round2(tick * DT_SECONDS);

            // Apply disruption once t >= disruptionStartSec
            if (!disruptionApplied && simTime >= config.disruptionStartSec()) {
                if (config.scenarioType() == SimulationScenarioType.BLOCKED_CORRIDOR && blockedEdgeIdx >= 0) {
                    edges[blockedEdgeIdx].runtimeActive = false;
                }
                if (config.scenarioType() == SimulationScenarioType.CLOSED_EXIT && closedExitNodeIdx >= 0) {
                    nodes[closedExitNodeIdx].runtimeClosed = true;
                }
                disruptionApplied = true;
            }

            // 1. Recompute local edge-segment and spatial grid cell densities
            Arrays.fill(cellCounts, 0);
            Arrays.fill(cellDensities, 0.0);
            for (SimEdge e : edges) {
                Arrays.fill(e.segFwdCount, 0);
                Arrays.fill(e.segRevCount, 0);
                Arrays.fill(e.segDensity, 0.0);
            }

            for (SimAgent a : agents) {
                if (a.phase == AgentPhase.NOT_SPAWNED || a.phase == AgentPhase.EXITED) {
                    continue;
                }
                if (a.onEdge >= 0) {
                    SimEdge e = edges[a.onEdge];
                    int seg = e.segmentForProgress(a.edgeProgressM);
                    if (a.edgeReversed) {
                        e.segRevCount[seg]++;
                    } else {
                        e.segFwdCount[seg]++;
                    }
                }
                int cIdx = grid.cellIndex(a.x, a.y);
                if (cIdx >= 0) {
                    cellCounts[cIdx]++;
                }
            }

            double tickMaxDensity = 0.0;
            double tickMaxX = nodes[entryNodeIndices.get(0)].x;
            double tickMaxY = nodes[entryNodeIndices.get(0)].y;
            String tickMaxLabel = "Venue";

            for (int c = 0; c < cellCounts.length; c++) {
                double d = cellCounts[c] / grid.walkableAreaSqm[c];
                cellDensities[c] = d;
                if (d > tickMaxDensity) {
                    tickMaxDensity = d;
                    tickMaxX = grid.cellCenterX(c);
                    tickMaxY = grid.cellCenterY(c);
                    tickMaxLabel = resolveNearestLabel(tickMaxX, tickMaxY, rawZones, nodes);
                }
            }

            for (SimEdge e : edges) {
                double edgeMaxD = 0.0;
                for (int s = 0; s < e.numSegs; s++) {
                    int totalInSeg = e.segFwdCount[s] + e.segRevCount[s];
                    double d = totalInSeg / e.segArea;
                    e.segDensity[s] = d;
                    if (d > edgeMaxD) {
                        edgeMaxD = d;
                    }
                    double segCenterProg = (s + 0.5) * e.segLen;
                    double[] xy = e.interpolatePoint(segCenterProg, false, 0.0);
                    int cIdx = grid.cellIndex(xy[0], xy[1]);
                    if (cIdx >= 0 && d > cellDensities[cIdx]) {
                        cellDensities[cIdx] = d;
                    }
                    if (d > tickMaxDensity) {
                        tickMaxDensity = d;
                        tickMaxX = xy[0];
                        tickMaxY = xy[1];
                        tickMaxLabel = e.label;
                    }
                }
                e.currentMaxDensity = edgeMaxD;
                if (edgeMaxD > e.peakObservedDensity) {
                    e.peakObservedDensity = edgeMaxD;
                }
                if (edgeMaxD >= config.highDensityThresholdPsm()) {
                    e.congestedDurationSec += DT_SECONDS;
                }
            }

            if (tickMaxDensity > peakLocalDensityPsm) {
                peakLocalDensityPsm = tickMaxDensity;
                peakDensityLocX = tickMaxX;
                peakDensityLocY = tickMaxY;
                peakDensityTimeSec = simTime;
                peakDensityLabel = tickMaxLabel;
            }
            if (tickMaxDensity >= config.highDensityThresholdPsm()) {
                bottleneckCongestionDurationSec += DT_SECONDS;
            }

            // 2. Replenish service & corridor admission tokens
            for (int i : entryNodeIndices) {
                double r = nodes[i].serviceRatePerSec * DT_SECONDS;
                entryTokens[i] = Math.min(Math.max(1.0, r * 1.5), entryTokens[i] + r);
            }
            for (int i = 0; i < nodeCount; i++) {
                if (nodes[i].nodeType == PathwayNodeType.SECURITY_CHECKPOINT) {
                    double r = nodes[i].serviceRatePerSec * DT_SECONDS;
                    cpTokens[i] = Math.min(Math.max(1.0, r * 1.5), cpTokens[i] + r);
                }
            }
            for (int i : exitNodeIndices) {
                double r = nodes[i].serviceRatePerSec * DT_SECONDS;
                exitTokens[i] = Math.min(Math.max(1.0, r * 1.5), exitTokens[i] + r);
            }
            for (int e = 0; e < edges.length; e++) {
                SimEdge edge = edges[e];
                if (!edge.runtimeActive) {
                    edgeTokens[e] = 0.0;
                    continue;
                }
                boolean contra = false;
                for (int s = 0; s < edge.numSegs; s++) {
                    if (edge.segFwdCount[s] > 0 && edge.segRevCount[s] > 0) {
                        contra = true;
                        break;
                    }
                }
                double r = edge.maxFlowPerSec * (contra ? CONTRA_FLOW_FACTOR : 1.0) * DT_SECONDS;
                edgeTokens[e] = Math.min(Math.max(1.0, r * 1.5), edgeTokens[e] + r);
            }

            // 3. Advance all agents in deterministic order
            Arrays.fill(entryQueueRank, 0);
            int activeCount = 0;
            int queuingCount = 0;
            int congestedCount = 0;
            int exitedCount = 0;

            List<double[]> frameAgents = (tick % frameStepTicks == 0) ? new ArrayList<>() : null;

            for (SimAgent a : agents) {
                if (a.phase == AgentPhase.EXITED) {
                    exitedCount++;
                    continue;
                }
                if (a.phase == AgentPhase.NOT_SPAWNED) {
                    if (simTime >= a.spawnTimeSec) {
                        a.phase = AgentPhase.ENTRY_QUEUE;
                        a.atNode = a.entryNodeIdx;
                        SimNode gn = nodes[a.entryNodeIdx];
                        a.x = gn.x;
                        a.y = gn.y;
                    } else {
                        continue;
                    }
                }

                activeCount++;
                int visState = stepAgent(
                        a, simTime, config, nodes, edges, adj,
                        entryTokens, cpTokens, exitTokens, edgeTokens,
                        cellDensities, grid, entryQueueRank);

                if (a.phase == AgentPhase.EXITED) {
                    activeCount--;
                    exitedCount++;
                    continue;
                }

                if (visState == VIS_QUEUING) {
                    queuingCount++;
                } else if (visState == VIS_CONGESTED) {
                    congestedCount++;
                }

                if (frameAgents != null) {
                    frameAgents.add(new double[]{a.id, round1(a.x), round1(a.y), visState});
                }
            }

            int totalQueuedOrCongested = queuingCount + congestedCount;
            if (totalQueuedOrCongested > peakQueueLength) {
                peakQueueLength = totalQueuedOrCongested;
                peakQueueTimeSec = simTime;
            }

            if (frameAgents != null) {
                List<double[]> hotCells = new ArrayList<>();
                for (int r = 0; r < grid.rows; r++) {
                    for (int c = 0; c < grid.cols; c++) {
                        double d = cellDensities[r * grid.cols + c];
                        if (d >= 0.08) {
                            hotCells.add(new double[]{c, r, round2(d)});
                        }
                    }
                }
                frames.add(new SimulationDtos.SimulationFrame(
                        simTime, activeCount, queuingCount, congestedCount, exitedCount,
                        round2(tickMaxDensity), frameAgents, hotCells));
            }

            if (tick % tsStepTicks == 0 || tick == totalTicks) {
                timeSeries.add(new SimulationDtos.TimeSeriesSample(
                        simTime, activeCount, queuingCount, congestedCount, exitedCount, round2(tickMaxDensity)));
            }
        }

        // 4. Aggregate final deterministic metrics
        int totalSpawned = 0;
        int totalExited = 0;
        int blockedNoRoute = 0;
        int unservedAtEnd = 0;
        double maxWait = 0.0;
        double sumWait = 0.0;
        List<Double> clearanceTimes = new ArrayList<>();

        for (SimAgent a : agents) {
            if (a.phase != AgentPhase.NOT_SPAWNED) {
                totalSpawned++;
                sumWait += a.waitTimeSec;
                if (a.waitTimeSec > maxWait) {
                    maxWait = a.waitTimeSec;
                }
            }
            if (a.phase == AgentPhase.EXITED) {
                totalExited++;
                clearanceTimes.add(Math.max(0.5, a.exitTimeSec - a.spawnTimeSec));
            } else if (a.phase == AgentPhase.BLOCKED) {
                blockedNoRoute++;
                clearanceTimes.add(Math.max(0.5, config.durationSeconds() - a.spawnTimeSec));
            } else if (a.phase != AgentPhase.NOT_SPAWNED) {
                unservedAtEnd++;
                // Include right-censored elapsed time so layouts leaving crowds stranded are not biased downward
                clearanceTimes.add(Math.max(0.5, config.durationSeconds() - a.spawnTimeSec));
            }
        }

        double avgClearance;
        double p95Clearance;
        if (!clearanceTimes.isEmpty()) {
            Collections.sort(clearanceTimes);
            double sumClear = 0.0;
            for (double ct : clearanceTimes) {
                sumClear += ct;
            }
            avgClearance = sumClear / clearanceTimes.size();
            int p95Idx = Math.min(clearanceTimes.size() - 1, (int) Math.ceil(0.95 * clearanceTimes.size()) - 1);
            p95Clearance = clearanceTimes.get(Math.max(0, p95Idx));
        } else {
            double fallbackElapsed = Math.max(0.0, config.durationSeconds());
            avgClearance = fallbackElapsed;
            p95Clearance = fallbackElapsed;
        }

        double avgWait = totalSpawned > 0 ? sumWait / totalSpawned : 0.0;
        int unservedBlockedTotal = blockedNoRoute + unservedAtEnd;
        double clearancePct = agents.length > 0 ? (totalExited * 100.0) / agents.length : 0.0;

        List<SimulationDtos.HotspotSummary> hotspots = Arrays.stream(edges)
                .filter(e -> e.peakObservedDensity > 0.01)
                .sorted(Comparator.comparingDouble((SimEdge e) -> e.peakObservedDensity).reversed()
                        .thenComparing(e -> e.label))
                .limit(5)
                .map(e -> {
                    double[] mid = e.interpolatePoint(e.lengthM * 0.5, false, 0.0);
                    return new SimulationDtos.HotspotSummary(
                            e.label,
                            round2(mid[0]),
                            round2(mid[1]),
                            round2(e.peakObservedDensity),
                            round2(e.congestedDurationSec),
                            round2(e.effectiveWidthM),
                            round2(e.nominalWidthM)
                    );
                })
                .toList();

        SimulationDtos.SimulationMetricsSummary metrics = new SimulationDtos.SimulationMetricsSummary(
                agents.length,
                totalSpawned,
                totalExited,
                unservedBlockedTotal,
                blockedNoRoute,
                unservedAtEnd,
                round2(peakLocalDensityPsm),
                peakDensityLocX != null ? round2(peakDensityLocX) : null,
                peakDensityLocY != null ? round2(peakDensityLocY) : null,
                round2(peakDensityTimeSec),
                peakDensityLabel,
                peakQueueLength,
                round2(peakQueueTimeSec),
                round2(maxWait),
                round2(avgWait),
                round2(bottleneckCongestionDurationSec),
                config.highDensityThresholdPsm(),
                round2(avgClearance),
                round2(p95Clearance),
                round2(clearancePct),
                disruptionNote,
                hotspots,
                List.copyOf(timeSeries)
        );

        SimulationDtos.SimulationFrameStream frameStream = new SimulationDtos.SimulationFrameStream(
                round2(grid.originX),
                round2(grid.originY),
                round2(grid.cellSizeM),
                grid.cols,
                grid.rows,
                config.durationSeconds(),
                sampleIntervalSec,
                config.highDensityThresholdPsm(),
                List.copyOf(frames)
        );

        return new SimulationExecutionResult(metrics, frameStream);
    }

    private int stepAgent(SimAgent a,
                          double simTime,
                          SimulationConfig config,
                          SimNode[] nodes,
                          SimEdge[] edges,
                          List<List<AdjEdge>> adj,
                          double[] entryTokens,
                          double[] cpTokens,
                          double[] exitTokens,
                          double[] edgeTokens,
                          double[] cellDensities,
                          DensityGrid grid,
                          int[] entryQueueRank) {
        if (a.phase == AgentPhase.BLOCKED) {
            return VIS_BLOCKED;
        }

        if (a.phase == AgentPhase.ENTRY_QUEUE) {
            int gateIdx = a.entryNodeIdx;
            if (entryTokens[gateIdx] >= 1.0) {
                List<Integer> route = planInboundRoute(gateIdx, a.targetHubNodeIdx, nodes, edges, adj);
                if (route == null && a.targetHubNodeIdx >= 0) {
                    // Fallback directly toward exits if target hub has no inbound route
                    a.targetHubNodeIdx = -1;
                    route = planEgressRoute(gateIdx, nodes, edges, adj, false);
                }
                if (route == null) {
                    a.phase = AgentPhase.BLOCKED;
                    return VIS_BLOCKED;
                }
                if (route.size() == 1) {
                    entryTokens[gateIdx] -= 1.0;
                    if (a.targetHubNodeIdx >= 0) {
                        a.phase = AgentPhase.DWELLING;
                        a.dwellRemaining = a.dwellDurationSec;
                    } else {
                        a.phase = AgentPhase.EXIT_QUEUE;
                    }
                    return VIS_QUEUING;
                }
                int nextNode = route.get(1);
                int nextEdgeIdx = findBestEdge(gateIdx, nextNode, edges, adj);
                if (nextEdgeIdx >= 0 && edges[nextEdgeIdx].entrySegDensity(gateIdx) < MAX_EDGE_JAM_DENSITY_PSM) {
                    entryTokens[gateIdx] -= 1.0;
                    a.phase = a.targetHubNodeIdx >= 0 ? AgentPhase.WALKING_IN : AgentPhase.EXITING;
                    a.pathNodes = route;
                    a.pathPos = 0;
                    enterEdge(a, nextEdgeIdx, gateIdx, nextNode, edges[nextEdgeIdx]);
                    return a.phase == AgentPhase.EXITING ? VIS_EXITING : VIS_WALKING;
                }
            }
            // Arrange queue neatly behind gate
            int rank = entryQueueRank[gateIdx]++;
            SimNode gn = nodes[gateIdx];
            int row = rank / 3;
            int col = (rank % 3) - 1;
            a.x = gn.x + col * 0.70 + (a.lateralSeed - 0.5) * 0.15;
            a.y = gn.y - Math.min(24.0, 0.80 + row * 0.65);
            a.waitTimeSec += DT_SECONDS;
            return VIS_QUEUING;
        }

        if (a.phase == AgentPhase.CHECKPOINT_QUEUE) {
            int cpIdx = a.atNode;
            if (cpTokens[cpIdx] >= 1.0) {
                cpTokens[cpIdx] -= 1.0;
                a.clearedCheckpoint = true;
                a.phase = AgentPhase.WALKING_IN;
                // Proceed on existing path (or re-route from checkpoint)
                if (a.pathNodes == null || a.pathPos >= a.pathNodes.size() - 1) {
                    a.pathNodes = planInboundRoute(cpIdx, a.targetHubNodeIdx, nodes, edges, adj);
                    a.pathPos = 0;
                }
            } else {
                a.waitTimeSec += DT_SECONDS;
                return VIS_QUEUING;
            }
        }

        if (a.phase == AgentPhase.EXIT_QUEUE) {
            int exIdx = a.atNode;
            if (nodes[exIdx].runtimeClosed) {
                // Exit closed! Reroute immediately toward another open exit
                a.phase = AgentPhase.EXITING;
                a.pathNodes = planEgressRoute(exIdx, nodes, edges, adj, true);
                a.pathPos = 0;
                if (a.pathNodes == null) {
                    a.phase = AgentPhase.BLOCKED;
                    return VIS_BLOCKED;
                }
            } else if (exitTokens[exIdx] >= 1.0) {
                exitTokens[exIdx] -= 1.0;
                a.phase = AgentPhase.EXITED;
                a.exitTimeSec = simTime;
                return VIS_EXITING;
            } else {
                a.waitTimeSec += DT_SECONDS;
                return VIS_QUEUING;
            }
        }

        if (a.phase == AgentPhase.DWELLING) {
            a.dwellRemaining -= DT_SECONDS;
            double targetX = a.dwellRemaining > Math.min(4.0, a.dwellDurationSec * 0.25)
                    ? a.dwellX
                    : nodes[a.targetHubNodeIdx].x;
            double targetY = a.dwellRemaining > Math.min(4.0, a.dwellDurationSec * 0.25)
                    ? a.dwellY
                    : nodes[a.targetHubNodeIdx].y;
            double localDensity = grid.densityAt(a.x, a.y, cellDensities);
            double speed = weidmannSpeed(config.freeFlowSpeedMps(), localDensity);
            double dx = targetX - a.x;
            double dy = targetY - a.y;
            double dist = Math.hypot(dx, dy);
            if (dist > 0.15) {
                double step = Math.min(dist, speed * DT_SECONDS);
                a.x += (dx / dist) * step;
                a.y += (dy / dist) * step;
            }
            if (a.dwellRemaining <= 0.0) {
                a.x = nodes[a.targetHubNodeIdx].x;
                a.y = nodes[a.targetHubNodeIdx].y;
                a.atNode = a.targetHubNodeIdx;
                a.phase = AgentPhase.EXITING;
                a.pathNodes = planEgressRoute(a.atNode, nodes, edges, adj, false);
                a.pathPos = 0;
                if (a.pathNodes == null) {
                    a.phase = AgentPhase.BLOCKED;
                    return VIS_BLOCKED;
                }
            }
            return localDensity >= config.highDensityThresholdPsm() ? VIS_CONGESTED : VIS_WALKING;
        }

        // WALKING_IN or EXITING along edge or transitioning at a node
        if (a.onEdge >= 0) {
            SimEdge edge = edges[a.onEdge];
            if (!edge.runtimeActive) {
                // Corridor became disabled mid-run: return to start node and reroute
                a.onEdge = -1;
                a.atNode = a.edgeStartNodeIdx;
                a.x = nodes[a.atNode].x;
                a.y = nodes[a.atNode].y;
                a.pathNodes = a.phase == AgentPhase.WALKING_IN
                        ? planInboundRoute(a.atNode, a.targetHubNodeIdx, nodes, edges, adj)
                        : planEgressRoute(a.atNode, nodes, edges, adj, true);
                a.pathPos = 0;
                if (a.pathNodes == null) {
                    a.phase = AgentPhase.BLOCKED;
                    return VIS_BLOCKED;
                }
                return VIS_QUEUING;
            }

            int seg = edge.segmentForProgress(a.edgeProgressM);
            double rho = edge.segDensity[seg];
            boolean contra = edge.segFwdCount[seg] > 0 && edge.segRevCount[seg] > 0;
            double speed = weidmannSpeed(config.freeFlowSpeedMps(), rho) * (contra ? CONTRA_FLOW_FACTOR : 1.0);

            double nextProg = a.edgeProgressM + speed * DT_SECONDS;
            int nextSeg = edge.segmentForProgress(Math.min(edge.lengthM, nextProg));
            boolean jammedAhead = nextSeg != seg && edge.segDensity[nextSeg] >= MAX_EDGE_JAM_DENSITY_PSM;
            if (jammedAhead) {
                speed = JAM_CREEP_SPEED_MPS;
                nextProg = a.edgeProgressM + speed * DT_SECONDS;
                a.waitTimeSec += DT_SECONDS;
            }

            if (nextProg >= edge.lengthM) {
                if (edgeTokens[edge.index] < 1.0) {
                    a.edgeProgressM = Math.max(a.edgeProgressM, edge.lengthM - 0.25);
                    double lateralOffset = (a.lateralSeed - 0.5) * Math.min(2.8, Math.max(0.25, edge.effectiveWidthM * 0.65));
                    double[] xy = edge.interpolatePoint(a.edgeProgressM, a.edgeReversed, lateralOffset);
                    a.x = xy[0];
                    a.y = xy[1];
                    a.waitTimeSec += DT_SECONDS;
                    return VIS_CONGESTED;
                }
                edgeTokens[edge.index] -= 1.0;
                int arrivedNode = a.edgeEndNodeIdx;
                a.onEdge = -1;
                a.atNode = arrivedNode;
                a.pathPos++;
                a.x = nodes[arrivedNode].x;
                a.y = nodes[arrivedNode].y;

                if (a.phase == AgentPhase.WALKING_IN) {
                    if (nodes[arrivedNode].nodeType == PathwayNodeType.SECURITY_CHECKPOINT && !a.clearedCheckpoint) {
                        a.phase = AgentPhase.CHECKPOINT_QUEUE;
                        return VIS_QUEUING;
                    }
                    if (arrivedNode == a.targetHubNodeIdx) {
                        a.phase = AgentPhase.DWELLING;
                        a.dwellRemaining = a.dwellDurationSec;
                        return VIS_WALKING;
                    }
                } else if (a.phase == AgentPhase.EXITING) {
                    if (isExitNodeOpen(nodes[arrivedNode])) {
                        a.phase = AgentPhase.EXIT_QUEUE;
                        return VIS_QUEUING;
                    }
                }
                return (rho >= config.highDensityThresholdPsm() || jammedAhead)
                        ? VIS_CONGESTED
                        : (a.phase == AgentPhase.EXITING ? VIS_EXITING : VIS_WALKING);
            }

            a.edgeProgressM = nextProg;
            double lateralOffset = (a.lateralSeed - 0.5) * Math.min(2.8, Math.max(0.25, edge.effectiveWidthM * 0.65));
            double[] xy = edge.interpolatePoint(a.edgeProgressM, a.edgeReversed, lateralOffset);
            a.x = xy[0];
            a.y = xy[1];

            if (rho >= config.highDensityThresholdPsm() || jammedAhead || speed < config.freeFlowSpeedMps() * 0.35) {
                if (speed < config.freeFlowSpeedMps() * 0.35) {
                    a.waitTimeSec += DT_SECONDS * 0.5;
                }
                return VIS_CONGESTED;
            }
            return a.phase == AgentPhase.EXITING ? VIS_EXITING : VIS_WALKING;
        }

        // Currently standing at a node waiting to enter the next edge
        int currNode = a.atNode;
        if (currNode < 0) {
            a.phase = AgentPhase.BLOCKED;
            return VIS_BLOCKED;
        }

        if (a.phase == AgentPhase.EXITING && isExitNodeOpen(nodes[currNode])) {
            a.phase = AgentPhase.EXIT_QUEUE;
            return VIS_QUEUING;
        }

        boolean needsReroute = a.pathNodes == null
                || a.pathPos >= a.pathNodes.size() - 1
                || a.pathNodes.get(a.pathPos) != currNode;
        if (!needsReroute && a.phase == AgentPhase.EXITING) {
            int finalTarget = a.pathNodes.get(a.pathNodes.size() - 1);
            if (nodes[finalTarget].runtimeClosed) {
                needsReroute = true;
            }
        }
        int nextNode = !needsReroute ? a.pathNodes.get(a.pathPos + 1) : -1;
        int nextEdgeIdx = !needsReroute ? findBestEdge(currNode, nextNode, edges, adj) : -1;
        if (needsReroute || nextEdgeIdx < 0) {
            a.pathNodes = a.phase == AgentPhase.WALKING_IN
                    ? planInboundRoute(currNode, a.targetHubNodeIdx, nodes, edges, adj)
                    : planEgressRoute(currNode, nodes, edges, adj, true);
            a.pathPos = 0;
            if (a.pathNodes == null || a.pathNodes.size() < 2) {
                if (a.phase == AgentPhase.WALKING_IN && currNode == a.targetHubNodeIdx) {
                    a.phase = AgentPhase.DWELLING;
                    a.dwellRemaining = a.dwellDurationSec;
                    return VIS_WALKING;
                }
                a.phase = AgentPhase.BLOCKED;
                return VIS_BLOCKED;
            }
            nextNode = a.pathNodes.get(1);
            nextEdgeIdx = findBestEdge(currNode, nextNode, edges, adj);
        }

        if (nextEdgeIdx >= 0
                && edges[nextEdgeIdx].entrySegDensity(currNode) < MAX_EDGE_JAM_DENSITY_PSM) {
            enterEdge(a, nextEdgeIdx, currNode, nextNode, edges[nextEdgeIdx]);
            return a.phase == AgentPhase.EXITING ? VIS_EXITING : VIS_WALKING;
        }

        a.waitTimeSec += DT_SECONDS;
        double localD = grid.densityAt(a.x, a.y, cellDensities);
        return localD >= config.highDensityThresholdPsm() ? VIS_CONGESTED : VIS_QUEUING;
    }

    private void enterEdge(SimAgent a, int edgeIdx, int fromNodeIdx, int toNodeIdx, SimEdge edge) {
        a.onEdge = edgeIdx;
        a.edgeStartNodeIdx = fromNodeIdx;
        a.edgeEndNodeIdx = toNodeIdx;
        a.edgeReversed = (edge.fromIdx != fromNodeIdx);
        a.edgeProgressM = 0.0;
        a.atNode = -1;
    }

    private boolean isExitNodeOpen(SimNode node) {
        return !node.runtimeClosed
                && (node.nodeType == PathwayNodeType.EXIT_GATE || node.nodeType == PathwayNodeType.EMERGENCY_EXIT);
    }

    /**
     * Weidmann-inspired fundamental diagram speed-density relationship (meters/sec).
     */
    static double weidmannSpeed(double freeFlowSpeedMps, double densityPsm) {
        if (densityPsm <= 0.15) {
            return freeFlowSpeedMps;
        }
        if (densityPsm >= 5.25) {
            return JAM_CREEP_SPEED_MPS;
        }
        double exponent = -1.913 * ((1.0 / densityPsm) - (1.0 / 5.4));
        double factor = 1.0 - Math.exp(exponent);
        return Math.max(JAM_CREEP_SPEED_MPS, freeFlowSpeedMps * Math.max(0.0, Math.min(1.0, factor)));
    }

    private List<Integer> planInboundRoute(int startIdx,
                                           int targetIdx,
                                           SimNode[] nodes,
                                           SimEdge[] edges,
                                           List<List<AdjEdge>> adj) {
        if (targetIdx < 0) {
            return null;
        }
        if (startIdx == targetIdx) {
            return List.of(startIdx);
        }
        // Prefer non-emergency layers during normal inbound circulation
        List<Integer> route = dijkstra(startIdx, Set.of(targetIdx), nodes, edges, adj, false, false);
        if (route != null) {
            return route;
        }
        return dijkstra(startIdx, Set.of(targetIdx), nodes, edges, adj, true, false);
    }

    private List<Integer> planEgressRoute(int startIdx,
                                          SimNode[] nodes,
                                          SimEdge[] edges,
                                          List<List<AdjEdge>> adj,
                                          boolean emergencyFallbackUrgent) {
        Set<Integer> openExits = new HashSet<>();
        for (SimNode n : nodes) {
            if (isExitNodeOpen(n)) {
                openExits.add(n.index);
            }
        }
        if (openExits.isEmpty()) {
            return null;
        }
        if (openExits.contains(startIdx)) {
            return List.of(startIdx);
        }
        return dijkstra(startIdx, openExits, nodes, edges, adj, true, emergencyFallbackUrgent);
    }

    private List<Integer> dijkstra(int startIdx,
                                   Set<Integer> targets,
                                   SimNode[] nodes,
                                   SimEdge[] edges,
                                   List<List<AdjEdge>> adj,
                                   boolean allowEmergencyLayer,
                                   boolean equalEmergencyWeight) {
        int n = nodes.length;
        double[] dist = new double[n];
        int[] prev = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);

        record QNode(int idx, double d) {
        }
        PriorityQueue<QNode> pq = new PriorityQueue<>(
                Comparator.comparingDouble(QNode::d).thenComparingInt(QNode::idx));
        dist[startIdx] = 0.0;
        pq.add(new QNode(startIdx, 0.0));

        int reached = -1;
        while (!pq.isEmpty()) {
            QNode top = pq.poll();
            if (top.d > dist[top.idx] + 1e-9) {
                continue;
            }
            if (targets.contains(top.idx)) {
                reached = top.idx;
                break;
            }
            for (AdjEdge ae : adj.get(top.idx)) {
                SimEdge edge = edges[ae.edgeIdx];
                if (!edge.runtimeActive) {
                    continue;
                }
                if (!allowEmergencyLayer && edge.layer == NetworkLayer.EMERGENCY_ACCESS) {
                    continue;
                }
                double layerFactor = (!equalEmergencyWeight && edge.layer == NetworkLayer.EMERGENCY_ACCESS) ? 1.25 : 1.0;
                double congestionFactor = 1.0 + 0.65 * edge.currentMaxDensity;
                double widthPenalty = edge.effectiveWidthM < 2.4 ? (2.4 / Math.max(0.45, edge.effectiveWidthM)) * 0.45 : 1.0;
                double weight = edge.lengthM * layerFactor * congestionFactor * Math.max(1.0, widthPenalty);

                double cand = top.d + weight;
                if (cand < dist[ae.toNodeIdx] - 1e-9) {
                    dist[ae.toNodeIdx] = cand;
                    prev[ae.toNodeIdx] = top.idx;
                    pq.add(new QNode(ae.toNodeIdx, cand));
                }
            }
        }

        if (reached < 0) {
            return null;
        }
        List<Integer> path = new ArrayList<>();
        int cur = reached;
        while (cur >= 0) {
            path.add(cur);
            if (cur == startIdx) {
                break;
            }
            cur = prev[cur];
        }
        Collections.reverse(path);
        return path;
    }

    private int findBestEdge(int fromIdx, int toIdx, SimEdge[] edges, List<List<AdjEdge>> adj) {
        int bestIdx = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        for (AdjEdge ae : adj.get(fromIdx)) {
            if (ae.toNodeIdx == toIdx && edges[ae.edgeIdx].runtimeActive) {
                double score = edges[ae.edgeIdx].lengthM * (1.0 + edges[ae.edgeIdx].currentMaxDensity);
                if (score < bestScore) {
                    bestScore = score;
                    bestIdx = ae.edgeIdx;
                }
            }
        }
        return bestIdx;
    }

    private SimAgent[] scheduleAgents(SimulationConfig config,
                                      SimulationDtos.SerializedLayout layout,
                                      SimNode[] nodes,
                                      List<SimulationDtos.SnapElement> rawElements,
                                      List<Integer> entryNodeIndices,
                                      List<Integer> zoneHubIndices,
                                      Map<Integer, Geometry> hubZoneGeom,
                                      Geometry obstacleUnion,
                                      Random rng) {
        int count = config.agentCount();
        SimAgent[] agents = new SimAgent[count];

        double arrivalHorizonSec = switch (config.scenarioType()) {
            case ARRIVAL_RUSH -> Math.max(15.0, config.durationSeconds() * 0.28);
            case BLOCKED_CORRIDOR, CLOSED_EXIT -> Math.max(20.0, config.durationSeconds() * 0.42);
            case NORMAL_OPERATIONS, STAGE_CROWDING -> Math.max(25.0, config.durationSeconds() * 0.62);
        };

        // Compute entry gate selection weights from gate throughput (plus arrival wave weights if any)
        double[] entryWeights = new double[entryNodeIndices.size()];
        double totalWeight = 0.0;
        for (int i = 0; i < entryNodeIndices.size(); i++) {
            SimNode en = nodes[entryNodeIndices.get(i)];
            double w = Math.max(0.25, en.serviceRatePerSec);
            if (layout.arrivalWaves() != null) {
                for (SimulationDtos.SnapArrivalWave wave : layout.arrivalWaves()) {
                    if (wave.targetGateId() != null && en.linkedInfraId != null
                            && wave.targetGateId().equals(en.linkedInfraId)) {
                        w += Math.max(0.1, wave.expectedArrivals() / 200.0);
                    }
                }
            }
            entryWeights[i] = w;
            totalWeight += w;
        }

        // Identify stage-preferred zone hub for STAGE_CROWDING
        int stageHubIdx = resolveStagePreferredHub(nodes, rawElements, zoneHubIndices);

        // Precompute arrival waves CDF if normal ops and waves exist
        List<SimulationDtos.SnapArrivalWave> waves = layout.arrivalWaves() != null ? layout.arrivalWaves() : List.of();
        boolean useWaveCurve = !waves.isEmpty() && config.scenarioType() != SimulationScenarioType.ARRIVAL_RUSH;
        double waveMinStart = 0.0;
        double waveMaxEnd = 1.0;
        double totalWaveArrivals = 0.0;
        if (useWaveCurve) {
            waveMinStart = waves.stream().mapToInt(SimulationDtos.SnapArrivalWave::startMinute).min().orElse(0);
            waveMaxEnd = Math.max(waveMinStart + 1.0,
                    waves.stream().mapToInt(SimulationDtos.SnapArrivalWave::endMinute).max().orElse(1));
            totalWaveArrivals = waves.stream().mapToInt(w -> Math.max(0, w.expectedArrivals())).sum();
            if (totalWaveArrivals <= 0.0) {
                useWaveCurve = false;
            }
        }

        for (int i = 0; i < count; i++) {
            double spawnTime;
            if (config.scenarioType() == SimulationScenarioType.ARRIVAL_RUSH) {
                // Triangular rush concentrated tightly around the early surge window
                double u = (rng.nextDouble() + rng.nextDouble()) * 0.5;
                spawnTime = u * arrivalHorizonSec;
            } else if (useWaveCurve) {
                double pick = rng.nextDouble() * totalWaveArrivals;
                double acc = 0.0;
                SimulationDtos.SnapArrivalWave chosen = waves.get(waves.size() - 1);
                for (SimulationDtos.SnapArrivalWave w : waves) {
                    acc += Math.max(0, w.expectedArrivals());
                    if (pick <= acc) {
                        chosen = w;
                        break;
                    }
                }
                double m = chosen.startMinute() + rng.nextDouble() * Math.max(1, chosen.endMinute() - chosen.startMinute());
                double norm = (m - waveMinStart) / (waveMaxEnd - waveMinStart);
                spawnTime = Math.max(0.0, Math.min(arrivalHorizonSec, norm * arrivalHorizonSec));
            } else {
                spawnTime = rng.nextDouble() * arrivalHorizonSec;
            }

            // Pick entry node
            double rGate = rng.nextDouble() * totalWeight;
            double gAcc = 0.0;
            int chosenEntryIdx = entryNodeIndices.get(0);
            for (int k = 0; k < entryNodeIndices.size(); k++) {
                gAcc += entryWeights[k];
                if (rGate <= gAcc) {
                    chosenEntryIdx = entryNodeIndices.get(k);
                    break;
                }
            }

            // Pick target zone hub
            int targetHubIdx = -1;
            if (!zoneHubIndices.isEmpty()) {
                if (config.scenarioType() == SimulationScenarioType.STAGE_CROWDING && stageHubIdx >= 0) {
                    if (rng.nextDouble() < 0.85 || zoneHubIndices.size() == 1) {
                        targetHubIdx = stageHubIdx;
                    } else {
                        targetHubIdx = zoneHubIndices.get(rng.nextInt(zoneHubIndices.size()));
                    }
                } else {
                    targetHubIdx = zoneHubIndices.get(rng.nextInt(zoneHubIndices.size()));
                }
            }

            double dwellDuration = switch (config.scenarioType()) {
                case STAGE_CROWDING -> 16.0 + rng.nextDouble() * 18.0;
                case BLOCKED_CORRIDOR, CLOSED_EXIT -> 4.0 + rng.nextDouble() * 8.0;
                default -> 8.0 + rng.nextDouble() * 14.0;
            };

            double dwellX = targetHubIdx >= 0 ? nodes[targetHubIdx].x : 0.0;
            double dwellY = targetHubIdx >= 0 ? nodes[targetHubIdx].y : 0.0;
            if (targetHubIdx >= 0) {
                Geometry zGeom = hubZoneGeom.get(targetHubIdx);
                if (zGeom != null && !zGeom.isEmpty()) {
                    GeometryUtil.Bounds zb = geometryUtil.bounds(zGeom);
                    boolean placed = false;
                    if (zb != null) {
                        for (int attempt = 0; attempt < 10; attempt++) {
                            double cx = zb.minX() + rng.nextDouble() * Math.max(0.5, zb.maxX() - zb.minX());
                            double cy = zb.minY() + rng.nextDouble() * Math.max(0.5, zb.maxY() - zb.minY());
                            var pt = geometryUtil.buildPoint(cx, cy);
                            if (zGeom.covers(pt) && (obstacleUnion == null || obstacleUnion.isEmpty() || !obstacleUnion.covers(pt))) {
                                dwellX = cx;
                                dwellY = cy;
                                placed = true;
                                break;
                            }
                        }
                    }
                    if (!placed) {
                        dwellX = nodes[targetHubIdx].x + (rng.nextDouble() - 0.5) * 2.5;
                        dwellY = nodes[targetHubIdx].y + (rng.nextDouble() - 0.5) * 2.5;
                    }
                } else {
                    dwellX = nodes[targetHubIdx].x + (rng.nextDouble() - 0.5) * 3.0;
                    dwellY = nodes[targetHubIdx].y + (rng.nextDouble() - 0.5) * 3.0;
                }
            }

            agents[i] = new SimAgent(
                    i + 1,
                    round2(spawnTime),
                    chosenEntryIdx,
                    targetHubIdx,
                    dwellDuration,
                    dwellX,
                    dwellY,
                    rng.nextDouble()
            );
        }

        return agents;
    }

    private int resolveStagePreferredHub(SimNode[] nodes,
                                         List<SimulationDtos.SnapElement> elements,
                                         List<Integer> zoneHubIndices) {
        if (zoneHubIndices.isEmpty()) {
            return -1;
        }
        SimulationDtos.SnapElement stage = elements.stream()
                .filter(e -> e.active() && e.elementType() == InfrastructureType.STAGE)
                .findFirst()
                .orElse(null);
        if (stage != null) {
            int bestIdx = zoneHubIndices.get(0);
            double bestDist = Double.POSITIVE_INFINITY;
            for (int hubIdx : zoneHubIndices) {
                double d = Math.hypot(nodes[hubIdx].x - stage.centerX(), nodes[hubIdx].y - stage.centerY());
                // Prefer AUDIENCE zones slightly when equidistant
                if (nodes[hubIdx].linkedZone != null && nodes[hubIdx].linkedZone.zoneType().name().equals("AUDIENCE")) {
                    d *= 0.8;
                }
                if (d < bestDist) {
                    bestDist = d;
                    bestIdx = hubIdx;
                }
            }
            return bestIdx;
        }
        for (int hubIdx : zoneHubIndices) {
            if (nodes[hubIdx].linkedZone != null && nodes[hubIdx].linkedZone.zoneType().name().equals("AUDIENCE")) {
                return hubIdx;
            }
        }
        return zoneHubIndices.get(0);
    }

    private double resolveNodeServiceRatePerSec(PathwayNodeType type, SimulationDtos.SnapElement linkedElem) {
        double perMin;
        if (linkedElem != null && linkedElem.throughputPersonsPerMin() != null && linkedElem.throughputPersonsPerMin() > 0.0) {
            perMin = linkedElem.throughputPersonsPerMin();
        } else {
            perMin = switch (type) {
                case ENTRY_GATE -> 60.0;
                case SECURITY_CHECKPOINT -> 30.0;
                case EXIT_GATE -> 80.0;
                case EMERGENCY_EXIT -> 100.0;
                default -> 120.0;
            };
        }
        return Math.max(0.1, perMin / 60.0);
    }

    private Polygon parseBoundaryPolygon(String boundaryWkt) {
        if (boundaryWkt == null || boundaryWkt.isBlank()) {
            return null;
        }
        try {
            return geometryUtil.parsePolygon(boundaryWkt);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Geometry buildObstacleUnion(List<SimulationDtos.SnapElement> elements,
                                        List<SimulationDtos.SnapExclusion> exclusions) {
        List<Geometry> geoms = new ArrayList<>();
        if (elements != null) {
            for (SimulationDtos.SnapElement el : elements) {
                if (!el.active() || !el.elementType().isRigidStructure()) {
                    continue;
                }
                if (el.elementType().isSupportsThroughput() || el.elementType().isExitPortal()) {
                    // Portals (gates/checkpoints) allow flow rather than blocking corridors
                    continue;
                }
                try {
                    if (el.footprintWkt() != null && !el.footprintWkt().isBlank()) {
                        geoms.add(geometryUtil.parsePolygon(el.footprintWkt()));
                    } else {
                        geoms.add(geometryUtil.buildRotatedRectangle(
                                el.centerX(), el.centerY(), el.widthM(), el.heightM(), el.rotationDeg()));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (exclusions != null) {
            for (SimulationDtos.SnapExclusion ex : exclusions) {
                if (ex.corridor() || ex.polygonWkt() == null || ex.polygonWkt().isBlank()) {
                    continue;
                }
                try {
                    geoms.add(geometryUtil.parsePolygon(ex.polygonWkt()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return geometryUtil.unionPolygons(geoms);
    }

    private LineString buildEdgeLine(SimulationDtos.SnapEdge se, SimNode from, SimNode to) {
        if (se.polylineWkt() != null && !se.polylineWkt().isBlank()) {
            try {
                return geometryUtil.parseLineString(se.polylineWkt());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return geometryUtil.buildLineString(List.of(
                new GeometryUtil.Point2D(from.x, from.y),
                new GeometryUtil.Point2D(to.x, to.y)));
    }

    /**
     * Measures perpendicular cross-sections along a corridor polyline against JTS rigid obstacles
     * and non-corridor exclusions so intruding stalls/barricades physically pinch corridor width.
     */
    private double computeEffectiveCorridorWidth(LineString line, double nominalWidthM, Geometry obstacleUnion) {
        if (obstacleUnion == null || obstacleUnion.isEmpty()) {
            return nominalWidthM;
        }
        Geometry corridorPoly = geometryUtil.bufferLineFlat(line, nominalWidthM / 2.0);
        if (!corridorPoly.intersects(obstacleUnion)) {
            return nominalWidthM;
        }
        double totalLen = line.getLength();
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double startS = totalLen > 1.8 ? 0.75 : totalLen * 0.5;
        double endS = totalLen > 1.8 ? totalLen - 0.75 : totalLen * 0.5;

        double maxObstructed = 0.0;
        GeometryFactory gf = geometryUtil.getGeometryFactory();
        for (double s = startS; s <= endS + 1e-6; s += 1.0) {
            double clampedS = Math.min(totalLen, s);
            Coordinate p = indexed.extractPoint(clampedS);
            Coordinate pPrev = indexed.extractPoint(Math.max(0.0, clampedS - 0.25));
            Coordinate pNext = indexed.extractPoint(Math.min(totalLen, clampedS + 0.25));
            double tx = pNext.x - pPrev.x;
            double ty = pNext.y - pPrev.y;
            double tLen = Math.hypot(tx, ty);
            if (tLen < 1e-6) {
                continue;
            }
            double nx = -ty / tLen;
            double ny = tx / tLen;
            double halfW = nominalWidthM / 2.0;
            Coordinate c1 = new Coordinate(p.x - nx * halfW, p.y - ny * halfW);
            Coordinate c2 = new Coordinate(p.x + nx * halfW, p.y + ny * halfW);
            LineString transect = gf.createLineString(new Coordinate[]{c1, c2});
            double obstructed = transect.intersection(obstacleUnion).getLength();
            if (obstructed > maxObstructed) {
                maxObstructed = obstructed;
            }
        }
        return Math.max(0.45, round2(nominalWidthM - maxObstructed));
    }

    private DensityGrid buildDensityGrid(SimulationDtos.SerializedLayout layout,
                                         SimNode[] nodes,
                                         List<SimulationDtos.SnapZone> zones,
                                         List<SimulationDtos.SnapElement> elements,
                                         Polygon boundaryPoly,
                                         Geometry obstacleUnion,
                                         double requestedCellSizeM) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        if (layout.bounds() != null) {
            minX = Math.min(minX, layout.bounds().minX());
            minY = Math.min(minY, layout.bounds().minY());
            maxX = Math.max(maxX, layout.bounds().maxX());
            maxY = Math.max(maxY, layout.bounds().maxY());
        }
        for (SimNode n : nodes) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
            maxX = Math.max(maxX, n.x);
            maxY = Math.max(maxY, n.y);
        }
        for (SimulationDtos.SnapElement el : elements) {
            minX = Math.min(minX, el.centerX() - el.widthM());
            minY = Math.min(minY, el.centerY() - el.heightM());
            maxX = Math.max(maxX, el.centerX() + el.widthM());
            maxY = Math.max(maxY, el.centerY() + el.heightM());
        }
        if (!Double.isFinite(minX)) {
            minX = 0.0;
            minY = 0.0;
            maxX = 100.0;
            maxY = 80.0;
        }

        minX -= 4.0;
        minY -= 4.0;
        maxX += 4.0;
        maxY += 4.0;

        double spanX = Math.max(8.0, maxX - minX);
        double spanY = Math.max(8.0, maxY - minY);
        double cellSize = Math.max(2.0, requestedCellSizeM);
        if (spanX / cellSize > 80.0 || spanY / cellSize > 80.0) {
            cellSize = Math.max(cellSize, Math.ceil(Math.max(spanX, spanY) / 80.0));
        }

        int cols = Math.max(1, (int) Math.ceil(spanX / cellSize));
        int rows = Math.max(1, (int) Math.ceil(spanY / cellSize));
        double[] walkableAreaSqm = new double[cols * rows];
        double fullCellArea = cellSize * cellSize;
        double minFloorArea = Math.max(2.0, fullCellArea * 0.25);

        GeometryFactory gf = geometryUtil.getGeometryFactory();
        for (int r = 0; r < rows; r++) {
            double cy0 = minY + r * cellSize;
            double cy1 = cy0 + cellSize;
            for (int c = 0; c < cols; c++) {
                double cx0 = minX + c * cellSize;
                double cx1 = cx0 + cellSize;
                double area = fullCellArea;
                if (boundaryPoly != null && boundaryPoly.getEnvelopeInternal().intersects(new Envelope(cx0, cx1, cy0, cy1))) {
                    Coordinate[] boxCoords = new Coordinate[]{
                            new Coordinate(cx0, cy0),
                            new Coordinate(cx1, cy0),
                            new Coordinate(cx1, cy1),
                            new Coordinate(cx0, cy1),
                            new Coordinate(cx0, cy0)
                    };
                    Polygon cellBox = gf.createPolygon(boxCoords);
                    Geometry clipped = cellBox.intersection(boundaryPoly);
                    if (obstacleUnion != null && !obstacleUnion.isEmpty() && clipped.intersects(obstacleUnion)) {
                        clipped = clipped.difference(obstacleUnion);
                    }
                    double measured = geometryUtil.polygonalArea(clipped);
                    if (measured > 0.1) {
                        area = Math.max(minFloorArea, measured);
                    }
                }
                walkableAreaSqm[r * cols + c] = Math.max(minFloorArea, area);
            }
        }

        return new DensityGrid(minX, minY, cellSize, cols, rows, walkableAreaSqm);
    }

    private int resolveBlockedEdgeIndex(SimulationConfig config,
                                        SimEdge[] edges,
                                        Map<String, Integer> edgeIdxByUid,
                                        Map<Long, Integer> edgeIdxById) {
        if (config.scenarioType() != SimulationScenarioType.BLOCKED_CORRIDOR) {
            return -1;
        }
        if (config.targetEdgeUid() != null && edgeIdxByUid.containsKey(config.targetEdgeUid())) {
            return edgeIdxByUid.get(config.targetEdgeUid());
        }
        if (config.targetEdgeId() != null && edgeIdxById.containsKey(config.targetEdgeId())) {
            return edgeIdxById.get(config.targetEdgeId());
        }
        for (SimEdge e : edges) {
            if (e.layer == NetworkLayer.INTERNAL_CIRCULATION) {
                return e.index;
            }
        }
        int best = 0;
        for (int i = 1; i < edges.length; i++) {
            if (edges[i].lengthM > edges[best].lengthM) {
                best = i;
            }
        }
        return best;
    }

    private int resolveClosedExitNodeIndex(SimulationConfig config,
                                           SimNode[] nodes,
                                           List<Integer> exitNodeIndices,
                                           Map<String, Integer> nodeIdxByUid,
                                           Map<Long, Integer> nodeIdxByInfraId) {
        if (config.scenarioType() != SimulationScenarioType.CLOSED_EXIT) {
            return -1;
        }
        if (config.targetExitNodeUid() != null && nodeIdxByUid.containsKey(config.targetExitNodeUid())) {
            int idx = nodeIdxByUid.get(config.targetExitNodeUid());
            if (nodes[idx].nodeType == PathwayNodeType.EXIT_GATE || nodes[idx].nodeType == PathwayNodeType.EMERGENCY_EXIT) {
                return idx;
            }
        }
        if (config.targetExitInfraId() != null && nodeIdxByInfraId.containsKey(config.targetExitInfraId())) {
            int idx = nodeIdxByInfraId.get(config.targetExitInfraId());
            if (nodes[idx].nodeType == PathwayNodeType.EXIT_GATE || nodes[idx].nodeType == PathwayNodeType.EMERGENCY_EXIT) {
                return idx;
            }
        }
        for (int idx : exitNodeIndices) {
            if (nodes[idx].nodeType == PathwayNodeType.EXIT_GATE) {
                return idx;
            }
        }
        return exitNodeIndices.get(0);
    }

    private String buildDisruptionNote(SimulationConfig config,
                                       SimEdge[] edges,
                                       SimNode[] nodes,
                                       int blockedEdgeIdx,
                                       int closedExitNodeIdx) {
        return switch (config.scenarioType()) {
            case NORMAL_OPERATIONS -> "Baseline balanced arrivals, circulation, and egress.";
            case ARRIVAL_RUSH -> "Arrival surge compressed into peak arrival burst (~2.5x rate).";
            case STAGE_CROWDING -> "85% destination affinity directed toward stage-adjacent audience zone.";
            case BLOCKED_CORRIDOR -> blockedEdgeIdx >= 0
                    ? String.format(Locale.US, "Corridor \"%s\" blocked starting at t = %ds.",
                    edges[blockedEdgeIdx].label, config.disruptionStartSec())
                    : "No active corridor available to block.";
            case CLOSED_EXIT -> closedExitNodeIdx >= 0
                    ? String.format(Locale.US, "Exit \"%s\" closed starting at t = %ds.",
                    nodes[closedExitNodeIdx].label, config.disruptionStartSec())
                    : "No active exit available to close.";
        };
    }

    private String resolveNearestLabel(double x,
                                       double y,
                                       List<SimulationDtos.SnapZone> zones,
                                       SimNode[] nodes) {
        String bestLabel = "Venue";
        double bestDist = Double.POSITIVE_INFINITY;
        for (SimulationDtos.SnapZone z : zones) {
            if (z.labelPoint() != null) {
                double d = Math.hypot(x - z.labelPoint().x(), y - z.labelPoint().y());
                if (d < bestDist) {
                    bestDist = d;
                    bestLabel = z.zoneCode() + " · " + z.name();
                }
            }
        }
        for (SimNode n : nodes) {
            double d = Math.hypot(x - n.x, y - n.y);
            if (d < bestDist) {
                bestDist = d;
                bestLabel = n.label;
            }
        }
        return bestLabel;
    }

    private List<SimulationDtos.SnapNode> sortNodes(List<SimulationDtos.SnapNode> list) {
        if (list == null) return List.of();
        return list.stream()
                .sorted(Comparator.comparing((SimulationDtos.SnapNode n) -> n.nodeUid() != null ? n.nodeUid() : "")
                        .thenComparing(n -> n.id() != null ? n.id() : Long.MAX_VALUE))
                .toList();
    }

    private List<SimulationDtos.SnapEdge> sortEdges(List<SimulationDtos.SnapEdge> list) {
        if (list == null) return List.of();
        return list.stream()
                .sorted(Comparator.comparing((SimulationDtos.SnapEdge e) -> e.edgeUid() != null ? e.edgeUid() : "")
                        .thenComparing(e -> e.id() != null ? e.id() : Long.MAX_VALUE))
                .toList();
    }

    private List<SimulationDtos.SnapZone> sortZones(List<SimulationDtos.SnapZone> list) {
        if (list == null) return List.of();
        return list.stream()
                .sorted(Comparator.comparing((SimulationDtos.SnapZone z) -> z.zoneCode() != null ? z.zoneCode() : "")
                        .thenComparing(z -> z.id() != null ? z.id() : Long.MAX_VALUE))
                .toList();
    }

    private List<SimulationDtos.SnapElement> sortElements(List<SimulationDtos.SnapElement> list) {
        if (list == null) return List.of();
        return list.stream()
                .sorted(Comparator.comparing((SimulationDtos.SnapElement e) -> e.elementUid() != null ? e.elementUid() : "")
                        .thenComparing(e -> e.id() != null ? e.id() : Long.MAX_VALUE))
                .toList();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        if (!Double.isFinite(v)) {
            return 0.0;
        }
        return Math.round(v * 100.0) / 100.0;
    }

    private static final class SimNode {
        final int index;
        final PathwayNodeType nodeType;
        final String label;
        final double x;
        final double y;
        final Long linkedInfraId;
        final SimulationDtos.SnapZone linkedZone;
        final double serviceRatePerSec;
        boolean runtimeClosed = false;

        SimNode(int index,
                SimulationDtos.SnapNode dto,
                SimulationDtos.SnapElement linkedElem,
                SimulationDtos.SnapZone linkedZone,
                double serviceRatePerSec) {
            this.index = index;
            this.nodeType = dto.nodeType();
            this.label = dto.label() != null ? dto.label() : dto.nodeType().getDisplayName();
            this.x = dto.x();
            this.y = dto.y();
            this.linkedInfraId = dto.linkedInfraId();
            this.linkedZone = linkedZone;
            this.serviceRatePerSec = serviceRatePerSec;
        }
    }

    private static final class SimEdge {
        final int index;
        final int fromIdx;
        final int toIdx;
        final String label;
        final NetworkLayer layer;
        final double lengthM;
        final double nominalWidthM;
        final double effectiveWidthM;
        final double maxFlowPerSec;
        final int numSegs;
        final double segLen;
        final double segArea;
        final Coordinate[] coords;
        final double[] cumDist;

        final int[] segFwdCount;
        final int[] segRevCount;
        final double[] segDensity;
        double currentMaxDensity = 0.0;
        double peakObservedDensity = 0.0;
        double congestedDurationSec = 0.0;
        boolean runtimeActive = true;

        SimEdge(int index,
                SimulationDtos.SnapEdge dto,
                int fromIdx,
                int toIdx,
                LineString line,
                double lengthM,
                double nominalWidthM,
                double effectiveWidthM,
                double maxFlowPerSec,
                int numSegs,
                double segLen,
                double segArea) {
            this.index = index;
            this.fromIdx = fromIdx;
            this.toIdx = toIdx;
            this.label = (dto.label() != null && !dto.label().isBlank()) ? dto.label() : "Corridor #" + (index + 1);
            this.layer = dto.networkLayer() != null ? dto.networkLayer() : NetworkLayer.INTERNAL_CIRCULATION;
            this.lengthM = lengthM;
            this.nominalWidthM = nominalWidthM;
            this.effectiveWidthM = effectiveWidthM;
            this.maxFlowPerSec = maxFlowPerSec;
            this.numSegs = numSegs;
            this.segLen = segLen;
            this.segArea = segArea;
            this.coords = line.getCoordinates();
            this.cumDist = new double[coords.length];
            double acc = 0.0;
            cumDist[0] = 0.0;
            for (int i = 1; i < coords.length; i++) {
                acc += coords[i - 1].distance(coords[i]);
                cumDist[i] = acc;
            }
            this.segFwdCount = new int[numSegs];
            this.segRevCount = new int[numSegs];
            this.segDensity = new double[numSegs];
        }

        int segmentForProgress(double progressM) {
            int s = (int) Math.floor(Math.max(0.0, progressM) / segLen);
            return Math.max(0, Math.min(numSegs - 1, s));
        }

        double entrySegDensity(int startNodeIdx) {
            int seg = (startNodeIdx == fromIdx) ? 0 : (numSegs - 1);
            return segDensity[seg];
        }

        double[] interpolatePoint(double progressM, boolean reversed, double lateralOffsetM) {
            double totalPolyLen = cumDist[cumDist.length - 1];
            double d = Math.max(0.0, Math.min(totalPolyLen, reversed ? (totalPolyLen - progressM) : progressM));
            for (int i = 1; i < coords.length; i++) {
                if (d <= cumDist[i] + 1e-9 || i == coords.length - 1) {
                    double segD = Math.max(1e-6, cumDist[i] - cumDist[i - 1]);
                    double t = Math.max(0.0, Math.min(1.0, (d - cumDist[i - 1]) / segD));
                    double px = coords[i - 1].x + (coords[i].x - coords[i - 1].x) * t;
                    double py = coords[i - 1].y + (coords[i].y - coords[i - 1].y) * t;
                    double dx = coords[i].x - coords[i - 1].x;
                    double dy = coords[i].y - coords[i - 1].y;
                    double len = Math.hypot(dx, dy);
                    if (len > 1e-6 && Math.abs(lateralOffsetM) > 1e-6) {
                        double nx = -dy / len;
                        double ny = dx / len;
                        if (reversed) {
                            nx = -nx;
                            ny = -ny;
                        }
                        px += nx * lateralOffsetM;
                        py += ny * lateralOffsetM;
                    }
                    return new double[]{px, py};
                }
            }
            return new double[]{coords[0].x, coords[0].y};
        }
    }

    private record AdjEdge(int edgeIdx, int toNodeIdx, boolean reversed) {
    }

    private static final class DensityGrid {
        final double originX;
        final double originY;
        final double cellSizeM;
        final int cols;
        final int rows;
        final double[] walkableAreaSqm;

        DensityGrid(double originX, double originY, double cellSizeM, int cols, int rows, double[] walkableAreaSqm) {
            this.originX = originX;
            this.originY = originY;
            this.cellSizeM = cellSizeM;
            this.cols = cols;
            this.rows = rows;
            this.walkableAreaSqm = walkableAreaSqm;
        }

        int cellIndex(double wx, double wy) {
            int c = (int) Math.floor((wx - originX) / cellSizeM);
            int r = (int) Math.floor((wy - originY) / cellSizeM);
            if (c < 0 || c >= cols || r < 0 || r >= rows) {
                return -1;
            }
            return r * cols + c;
        }

        double densityAt(double wx, double wy, double[] densities) {
            int idx = cellIndex(wx, wy);
            return idx >= 0 ? densities[idx] : 0.0;
        }

        double cellCenterX(int cellIdx) {
            int c = cellIdx % cols;
            return originX + (c + 0.5) * cellSizeM;
        }

        double cellCenterY(int cellIdx) {
            int r = cellIdx / cols;
            return originY + (r + 0.5) * cellSizeM;
        }
    }

    private static final class SimAgent {
        final int id;
        final double spawnTimeSec;
        final int entryNodeIdx;
        int targetHubNodeIdx;
        final double dwellDurationSec;
        final double dwellX;
        final double dwellY;
        final double lateralSeed;

        AgentPhase phase = AgentPhase.NOT_SPAWNED;
        double x = 0.0;
        double y = 0.0;
        int atNode = -1;
        int onEdge = -1;
        int edgeStartNodeIdx = -1;
        int edgeEndNodeIdx = -1;
        boolean edgeReversed = false;
        double edgeProgressM = 0.0;
        List<Integer> pathNodes = null;
        int pathPos = 0;
        boolean clearedCheckpoint = false;
        double dwellRemaining = 0.0;
        double waitTimeSec = 0.0;
        double exitTimeSec = 0.0;

        SimAgent(int id,
                 double spawnTimeSec,
                 int entryNodeIdx,
                 int targetHubNodeIdx,
                 double dwellDurationSec,
                 double dwellX,
                 double dwellY,
                 double lateralSeed) {
            this.id = id;
            this.spawnTimeSec = spawnTimeSec;
            this.entryNodeIdx = entryNodeIdx;
            this.targetHubNodeIdx = targetHubNodeIdx;
            this.dwellDurationSec = dwellDurationSec;
            this.dwellX = dwellX;
            this.dwellY = dwellY;
            this.lateralSeed = lateralSeed;
        }
    }
}
