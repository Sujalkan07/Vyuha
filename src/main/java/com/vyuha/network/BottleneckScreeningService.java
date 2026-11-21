package com.vyuha.network;

import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.MovementGraphService.DeadEndStem;
import com.vyuha.network.MovementGraphService.DirectedTraversal;
import com.vyuha.network.MovementGraphService.GraphEdge;
import com.vyuha.network.MovementGraphService.GraphNode;
import com.vyuha.network.MovementGraphService.MovementGraph;
import com.vyuha.network.MovementGraphService.PathResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;

/**
 * Pure-Java automated pedestrian movement network and bottleneck screening engine.
 * Detects disconnected zones, isolated exits, dead-end corridors, narrow walkways,
 * high-convergence merges, contra-flow intersections, queue crossings, and entry gate overloads.
 */
@Service
public class BottleneckScreeningService {

    public static final double DEAD_END_MAX_LENGTH_M = 10.0;
    public static final double MIN_STANDARD_WIDTH_M = 2.4;
    public static final double MIN_CRITICAL_WIDTH_M = 1.5;
    public static final double MIN_EMERGENCY_WIDTH_M = 3.5;
    public static final double MERGE_MIN_RATIO = 0.75;
    public static final double MIN_BIDIRECTIONAL_CONTRAFLOW_WIDTH_M = 4.0;
    public static final double QUEUE_CROSSING_MIN_LENGTH_M = 0.25;
    public static final int DEFAULT_INGRESS_WINDOW_MINUTES = 60;
    public static final double OVERLOAD_CRITICAL_RATIO = 1.25;

    private static final Set<NetworkLayer> NORMAL_LAYERS = EnumSet.of(
            NetworkLayer.NORMAL_ENTRY,
            NetworkLayer.INTERNAL_CIRCULATION,
            NetworkLayer.NORMAL_EXIT
    );
    private static final Set<NetworkLayer> EMERGENCY_LAYERS = EnumSet.allOf(NetworkLayer.class);

    private final GeometryUtil geometryUtil;
    private final MovementGraphService graphService;

    public BottleneckScreeningService(GeometryUtil geometryUtil, MovementGraphService graphService) {
        this.geometryUtil = geometryUtil;
        this.graphService = graphService;
    }

    public record DetectedBottleneck(
            String findingKey,
            BottleneckFindingType findingType,
            FindingSeverity severity,
            String title,
            String description,
            String measuredValue,
            String thresholdNote,
            Double locationX,
            Double locationY,
            List<Long> involvedNodeIds,
            List<Long> involvedEdgeIds
    ) {
    }

    public record ScreeningResult(
            List<DetectedBottleneck> findings,
            int connectedComponents,
            double connectedEntryCapacityPerMin,
            double estimatedArrivalDemandPerMin,
            int effectiveIngressWindowMin
    ) {
    }

    public ScreeningResult screen(List<FunctionalZone> zones,
                                  List<InfrastructureElement> elements,
                                  List<PathwayNode> nodes,
                                  List<PathwayEdge> edges,
                                  Integer expectedAttendance,
                                  Integer ingressWindowMinutes,
                                  Double peakArrivalRateOverridePerMin) {
        List<FunctionalZone> safeZones = zones != null ? zones : List.of();
        List<InfrastructureElement> safeElements = elements != null ? elements : List.of();
        List<PathwayNode> safeNodes = nodes != null ? nodes : List.of();
        List<PathwayEdge> safeEdges = edges != null ? edges : List.of();

        int effectiveWindow = (ingressWindowMinutes != null && ingressWindowMinutes > 0)
                ? ingressWindowMinutes
                : DEFAULT_INGRESS_WINDOW_MINUTES;
        double demandPerMin = computeArrivalDemandPerMin(
                expectedAttendance, effectiveWindow, peakArrivalRateOverridePerMin);

        if (safeNodes.isEmpty() && safeEdges.isEmpty()) {
            return new ScreeningResult(
                    List.of(),
                    0,
                    0.0,
                    geometryUtil.round2(demandPerMin),
                    effectiveWindow
            );
        }

        MovementGraph graph = graphService.buildGraph(safeNodes, safeEdges);
        int components = graphService.countConnectedComponents(graph);

        Set<String> entrySources = new LinkedHashSet<>();
        Set<String> normalExitSinks = new LinkedHashSet<>();
        Set<String> emergencyExitSinks = new LinkedHashSet<>();
        Set<String> publicZoneNodes = new LinkedHashSet<>();

        classifyNodes(graph, entrySources, normalExitSinks, emergencyExitSinks, publicZoneNodes);

        Map<String, DetectedBottleneck> findingsByKey = new LinkedHashMap<>();

        detectDisconnectedZones(
                graph, safeZones, entrySources, normalExitSinks, emergencyExitSinks, publicZoneNodes, findingsByKey);
        detectIsolatedExits(
                graph, safeElements, normalExitSinks, emergencyExitSinks, publicZoneNodes, findingsByKey);
        detectDeadEnds(graph, findingsByKey);
        detectNarrowWalkways(graph, findingsByKey);
        detectHighConvergenceMerges(graph, findingsByKey);
        detectContraFlowConflicts(
                graph, entrySources, normalExitSinks, publicZoneNodes, findingsByKey);
        detectCrossingQueueConflicts(graph, safeZones, findingsByKey);

        double connectedEntryCap = computeConnectedEntryCapacity(graph, entrySources, publicZoneNodes);
        detectEntryGateOverload(
                graph, entrySources, publicZoneNodes, demandPerMin, connectedEntryCap, effectiveWindow, findingsByKey);

        List<DetectedBottleneck> sorted = new ArrayList<>(findingsByKey.values());
        sorted.sort(Comparator
                .comparingInt((DetectedBottleneck f) -> f.severity().sortRank())
                .thenComparing(DetectedBottleneck::findingKey));

        return new ScreeningResult(
                List.copyOf(sorted),
                components,
                geometryUtil.round2(connectedEntryCap),
                geometryUtil.round2(demandPerMin),
                effectiveWindow
        );
    }

    private void classifyNodes(MovementGraph graph,
                               Set<String> entrySources,
                               Set<String> normalExitSinks,
                               Set<String> emergencyExitSinks,
                               Set<String> publicZoneNodes) {
        for (GraphNode gn : graph.nodes()) {
            PathwayNode n = gn.entity();
            PathwayNodeType type = n.getNodeType();
            InfrastructureElement infra = n.getLinkedInfra();
            FunctionalZone zone = n.getLinkedZone();

            boolean isEntry = type == PathwayNodeType.ENTRY_GATE
                    || (infra != null && infra.isActive() && infra.getElementType() == InfrastructureType.ENTRY_GATE)
                    || (zone != null && zone.getZoneType() == ZoneType.ENTRY);

            boolean isNormalExit = type == PathwayNodeType.EXIT_GATE
                    || (infra != null && infra.isActive() && infra.getElementType() == InfrastructureType.EXIT_GATE)
                    || (zone != null && zone.getZoneType() == ZoneType.EXIT);

            boolean isEmergencyExit = type == PathwayNodeType.EMERGENCY_EXIT
                    || (infra != null && infra.isActive() && infra.getElementType() == InfrastructureType.EMERGENCY_EXIT)
                    || (zone != null && zone.getZoneType() == ZoneType.EMERGENCY_CORRIDOR);

            if (isEntry) {
                entrySources.add(gn.key());
            }
            if (isNormalExit) {
                normalExitSinks.add(gn.key());
            }
            if (isEmergencyExit) {
                emergencyExitSinks.add(gn.key());
            }

            if (type == PathwayNodeType.ZONE_HUB && !isEntry && !isNormalExit && !isEmergencyExit) {
                if (zone == null || isPublicDestinationZone(zone.getZoneType())) {
                    publicZoneNodes.add(gn.key());
                }
            }
        }
    }

    private boolean isPublicDestinationZone(ZoneType zoneType) {
        if (zoneType == null) {
            return true;
        }
        return zoneType == ZoneType.AUDIENCE
                || zoneType == ZoneType.FOOD_STALLS
                || zoneType == ZoneType.RESTROOMS
                || zoneType == ZoneType.MEDICAL;
    }

    private void detectDisconnectedZones(MovementGraph graph,
                                         List<FunctionalZone> zones,
                                         Set<String> entrySources,
                                         Set<String> normalExitSinks,
                                         Set<String> emergencyExitSinks,
                                         Set<String> publicZoneNodes,
                                         Map<String, DetectedBottleneck> out) {
        Set<String> reachableFromEntry = graphService.findReachableForward(graph, entrySources, NORMAL_LAYERS);
        Set<String> canReachNormalExit = graphService.findReachableBackward(graph, normalExitSinks, NORMAL_LAYERS);
        Set<String> canReachEmergencyExit = graphService.findReachableBackward(graph, emergencyExitSinks, EMERGENCY_LAYERS);

        for (String zoneKey : publicZoneNodes) {
            GraphNode gn = graph.node(zoneKey);
            if (gn == null) {
                continue;
            }
            List<String> missing = new ArrayList<>();
            if (!reachableFromEntry.contains(zoneKey)) {
                missing.add("Entry Gate");
            }
            if (!canReachNormalExit.contains(zoneKey)) {
                missing.add("Normal Exit");
            }
            if (!canReachEmergencyExit.contains(zoneKey)) {
                missing.add("Emergency Exit");
            }
            if (!missing.isEmpty()) {
                PathwayNode n = gn.entity();
                String missingJoined = String.join(", ", missing);
                addFinding(out, new DetectedBottleneck(
                        "DISCONNECTED_ZONE:" + zoneKey,
                        BottleneckFindingType.DISCONNECTED_ZONE,
                        FindingSeverity.CRITICAL,
                        n.getLabel() + " lacks connected access to " + missingJoined,
                        "Every public gathering zone must have directed movement paths from an Entry Gate, to a Normal Exit, and to an Emergency Exit.",
                        "Missing: " + missingJoined,
                        "Required: Entry Gate + Normal Exit + Emergency Exit paths",
                        n.getX(),
                        n.getY(),
                        idList(n.getId()),
                        List.of()
                ));
            }
        }

        // Also flag AUDIENCE zones with zero linked or contained pathway nodes
        for (int i = 0; i < zones.size(); i++) {
            FunctionalZone zone = zones.get(i);
            if (zone == null || zone.getZoneType() != ZoneType.AUDIENCE
                    || zone.getPolygonWkt() == null || zone.getPolygonWkt().isBlank()) {
                continue;
            }
            boolean hasLinkedNode = false;
            for (GraphNode gn : graph.nodes()) {
                FunctionalZone lz = gn.entity().getLinkedZone();
                if (lz != null && (lz == zone || (lz.getId() != null && lz.getId().equals(zone.getId())))) {
                    hasLinkedNode = true;
                    break;
                }
            }
            if (hasLinkedNode) {
                continue;
            }
            Geometry zGeom = safeParsePolygonal(zone.getPolygonWkt());
            if (zGeom == null) {
                continue;
            }
            boolean hasNodeInside = false;
            for (GraphNode gn : graph.nodes()) {
                Point pt = geometryUtil.getGeometryFactory().createPoint(
                        new Coordinate(gn.entity().getX(), gn.entity().getY()));
                if (zGeom.distance(pt) <= 0.5) {
                    hasNodeInside = true;
                    break;
                }
            }
            if (!hasNodeInside) {
                String zKey = zoneKey(zone, i);
                GeometryUtil.Point2D center = geometryUtil.interiorPoint(zGeom);
                addFinding(out, new DetectedBottleneck(
                        "DISCONNECTED_ZONE:UNLINKED-ZONE-" + zKey,
                        BottleneckFindingType.DISCONNECTED_ZONE,
                        FindingSeverity.CRITICAL,
                        zone.getName() + " has no connected pathway hub",
                        "Audience zone is not anchored to any movement network node.",
                        "0 connected pathway nodes",
                        "Every audience zone requires at least 1 connected node",
                        center != null ? center.x() : null,
                        center != null ? center.y() : null,
                        List.of(),
                        List.of()
                ));
            }
        }
    }

    private void detectIsolatedExits(MovementGraph graph,
                                     List<InfrastructureElement> elements,
                                     Set<String> normalExitSinks,
                                     Set<String> emergencyExitSinks,
                                     Set<String> publicZoneNodes,
                                     Map<String, DetectedBottleneck> out) {
        Set<String> sourceSeeds = new LinkedHashSet<>(publicZoneNodes);
        if (sourceSeeds.isEmpty()) {
            for (GraphNode gn : graph.nodes()) {
                if (!normalExitSinks.contains(gn.key())
                        && !emergencyExitSinks.contains(gn.key())
                        && !graph.undirectedEdges(gn.key()).isEmpty()) {
                    sourceSeeds.add(gn.key());
                }
            }
        }

        Set<String> normalReachable = graphService.findReachableForward(graph, sourceSeeds, NORMAL_LAYERS);
        Set<String> emergencyReachable = graphService.findReachableForward(graph, sourceSeeds, EMERGENCY_LAYERS);

        for (String exitKey : normalExitSinks) {
            if (!normalReachable.contains(exitKey)) {
                GraphNode gn = graph.node(exitKey);
                if (gn == null) continue;
                PathwayNode n = gn.entity();
                addFinding(out, new DetectedBottleneck(
                        "ISOLATED_EXIT:" + exitKey,
                        BottleneckFindingType.ISOLATED_EXIT,
                        FindingSeverity.CRITICAL,
                        n.getLabel() + " is unreachable from venue circulation",
                        "Normal exit node cannot be reached via active forward normal-layer pathways from public zones.",
                        "0 reachable origin zones",
                        "Exit must be reachable along active forward pathways",
                        n.getX(),
                        n.getY(),
                        idList(n.getId()),
                        List.of()
                ));
            }
        }

        for (String emKey : emergencyExitSinks) {
            if (!emergencyReachable.contains(emKey)) {
                GraphNode gn = graph.node(emKey);
                if (gn == null) continue;
                PathwayNode n = gn.entity();
                addFinding(out, new DetectedBottleneck(
                        "ISOLATED_EXIT:" + emKey,
                        BottleneckFindingType.ISOLATED_EXIT,
                        FindingSeverity.CRITICAL,
                        n.getLabel() + " (Emergency Exit) is isolated",
                        "Emergency exit node has no active forward path from public gathering zones.",
                        "0 reachable emergency routes",
                        "Emergency exit must be reachable from all public zones",
                        n.getX(),
                        n.getY(),
                        idList(n.getId()),
                        List.of()
                ));
            }
        }

        for (int i = 0; i < elements.size(); i++) {
            InfrastructureElement el = elements.get(i);
            if (el == null || !el.isActive() || el.getElementType() == null || !el.getElementType().isExitPortal()) {
                continue;
            }
            boolean linkedOrCovered = false;
            for (GraphNode gn : graph.nodes()) {
                InfrastructureElement li = gn.entity().getLinkedInfra();
                if (li != null && (li == el || (li.getId() != null && li.getId().equals(el.getId())))) {
                    linkedOrCovered = true;
                    break;
                }
                if (normalExitSinks.contains(gn.key()) || emergencyExitSinks.contains(gn.key())) {
                    double dist = Math.hypot(gn.entity().getX() - el.getCenterX(), gn.entity().getY() - el.getCenterY());
                    if (dist <= Math.max(el.getWidthM(), el.getHeightM()) / 2.0 + 2.0) {
                        linkedOrCovered = true;
                        break;
                    }
                }
            }
            if (!linkedOrCovered) {
                String elKey = infraKey(el, i);
                addFinding(out, new DetectedBottleneck(
                        "ISOLATED_EXIT:UNLINKED-INFRA-" + elKey,
                        BottleneckFindingType.ISOLATED_EXIT,
                        FindingSeverity.CRITICAL,
                        el.getLabel() + " is not connected to the pathway network",
                        "Placed exit portal has no linked pathway exit node in the movement network.",
                        "Unlinked exit portal",
                        "Connect exit portals to movement network",
                        el.getCenterX(),
                        el.getCenterY(),
                        List.of(),
                        List.of()
                ));
            }
        }
    }

    private void detectDeadEnds(MovementGraph graph, Map<String, DetectedBottleneck> out) {
        for (GraphNode gn : graph.nodes()) {
            PathwayNode n = gn.entity();
            if (n.getNodeType() != PathwayNodeType.INTERSECTION) {
                continue;
            }
            List<GraphEdge> undirected = graph.undirectedEdges(gn.key());
            if (undirected.size() == 1) {
                DeadEndStem stem = graphService.computeDeadEndStem(graph, gn.key());
                if (stem.totalLengthM() > DEAD_END_MAX_LENGTH_M + 1e-6) {
                    List<Long> nodeIds = resolveNodeIds(graph, stem.nodeKeys());
                    List<Long> edgeIds = resolveEdgeIds(graph, stem.edgeKeys());
                    addFinding(out, new DetectedBottleneck(
                            "DEAD_END_CORRIDOR:" + gn.key(),
                            BottleneckFindingType.DEAD_END_CORRIDOR,
                            FindingSeverity.HIGH,
                            "Dead-end corridor at " + n.getLabel(),
                            "Terminal walkway intersection extends beyond the 10.0 m dead-end corridor limit with no through-egress.",
                            String.format(Locale.US, "%.2f m dead-end stem (max %.2f m)",
                                    stem.totalLengthM(), DEAD_END_MAX_LENGTH_M),
                            String.format(Locale.US, "Max dead-end corridor length: %.2f m", DEAD_END_MAX_LENGTH_M),
                            n.getX(),
                            n.getY(),
                            nodeIds,
                            edgeIds
                    ));
                }
            } else if (undirected.size() >= 2) {
                int inDeg = graph.incomingTraversals(gn.key(), EMERGENCY_LAYERS).size();
                int outDeg = graph.outgoingTraversals(gn.key(), EMERGENCY_LAYERS).size();
                if (inDeg >= 1 && outDeg == 0) {
                    List<Long> edgeIds = undirected.stream()
                            .map(e -> e.entity().getId())
                            .filter(Objects::nonNull)
                            .toList();
                    addFinding(out, new DetectedBottleneck(
                            "DEAD_END_CORRIDOR:" + gn.key(),
                            BottleneckFindingType.DEAD_END_CORRIDOR,
                            FindingSeverity.HIGH,
                            "One-way dead-end trap at " + n.getLabel(),
                            "Incoming one-way walkways converge at this intersection with zero outgoing paths.",
                            inDeg + " incoming / 0 outgoing edges",
                            "Intersections must provide >= 1 outgoing traversal path",
                            n.getX(),
                            n.getY(),
                            idList(n.getId()),
                            edgeIds
                    ));
                }
            }
        }
    }

    private void detectNarrowWalkways(MovementGraph graph, Map<String, DetectedBottleneck> out) {
        for (GraphEdge ge : graph.activeEdges()) {
            PathwayEdge e = ge.entity();
            String edgeLabel = formatEdgeLabel(e);
            GeometryUtil.Point2D mid = resolveEdgeMidpoint(e);

            if (ge.layer() == NetworkLayer.EMERGENCY_ACCESS && ge.widthM() < MIN_EMERGENCY_WIDTH_M - 1e-6) {
                addFinding(out, new DetectedBottleneck(
                        "NARROW_WALKWAY:" + ge.key(),
                        BottleneckFindingType.NARROW_WALKWAY,
                        FindingSeverity.CRITICAL,
                        edgeLabel + " narrower than 3.5 m emergency width",
                        "Emergency access routes require at least 3.50 m clear width for emergency vehicle and stretcher passage.",
                        String.format(Locale.US, "%.2f m emergency width (min %.2f m)",
                                ge.widthM(), MIN_EMERGENCY_WIDTH_M),
                        String.format(Locale.US, "Emergency access min width: %.2f m", MIN_EMERGENCY_WIDTH_M),
                        mid != null ? mid.x() : null,
                        mid != null ? mid.y() : null,
                        idList(e.getFromNode().getId(), e.getToNode().getId()),
                        idList(e.getId())
                ));
            } else if (ge.widthM() < MIN_CRITICAL_WIDTH_M - 1e-6) {
                addFinding(out, new DetectedBottleneck(
                        "NARROW_WALKWAY:" + ge.key(),
                        BottleneckFindingType.NARROW_WALKWAY,
                        FindingSeverity.CRITICAL,
                        "Severe pinch point on " + edgeLabel,
                        "Walkway width is below 1.50 m critical minimum, risking rapid crowd compression.",
                        String.format(Locale.US, "%.2f m width (critical < %.2f m)",
                                ge.widthM(), MIN_CRITICAL_WIDTH_M),
                        String.format(Locale.US, "Standard min %.2f m; critical < %.2f m",
                                MIN_STANDARD_WIDTH_M, MIN_CRITICAL_WIDTH_M),
                        mid != null ? mid.x() : null,
                        mid != null ? mid.y() : null,
                        idList(e.getFromNode().getId(), e.getToNode().getId()),
                        idList(e.getId())
                ));
            } else if (ge.widthM() < MIN_STANDARD_WIDTH_M - 1e-6) {
                addFinding(out, new DetectedBottleneck(
                        "NARROW_WALKWAY:" + ge.key(),
                        BottleneckFindingType.NARROW_WALKWAY,
                        FindingSeverity.HIGH,
                        "Narrow walkway on " + edgeLabel,
                        "Walkway width falls below the 2.40 m circulation screening threshold.",
                        String.format(Locale.US, "%.2f m width (min %.2f m)",
                                ge.widthM(), MIN_STANDARD_WIDTH_M),
                        String.format(Locale.US, "Standard circulation min width: %.2f m", MIN_STANDARD_WIDTH_M),
                        mid != null ? mid.x() : null,
                        mid != null ? mid.y() : null,
                        idList(e.getFromNode().getId(), e.getToNode().getId()),
                        idList(e.getId())
                ));
            }
        }
    }

    private void detectHighConvergenceMerges(MovementGraph graph, Map<String, DetectedBottleneck> out) {
        for (GraphNode gn : graph.nodes()) {
            PathwayNode n = gn.entity();
            PathwayNodeType nt = n.getNodeType();
            if (nt != PathwayNodeType.INTERSECTION
                    && nt != PathwayNodeType.ZONE_HUB
                    && nt != PathwayNodeType.SECURITY_CHECKPOINT) {
                continue;
            }
            List<GraphEdge> allIncident = graph.undirectedEdges(gn.key());
            if (allIncident.size() < 4) {
                continue;
            }
            List<GraphEdge> incident = allIncident.stream()
                    .filter(e -> e.layer() != NetworkLayer.EMERGENCY_ACCESS)
                    .toList();
            if (incident.size() < 4) {
                incident = allIncident;
            }

            List<GraphEdge> pureIn = new ArrayList<>();
            List<GraphEdge> pureOut = new ArrayList<>();
            List<GraphEdge> bi = new ArrayList<>();

            for (GraphEdge ge : incident) {
                if (ge.direction() == PathwayDirection.ONE_WAY) {
                    if (ge.toKey().equals(gn.key())) {
                        pureIn.add(ge);
                    } else if (ge.fromKey().equals(gn.key())) {
                        pureOut.add(ge);
                    }
                } else {
                    bi.add(ge);
                }
            }

            int incomingCount = 0;
            double incomingSum = 0.0;
            double outgoingSum = 0.0;
            boolean triggered = false;
            List<Long> involvedEdgeIds = new ArrayList<>();

            if (pureIn.size() >= 3 && (pureOut.size() + bi.size()) >= 1) {
                incomingCount = pureIn.size();
                incomingSum = sumWidths(pureIn);
                outgoingSum = sumWidths(pureOut) + sumWidths(bi);
                triggered = outgoingSum < incomingSum * MERGE_MIN_RATIO - 1e-6;
            } else if (pureOut.size() == 1 && (pureIn.size() + bi.size()) >= 3) {
                incomingCount = pureIn.size() + bi.size();
                incomingSum = sumWidths(pureIn) + sumWidths(bi);
                outgoingSum = pureOut.get(0).widthM();
                triggered = outgoingSum < incomingSum * MERGE_MIN_RATIO - 1e-6;
            } else if (pureIn.isEmpty() && pureOut.isEmpty() && bi.size() >= 4) {
                GraphEdge candidateOut = pickMergeCandidateFromBidirectional(bi);
                if (candidateOut != null) {
                    List<GraphEdge> others = bi.stream().filter(e -> e != candidateOut).toList();
                    incomingCount = others.size();
                    incomingSum = sumWidths(others);
                    outgoingSum = candidateOut.widthM();
                    triggered = outgoingSum < incomingSum * MERGE_MIN_RATIO - 1e-6;
                }
            }

            if (triggered && incomingSum > 0.0) {
                for (GraphEdge ge : incident) {
                    if (ge.entity().getId() != null) {
                        involvedEdgeIds.add(ge.entity().getId());
                    }
                }
                double ratioPct = (outgoingSum / incomingSum) * 100.0;
                addFinding(out, new DetectedBottleneck(
                        "HIGH_CONVERGENCE_MERGE:" + gn.key(),
                        BottleneckFindingType.HIGH_CONVERGENCE_MERGE,
                        FindingSeverity.HIGH,
                        "Merge constriction at " + n.getLabel(),
                        String.format(Locale.US, "%d incoming lanes (%.2f m total) merge into %.2f m outgoing capacity.",
                                incomingCount, incomingSum, outgoingSum),
                        String.format(Locale.US, "%d lanes (%.2f m) -> %.2f m (%.0f%% < 75%%)",
                                incomingCount, incomingSum, outgoingSum, ratioPct),
                        "Outgoing width must be >= 75% of combined incoming lane widths",
                        n.getX(),
                        n.getY(),
                        idList(n.getId()),
                        List.copyOf(involvedEdgeIds)
                ));
            }
        }
    }

    private GraphEdge pickMergeCandidateFromBidirectional(List<GraphEdge> bi) {
        List<GraphEdge> exitEdges = bi.stream()
                .filter(e -> e.layer() == NetworkLayer.NORMAL_EXIT)
                .toList();
        if (exitEdges.size() == 1) {
            return exitEdges.get(0);
        }
        GraphEdge minEdge = null;
        double minW = Double.POSITIVE_INFINITY;
        double secondMinW = Double.POSITIVE_INFINITY;
        for (GraphEdge ge : bi) {
            if (ge.widthM() < minW) {
                secondMinW = minW;
                minW = ge.widthM();
                minEdge = ge;
            } else if (ge.widthM() < secondMinW) {
                secondMinW = ge.widthM();
            }
        }
        if (minEdge != null && minW < secondMinW - 1e-6) {
            return minEdge;
        }
        return null;
    }

    private void detectContraFlowConflicts(MovementGraph graph,
                                           Set<String> entrySources,
                                           Set<String> normalExitSinks,
                                           Set<String> publicZoneNodes,
                                           Map<String, DetectedBottleneck> out) {
        List<GraphEdge> entryEdges = new ArrayList<>();
        List<GraphEdge> exitEdges = new ArrayList<>();
        for (GraphEdge ge : graph.activeEdges()) {
            if (ge.layer() == NetworkLayer.NORMAL_ENTRY) {
                entryEdges.add(ge);
            } else if (ge.layer() == NetworkLayer.NORMAL_EXIT) {
                exitEdges.add(ge);
            }
        }

        // 6a. Geometric segment crossing between NORMAL_ENTRY and NORMAL_EXIT
        for (GraphEdge e1 : entryEdges) {
            LineString l1 = safeParseLine(e1.entity());
            if (l1 == null) continue;
            for (GraphEdge e2 : exitEdges) {
                LineString l2 = safeParseLine(e2.entity());
                if (l2 == null) continue;
                if (!l1.getEnvelopeInternal().intersects(l2.getEnvelopeInternal())) {
                    continue;
                }
                Geometry inter = l1.intersection(l2);
                boolean crossesMidSegment = l1.crosses(l2) || inter.getLength() > 0.05;
                if (crossesMidSegment) {
                    boolean firstKey = e1.key().compareTo(e2.key()) <= 0;
                    String minKey = firstKey ? e1.key() : e2.key();
                    String maxKey = firstKey ? e2.key() : e1.key();
                    GeometryUtil.Point2D pt = resolveIntersectionPoint(inter, e1.entity(), e2.entity());
                    addFinding(out, new DetectedBottleneck(
                            "CONTRA_FLOW_CROSSING:" + minKey + ":" + maxKey,
                            BottleneckFindingType.CONTRA_FLOW_CONFLICT,
                            FindingSeverity.CRITICAL,
                            "Entry & exit streams cross (" + formatEdgeLabel(e1.entity()) + " × " + formatEdgeLabel(e2.entity()) + ")",
                            "Normal entry pathway intersects a normal exit pathway at grade without separation.",
                            "At-grade entry/exit crossing",
                            "Separate entry and exit flows via non-intersecting corridors",
                            pt != null ? pt.x() : null,
                            pt != null ? pt.y() : null,
                            idList(e1.entity().getFromNode().getId(), e1.entity().getToNode().getId(),
                                    e2.entity().getFromNode().getId(), e2.entity().getToNode().getId()),
                            idList(e1.entity().getId(), e2.entity().getId())
                    ));
                }
            }
        }

        // 6b. At-grade X-junction where both entry and exit streams traverse an INTERSECTION node
        for (GraphNode gn : graph.nodes()) {
            if (gn.entity().getNodeType() != PathwayNodeType.INTERSECTION) {
                continue;
            }
            Set<NetworkLayer> entryOnly = Set.of(NetworkLayer.NORMAL_ENTRY);
            Set<NetworkLayer> exitOnly = Set.of(NetworkLayer.NORMAL_EXIT);
            List<DirectedTraversal> entryIn = graph.incomingTraversals(gn.key(), entryOnly);
            List<DirectedTraversal> entryOut = graph.outgoingTraversals(gn.key(), entryOnly);
            List<DirectedTraversal> exitIn = graph.incomingTraversals(gn.key(), exitOnly);
            List<DirectedTraversal> exitOut = graph.outgoingTraversals(gn.key(), exitOnly);

            long entryEdgeCount = graph.undirectedEdges(gn.key()).stream()
                    .filter(e -> e.layer() == NetworkLayer.NORMAL_ENTRY).count();
            long exitEdgeCount = graph.undirectedEdges(gn.key()).stream()
                    .filter(e -> e.layer() == NetworkLayer.NORMAL_EXIT).count();

            if (!entryIn.isEmpty() && !entryOut.isEmpty()
                    && !exitIn.isEmpty() && !exitOut.isEmpty()
                    && entryEdgeCount >= 2 && exitEdgeCount >= 2) {
                List<Long> edgeIds = graph.undirectedEdges(gn.key()).stream()
                        .filter(e -> e.layer() == NetworkLayer.NORMAL_ENTRY || e.layer() == NetworkLayer.NORMAL_EXIT)
                        .map(e -> e.entity().getId())
                        .filter(Objects::nonNull)
                        .toList();
                addFinding(out, new DetectedBottleneck(
                        "CONTRA_FLOW_JUNCTION:" + gn.key(),
                        BottleneckFindingType.CONTRA_FLOW_CONFLICT,
                        FindingSeverity.HIGH,
                        "Shared entry/exit junction at " + gn.entity().getLabel(),
                        "Both entry and exit pedestrian streams cross through the same intersection node.",
                        entryEdgeCount + " entry × " + exitEdgeCount + " exit edges",
                        "Route entry and exit corridors through separate junctions",
                        gn.entity().getX(),
                        gn.entity().getY(),
                        idList(gn.entity().getId()),
                        edgeIds
                ));
            }
        }

        // 6c. Narrow BIDIRECTIONAL edges (< 4.0 m) carrying primary or opposing entry/exit flows
        Set<String> edgesUsedForEntryForward = new HashSet<>();
        Set<String> edgesUsedForEntryBackward = new HashSet<>();
        Set<String> edgesUsedForExitForward = new HashSet<>();
        Set<String> edgesUsedForExitBackward = new HashSet<>();

        for (String src : entrySources) {
            for (String zoneKey : publicZoneNodes) {
                PathResult path = graphService.shortestPath(graph, src, zoneKey, NORMAL_LAYERS);
                recordDirectionalUsage(graph, path, edgesUsedForEntryForward, edgesUsedForEntryBackward);
            }
        }
        for (String zoneKey : publicZoneNodes) {
            for (String sink : normalExitSinks) {
                PathResult path = graphService.shortestPath(graph, zoneKey, sink, NORMAL_LAYERS);
                recordDirectionalUsage(graph, path, edgesUsedForExitForward, edgesUsedForExitBackward);
            }
        }

        for (GraphEdge ge : graph.activeEdges()) {
            if (ge.direction() != PathwayDirection.BIDIRECTIONAL
                    || ge.widthM() >= MIN_BIDIRECTIONAL_CONTRAFLOW_WIDTH_M - 1e-6) {
                continue;
            }
            boolean isPrimaryFlowLayer = ge.layer() == NetworkLayer.NORMAL_ENTRY
                    || ge.layer() == NetworkLayer.NORMAL_EXIT;
            boolean opposesOnShortestPaths =
                    (edgesUsedForEntryForward.contains(ge.key()) && edgesUsedForExitBackward.contains(ge.key()))
                    || (edgesUsedForEntryBackward.contains(ge.key()) && edgesUsedForExitForward.contains(ge.key()));
            boolean touchesBothEntryAndExit = isInternalBridgeBetweenEntryAndExit(graph, ge);

            if (isPrimaryFlowLayer || (ge.layer() == NetworkLayer.INTERNAL_CIRCULATION
                    && (opposesOnShortestPaths || touchesBothEntryAndExit))) {
                PathwayEdge e = ge.entity();
                GeometryUtil.Point2D mid = resolveEdgeMidpoint(e);
                addFinding(out, new DetectedBottleneck(
                        "CONTRA_FLOW_NARROW_BI:" + ge.key(),
                        BottleneckFindingType.CONTRA_FLOW_CONFLICT,
                        FindingSeverity.HIGH,
                        "Narrow bidirectional corridor: " + formatEdgeLabel(e),
                        "Bidirectional corridor carrying opposing primary flows is narrower than 4.00 m.",
                        String.format(Locale.US, "%.2f m bidirectional (min %.2f m)",
                                ge.widthM(), MIN_BIDIRECTIONAL_CONTRAFLOW_WIDTH_M),
                        String.format(Locale.US, "Min width for opposing bidirectional flow: %.2f m",
                                MIN_BIDIRECTIONAL_CONTRAFLOW_WIDTH_M),
                        mid != null ? mid.x() : null,
                        mid != null ? mid.y() : null,
                        idList(e.getFromNode().getId(), e.getToNode().getId()),
                        idList(e.getId())
                ));
            }
        }
    }

    private void recordDirectionalUsage(MovementGraph graph,
                                        PathResult path,
                                        Set<String> forwardEdges,
                                        Set<String> backwardEdges) {
        if (path == null || path.edgeKeys().isEmpty()) {
            return;
        }
        Map<String, GraphEdge> byKey = new HashMap<>();
        for (GraphEdge ge : graph.activeEdges()) {
            byKey.put(ge.key(), ge);
        }
        for (int i = 0; i < path.edgeKeys().size(); i++) {
            String ek = path.edgeKeys().get(i);
            String u = path.nodeKeys().get(i);
            GraphEdge ge = byKey.get(ek);
            if (ge == null) continue;
            if (ge.fromKey().equals(u)) {
                forwardEdges.add(ek);
            } else {
                backwardEdges.add(ek);
            }
        }
    }

    private boolean isInternalBridgeBetweenEntryAndExit(MovementGraph graph, GraphEdge ge) {
        boolean fromHasEntry = hasLayerAtNode(graph, ge.fromKey(), NetworkLayer.NORMAL_ENTRY);
        boolean toHasEntry = hasLayerAtNode(graph, ge.toKey(), NetworkLayer.NORMAL_ENTRY);
        boolean fromHasExit = hasLayerAtNode(graph, ge.fromKey(), NetworkLayer.NORMAL_EXIT);
        boolean toHasExit = hasLayerAtNode(graph, ge.toKey(), NetworkLayer.NORMAL_EXIT);
        return (fromHasEntry && toHasExit) || (toHasEntry && fromHasExit);
    }

    private boolean hasLayerAtNode(MovementGraph graph, String nodeKey, NetworkLayer layer) {
        return graph.undirectedEdges(nodeKey).stream().anyMatch(e -> e.layer() == layer);
    }

    private void detectCrossingQueueConflicts(MovementGraph graph,
                                              List<FunctionalZone> zones,
                                              Map<String, DetectedBottleneck> out) {
        for (int i = 0; i < zones.size(); i++) {
            FunctionalZone zone = zones.get(i);
            if (zone == null || zone.getZoneType() != ZoneType.QUEUE
                    || zone.getPolygonWkt() == null || zone.getPolygonWkt().isBlank()) {
                continue;
            }
            Geometry queueGeom = safeParsePolygonal(zone.getPolygonWkt());
            if (queueGeom == null) {
                continue;
            }
            String zKey = zoneKey(zone, i);
            for (GraphEdge ge : graph.activeEdges()) {
                LineString line = safeParseLine(ge.entity());
                if (line == null || !line.getEnvelopeInternal().intersects(queueGeom.getEnvelopeInternal())) {
                    continue;
                }
                Geometry inter = line.intersection(queueGeom);
                double interLen = inter.getLength();
                if (interLen <= QUEUE_CROSSING_MIN_LENGTH_M) {
                    continue;
                }
                double angleDeg = geometryUtil.acuteAngleBetweenLinesDeg(line, queueGeom);
                boolean endpointLinkedToQueue = isNodeLinkedToZone(ge.entity().getFromNode(), zone)
                        || isNodeLinkedToZone(ge.entity().getToNode(), zone);
                if (ge.layer() == NetworkLayer.NORMAL_ENTRY && endpointLinkedToQueue && angleDeg < 45.0) {
                    continue;
                }

                FindingSeverity severity = ge.layer() == NetworkLayer.EMERGENCY_ACCESS
                        ? FindingSeverity.CRITICAL
                        : FindingSeverity.HIGH;
                GeometryUtil.Point2D pt = resolveIntersectionPoint(inter, ge.entity(), null);
                if (pt == null) {
                    pt = geometryUtil.interiorPoint(queueGeom);
                }
                addFinding(out, new DetectedBottleneck(
                        "CROSSING_QUEUE:" + ge.key() + ":ZONE-" + zKey,
                        BottleneckFindingType.CROSSING_QUEUE_CONFLICT,
                        severity,
                        formatEdgeLabel(ge.entity()) + " crosses queue zone " + zone.getName(),
                        "Pathway cuts across a holding/queue zone, disrupting standing queues and creating cross-flow collisions.",
                        String.format(Locale.US, "%.2f m across queue (%.0f°)", interLen, angleDeg),
                        "Route circulation & exit pathways around queue holding zones",
                        pt != null ? pt.x() : null,
                        pt != null ? pt.y() : null,
                        idList(ge.entity().getFromNode().getId(), ge.entity().getToNode().getId()),
                        idList(ge.entity().getId())
                ));
            }
        }
    }

    private double computeConnectedEntryCapacity(MovementGraph graph,
                                                 Set<String> entrySources,
                                                 Set<String> publicZoneNodes) {
        double total = 0.0;
        for (String entryKey : entrySources) {
            GraphNode gn = graph.node(entryKey);
            if (gn == null) {
                continue;
            }
            boolean connected;
            if (!publicZoneNodes.isEmpty()) {
                Set<String> reached = graphService.findReachableForward(graph, Set.of(entryKey), NORMAL_LAYERS);
                connected = reached.stream().anyMatch(publicZoneNodes::contains);
            } else {
                connected = !graph.outgoingTraversals(entryKey, NORMAL_LAYERS).isEmpty();
            }
            if (!connected) {
                continue;
            }
            PathwayNode n = gn.entity();
            InfrastructureElement infra = n.getLinkedInfra();
            if (infra != null) {
                if (infra.isActive() && infra.getThroughputPersonsPerMin() != null) {
                    total += infra.getThroughputPersonsPerMin();
                }
            } else if (n.getNodeType() == PathwayNodeType.ENTRY_GATE) {
                Double def = InfrastructureType.ENTRY_GATE.getDefaultThroughputPerMin();
                if (def != null) {
                    total += def;
                }
            }
        }
        return total;
    }

    private void detectEntryGateOverload(MovementGraph graph,
                                         Set<String> entrySources,
                                         Set<String> publicZoneNodes,
                                         double demandPerMin,
                                         double connectedEntryCap,
                                         int effectiveWindow,
                                         Map<String, DetectedBottleneck> out) {
        if (demandPerMin <= 0.0 || (entrySources.isEmpty() && publicZoneNodes.isEmpty())) {
            return;
        }
        if (demandPerMin <= connectedEntryCap + 1e-6) {
            return;
        }
        boolean isCritical = connectedEntryCap <= 1e-6
                || demandPerMin > connectedEntryCap * OVERLOAD_CRITICAL_RATIO;
        FindingSeverity severity = isCritical ? FindingSeverity.CRITICAL : FindingSeverity.HIGH;

        Double locX = null;
        Double locY = null;
        List<Long> entryIds = new ArrayList<>();
        for (String key : entrySources) {
            GraphNode gn = graph.node(key);
            if (gn == null) continue;
            if (locX == null) {
                locX = gn.entity().getX();
                locY = gn.entity().getY();
            }
            if (gn.entity().getId() != null) {
                entryIds.add(gn.entity().getId());
            }
        }

        double ratioPct = connectedEntryCap > 1e-6 ? (demandPerMin / connectedEntryCap) * 100.0 : 999.0;
        String measured = connectedEntryCap > 1e-6
                ? String.format(Locale.US, "Demand %.1f p/m vs %.1f p/m capacity (%.0f%%)",
                        demandPerMin, connectedEntryCap, ratioPct)
                : String.format(Locale.US, "Demand %.1f p/m vs 0.0 p/m connected gate capacity", demandPerMin);

        addFinding(out, new DetectedBottleneck(
                "ENTRY_GATE_OVERLOAD",
                BottleneckFindingType.ENTRY_GATE_OVERLOAD,
                severity,
                "Expected arrival demand exceeds connected entry gate throughput",
                "Total connected entry gate processing rate is insufficient for expected arrival rate, indicating ingress queue buildup.",
                measured,
                String.format(Locale.US, "Derived over %d min ingress window (critical > 125%% capacity)", effectiveWindow),
                locX,
                locY,
                List.copyOf(entryIds),
                List.of()
        ));
    }

    private double computeArrivalDemandPerMin(Integer expectedAttendance,
                                              int effectiveWindow,
                                              Double peakOverride) {
        if (peakOverride != null && Double.isFinite(peakOverride) && peakOverride > 0.0) {
            return peakOverride;
        }
        if (expectedAttendance != null && expectedAttendance > 0) {
            return expectedAttendance / (double) effectiveWindow;
        }
        return 0.0;
    }

    private boolean isNodeLinkedToZone(PathwayNode node, FunctionalZone zone) {
        if (node == null || node.getLinkedZone() == null || zone == null) {
            return false;
        }
        FunctionalZone lz = node.getLinkedZone();
        return lz == zone || (lz.getId() != null && lz.getId().equals(zone.getId()));
    }

    private Geometry safeParsePolygonal(String wkt) {
        try {
            return geometryUtil.parsePolygonal(wkt);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private LineString safeParseLine(PathwayEdge edge) {
        if (edge == null) {
            return null;
        }
        if (edge.getPolylineWkt() != null && !edge.getPolylineWkt().isBlank()) {
            try {
                return geometryUtil.parseLineString(edge.getPolylineWkt());
            } catch (IllegalArgumentException ignored) {
                // Fall back to endpoint chord below
            }
        }
        if (edge.getFromNode() != null && edge.getToNode() != null) {
            try {
                return geometryUtil.buildLineString(List.of(
                        new GeometryUtil.Point2D(edge.getFromNode().getX(), edge.getFromNode().getY()),
                        new GeometryUtil.Point2D(edge.getToNode().getX(), edge.getToNode().getY())
                ));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private GeometryUtil.Point2D resolveEdgeMidpoint(PathwayEdge edge) {
        LineString line = safeParseLine(edge);
        if (line != null) {
            return geometryUtil.lineMidpoint(line);
        }
        if (edge.getFromNode() != null && edge.getToNode() != null) {
            return new GeometryUtil.Point2D(
                    (edge.getFromNode().getX() + edge.getToNode().getX()) / 2.0,
                    (edge.getFromNode().getY() + edge.getToNode().getY()) / 2.0);
        }
        return null;
    }

    private GeometryUtil.Point2D resolveIntersectionPoint(Geometry inter,
                                                        PathwayEdge primary,
                                                        PathwayEdge secondary) {
        if (inter != null && !inter.isEmpty()) {
            for (int i = 0; i < inter.getNumGeometries(); i++) {
                if (inter.getGeometryN(i) instanceof LineString ls) {
                    return geometryUtil.lineMidpoint(ls);
                }
            }
            GeometryUtil.Point2D c = geometryUtil.centroid(inter);
            if (c != null) {
                return c;
            }
        }
        if (primary != null) {
            return resolveEdgeMidpoint(primary);
        }
        return secondary != null ? resolveEdgeMidpoint(secondary) : null;
    }

    private String formatEdgeLabel(PathwayEdge edge) {
        if (edge.getLabel() != null && !edge.getLabel().isBlank()) {
            return edge.getLabel();
        }
        String from = edge.getFromNode() != null ? edge.getFromNode().getLabel() : "Start";
        String to = edge.getToNode() != null ? edge.getToNode().getLabel() : "End";
        return from + " -> " + to;
    }

    private double sumWidths(List<GraphEdge> edges) {
        double sum = 0.0;
        for (GraphEdge e : edges) {
            sum += e.widthM();
        }
        return sum;
    }

    private String zoneKey(FunctionalZone zone, int index) {
        if (zone.getZoneCode() != null && !zone.getZoneCode().isBlank()) {
            return zone.getZoneCode().trim();
        }
        if (zone.getId() != null) {
            return "ID-" + zone.getId();
        }
        return "IDX-" + index;
    }

    private String infraKey(InfrastructureElement el, int index) {
        if (el.getElementUid() != null && !el.getElementUid().isBlank()) {
            return el.getElementUid().trim();
        }
        if (el.getId() != null) {
            return "ID-" + el.getId();
        }
        return "IDX-" + index;
    }

    private List<Long> resolveNodeIds(MovementGraph graph, List<String> keys) {
        List<Long> ids = new ArrayList<>();
        for (String k : keys) {
            GraphNode gn = graph.node(k);
            if (gn != null && gn.entity().getId() != null && !ids.contains(gn.entity().getId())) {
                ids.add(gn.entity().getId());
            }
        }
        return List.copyOf(ids);
    }

    private List<Long> resolveEdgeIds(MovementGraph graph, List<String> keys) {
        Map<String, Long> edgeIdByKey = new HashMap<>();
        for (GraphEdge ge : graph.allEdges()) {
            if (ge.entity().getId() != null) {
                edgeIdByKey.put(ge.key(), ge.entity().getId());
            }
        }
        List<Long> ids = new ArrayList<>();
        for (String k : keys) {
            Long id = edgeIdByKey.get(k);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    private List<Long> idList(Long... ids) {
        List<Long> list = new ArrayList<>();
        for (Long id : ids) {
            if (id != null && !list.contains(id)) {
                list.add(id);
            }
        }
        return List.copyOf(list);
    }

    private void addFinding(Map<String, DetectedBottleneck> out, DetectedBottleneck finding) {
        out.putIfAbsent(finding.findingKey(), finding);
    }
}
