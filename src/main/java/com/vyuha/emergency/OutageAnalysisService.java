package com.vyuha.emergency;

import com.vyuha.common.GeometryUtil;
import com.vyuha.emergency.EmergencyDtos.ZoneOutageStatus;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.MovementGraphService;
import com.vyuha.network.MovementGraphService.GraphEdge;
import com.vyuha.network.MovementGraphService.GraphNode;
import com.vyuha.network.MovementGraphService.MovementGraph;
import com.vyuha.network.MovementGraphService.PathResult;
import com.vyuha.network.NetworkLayer;
import com.vyuha.network.PathwayEdge;
import com.vyuha.network.PathwayNode;
import com.vyuha.network.PathwayNodeType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;

/**
 * Pure-Java emergency failure-mode and exit/corridor outage analyzer.
 * Re-runs emergency reachability and shortest surviving backup paths over MovementGraphService
 * with selected exit portals, nodes, and pathway corridors disabled.
 */
@Service
public class OutageAnalysisService {

    public static final double NARROW_BACKUP_THRESHOLD_M = 2.4;
    public static final double FALLBACK_UNLINKED_EXIT_WIDTH_M = 3.5;

    private final MovementGraphService graphService;
    private final GeometryUtil geometryUtil;

    public OutageAnalysisService(MovementGraphService graphService, GeometryUtil geometryUtil) {
        this.graphService = graphService;
        this.geometryUtil = geometryUtil;
    }

    private record BaselineOrigin(
            String nodeKey,
            Long zoneId,
            String zoneCode,
            String zoneName,
            Long hubNodeId,
            String hubNodeLabel,
            double x,
            double y
    ) {
    }

    private record ExitPortalDescriptor(
            String portalKey,
            String sourceType,
            Long infraId,
            Long nodeId,
            Long assetId,
            String baselineNodeKey,
            String label,
            boolean dedicatedEmergencyExit,
            double egressWidthM,
            Double throughputPerMin,
            boolean directlyDisabled,
            double x,
            double y
    ) {
    }

    public EmergencyDtos.OutageAnalysisResult analyzeOutage(List<FunctionalZone> zones,
                                                            List<InfrastructureElement> elements,
                                                            List<PathwayNode> nodes,
                                                            List<PathwayEdge> edges,
                                                            List<EmergencyAsset> assets,
                                                            Set<Long> disabledExitInfraIds,
                                                            Set<Long> disabledNodeIds,
                                                            Set<Long> disabledEdgeIds,
                                                            Set<Long> disabledAssetIds,
                                                            boolean emergencyExitsOnly) {
        List<FunctionalZone> safeZones = zones != null ? zones : List.of();
        List<InfrastructureElement> safeElements = elements != null ? elements : List.of();
        List<PathwayNode> safeNodes = nodes != null ? nodes : List.of();
        List<PathwayEdge> safeEdges = edges != null ? edges : List.of();
        List<EmergencyAsset> safeAssets = assets != null ? assets : List.of();

        Set<Long> normalizedInfraDisabled = new LinkedHashSet<>(safeIdSet(disabledExitInfraIds));
        Set<Long> normalizedNodeDisabled = new LinkedHashSet<>(safeIdSet(disabledNodeIds));
        Set<Long> normalizedEdgeDisabled = new LinkedHashSet<>(safeIdSet(disabledEdgeIds));
        Set<Long> normalizedAssetDisabled = new LinkedHashSet<>(safeIdSet(disabledAssetIds));

        crossLinkDisabledExits(
                safeElements, safeNodes, safeAssets,
                normalizedInfraDisabled, normalizedNodeDisabled, normalizedAssetDisabled);

        MovementGraph baselineGraph = graphService.buildGraph(safeNodes, safeEdges);
        MovementGraph outageGraph = graphService.buildGraph(
                safeNodes, safeEdges, normalizedNodeDisabled, normalizedEdgeDisabled);

        Set<NetworkLayer> allowedLayers = EnumSet.allOf(NetworkLayer.class);

        Set<String> baselineDedicatedSinks = new LinkedHashSet<>();
        Set<String> baselineNormalExitSinks = new LinkedHashSet<>();
        classifySinkKeys(baselineGraph, baselineDedicatedSinks, baselineNormalExitSinks);

        Set<String> outageDedicatedSinks = new LinkedHashSet<>();
        Set<String> outageNormalExitSinks = new LinkedHashSet<>();
        classifySinkKeys(outageGraph, outageDedicatedSinks, outageNormalExitSinks);

        Set<String> baselineTargetSinks = new LinkedHashSet<>(baselineDedicatedSinks);
        Set<String> outageTargetSinks = new LinkedHashSet<>(outageDedicatedSinks);
        if (!emergencyExitsOnly) {
            baselineTargetSinks.addAll(baselineNormalExitSinks);
            outageTargetSinks.addAll(outageNormalExitSinks);
        }

        List<BaselineOrigin> origins = collectZoneOrigins(baselineGraph, safeZones);
        Set<String> baselineOriginKeys = new LinkedHashSet<>();
        Set<String> survivingOriginKeys = new LinkedHashSet<>();
        for (BaselineOrigin origin : origins) {
            if (origin.nodeKey() != null) {
                baselineOriginKeys.add(origin.nodeKey());
                if (outageGraph.node(origin.nodeKey()) != null) {
                    survivingOriginKeys.add(origin.nodeKey());
                }
            }
        }

        Map<String, GraphEdge> outageEdgeByKey = new HashMap<>();
        for (GraphEdge ge : outageGraph.activeEdges()) {
            outageEdgeByKey.put(ge.key(), ge);
        }

        List<EmergencyDtos.ZoneOutageImpactDto> zoneImpacts = new ArrayList<>();
        int unaffectedCount = 0;
        int reroutedCount = 0;
        int newlyIsolatedCount = 0;
        int alreadyIsolatedCount = 0;

        for (BaselineOrigin origin : origins) {
            if (origin.nodeKey() == null) {
                alreadyIsolatedCount++;
                zoneImpacts.add(new EmergencyDtos.ZoneOutageImpactDto(
                        origin.zoneId(),
                        origin.zoneCode(),
                        origin.zoneName(),
                        null,
                        null,
                        ZoneOutageStatus.ALREADY_ISOLATED,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        List.of(),
                        List.of(),
                        List.of(),
                        origin.x(),
                        origin.y()
                ));
                continue;
            }

            PathResult baselinePath = graphService.shortestPathToAny(
                    baselineGraph, origin.nodeKey(), baselineTargetSinks, allowedLayers);
            PathResult outagePath = outageGraph.node(origin.nodeKey()) != null
                    ? graphService.shortestPathToAny(outageGraph, origin.nodeKey(), outageTargetSinks, allowedLayers)
                    : null;

            PathResult baselineDedicatedPath = graphService.shortestPathToAny(
                    baselineGraph, origin.nodeKey(), baselineDedicatedSinks, allowedLayers);
            PathResult outageDedicatedPath = outageGraph.node(origin.nodeKey()) != null
                    ? graphService.shortestPathToAny(outageGraph, origin.nodeKey(), outageDedicatedSinks, allowedLayers)
                    : null;
            boolean lostDedicatedEmergency = baselineDedicatedPath != null && outageDedicatedPath == null;

            ZoneOutageStatus status;
            if (baselinePath == null && outagePath == null) {
                status = ZoneOutageStatus.ALREADY_ISOLATED;
                alreadyIsolatedCount++;
            } else if (baselinePath != null && outagePath == null) {
                status = ZoneOutageStatus.NEWLY_ISOLATED;
                newlyIsolatedCount++;
            } else if (baselinePath != null) {
                boolean changed = !baselinePath.edgeKeys().equals(outagePath.edgeKeys())
                        || !baselinePath.nodeKeys().equals(outagePath.nodeKeys());
                if (changed) {
                    status = ZoneOutageStatus.REROUTED;
                    reroutedCount++;
                } else {
                    status = ZoneOutageStatus.UNAFFECTED;
                    unaffectedCount++;
                }
            } else {
                status = ZoneOutageStatus.UNAFFECTED;
                unaffectedCount++;
            }

            String baselineExitLabel = resolveEndNodeLabel(baselineGraph, baselinePath);
            Double baselineDist = baselinePath != null ? geometryUtil.round2(baselinePath.totalLengthM()) : null;
            String backupExitLabel = resolveEndNodeLabel(outageGraph, outagePath);
            Double backupDist = outagePath != null ? geometryUtil.round2(outagePath.totalLengthM()) : null;

            Double detourM = null;
            Double detourPct = null;
            if (baselineDist != null && backupDist != null) {
                double rawDetour = Math.max(0.0, backupDist - baselineDist);
                detourM = geometryUtil.round2(rawDetour);
                detourPct = baselineDist > 0.0
                        ? geometryUtil.round2((rawDetour / baselineDist) * 100.0)
                        : 0.0;
            }

            Double backupMinWidth = outagePath != null ? geometryUtil.round2(outagePath.minWidthM()) : null;
            boolean backupNarrowWarning = backupMinWidth != null && backupMinWidth < NARROW_BACKUP_THRESHOLD_M - 1e-6;

            List<Long> backupNodeIds = outagePath != null
                    ? resolveOrderedNodeIds(outageGraph, outagePath.nodeKeys())
                    : List.of();
            List<Long> backupEdgeIds = outagePath != null
                    ? resolveOrderedEdgeIds(outageEdgeByKey, outagePath.edgeKeys())
                    : List.of();
            List<GeometryUtil.Point2D> backupPathPoints = outagePath != null
                    ? stitchBackupPathPoints(outageGraph, outageEdgeByKey, outagePath)
                    : List.of();

            zoneImpacts.add(new EmergencyDtos.ZoneOutageImpactDto(
                    origin.zoneId(),
                    origin.zoneCode(),
                    origin.zoneName(),
                    origin.hubNodeId(),
                    origin.hubNodeLabel(),
                    status,
                    lostDedicatedEmergency,
                    baselineExitLabel,
                    baselineDist,
                    backupExitLabel,
                    backupDist,
                    detourM,
                    detourPct,
                    backupMinWidth,
                    backupNarrowWarning,
                    backupNodeIds,
                    backupEdgeIds,
                    backupPathPoints,
                    geometryUtil.round2(origin.x()),
                    geometryUtil.round2(origin.y())
            ));
        }

        zoneImpacts.sort(Comparator
                .comparingInt((EmergencyDtos.ZoneOutageImpactDto z) -> statusSortRank(z.status()))
                .thenComparing(Comparator.comparingDouble(
                        (EmergencyDtos.ZoneOutageImpactDto z) -> z.detourDistanceM() != null ? z.detourDistanceM() : 0.0)
                        .reversed())
                .thenComparing(z -> z.zoneName() != null ? z.zoneName() : ""));

        // Compute exit portal operational statuses & egress width/throughput capacity loss
        List<ExitPortalDescriptor> portals = collectExitPortals(
                baselineGraph, safeElements, safeAssets,
                normalizedInfraDisabled, normalizedNodeDisabled, normalizedAssetDisabled,
                emergencyExitsOnly);

        Set<String> baselineReachable = !baselineOriginKeys.isEmpty()
                ? graphService.findReachableForward(baselineGraph, baselineOriginKeys, allowedLayers)
                : Set.of();
        Set<String> outageReachable = !survivingOriginKeys.isEmpty()
                ? graphService.findReachableForward(outageGraph, survivingOriginKeys, allowedLayers)
                : Set.of();

        List<EmergencyDtos.ExitOutageStatusDto> exitStatuses = new ArrayList<>(portals.size());
        double baselineWidthSum = 0.0;
        double remainingWidthSum = 0.0;
        double baselineThroughputSum = 0.0;
        double remainingThroughputSum = 0.0;
        int operationalExits = 0;
        int cutOffExits = 0;

        for (ExitPortalDescriptor portal : portals) {
            boolean hasOrigins = !baselineOriginKeys.isEmpty();
            boolean reachableInBaseline = portal.baselineNodeKey() != null && baselineReachable.contains(portal.baselineNodeKey());
            boolean reachableInOutage = portal.baselineNodeKey() != null && outageReachable.contains(portal.baselineNodeKey());

            boolean cutOffByCorridor = !portal.directlyDisabled()
                    && hasOrigins
                    && portal.baselineNodeKey() != null
                    && reachableInBaseline
                    && !reachableInOutage;

            boolean alreadyUnreachableInBaseline = hasOrigins
                    && portal.baselineNodeKey() != null
                    && !reachableInBaseline;

            boolean operational = !portal.directlyDisabled() && !cutOffByCorridor && !alreadyUnreachableInBaseline;
            if (cutOffByCorridor) {
                cutOffExits++;
            }
            if (operational) {
                operationalExits++;
            }

            boolean countsTowardBaseline = !hasOrigins || portal.baselineNodeKey() == null || reachableInBaseline;
            if (countsTowardBaseline) {
                baselineWidthSum += portal.egressWidthM();
                if (portal.throughputPerMin() != null) {
                    baselineThroughputSum += portal.throughputPerMin();
                }
                if (operational) {
                    remainingWidthSum += portal.egressWidthM();
                    if (portal.throughputPerMin() != null) {
                        remainingThroughputSum += portal.throughputPerMin();
                    }
                }
            }

            exitStatuses.add(new EmergencyDtos.ExitOutageStatusDto(
                    portal.portalKey(),
                    portal.sourceType(),
                    portal.infraId(),
                    portal.nodeId(),
                    portal.assetId(),
                    portal.label(),
                    portal.dedicatedEmergencyExit(),
                    geometryUtil.round2(portal.egressWidthM()),
                    portal.throughputPerMin() != null ? geometryUtil.round2(portal.throughputPerMin()) : null,
                    portal.directlyDisabled(),
                    cutOffByCorridor,
                    operational,
                    geometryUtil.round2(portal.x()),
                    geometryUtil.round2(portal.y())
            ));
        }

        double widthLoss = Math.max(0.0, baselineWidthSum - remainingWidthSum);
        double widthLossPct = baselineWidthSum > 0.0
                ? geometryUtil.round2((widthLoss / baselineWidthSum) * 100.0)
                : 0.0;
        double remainingCapacityPct = baselineWidthSum > 0.0
                ? geometryUtil.round2((remainingWidthSum / baselineWidthSum) * 100.0)
                : (portals.isEmpty() ? 0.0 : 100.0);

        double throughputLoss = Math.max(0.0, baselineThroughputSum - remainingThroughputSum);
        double throughputLossPct = baselineThroughputSum > 0.0
                ? geometryUtil.round2((throughputLoss / baselineThroughputSum) * 100.0)
                : 0.0;
        double remainingThroughputPct = baselineThroughputSum > 0.0
                ? geometryUtil.round2((remainingThroughputSum / baselineThroughputSum) * 100.0)
                : (portals.isEmpty() ? 0.0 : 100.0);

        return new EmergencyDtos.OutageAnalysisResult(
                emergencyExitsOnly,
                sortedIds(normalizedInfraDisabled),
                sortedIds(normalizedNodeDisabled),
                sortedIds(normalizedEdgeDisabled),
                sortedIds(normalizedAssetDisabled),
                zoneImpacts.size(),
                unaffectedCount,
                reroutedCount,
                newlyIsolatedCount,
                alreadyIsolatedCount,
                geometryUtil.round2(baselineWidthSum),
                geometryUtil.round2(remainingWidthSum),
                geometryUtil.round2(widthLoss),
                widthLossPct,
                remainingCapacityPct,
                geometryUtil.round2(baselineThroughputSum),
                geometryUtil.round2(remainingThroughputSum),
                throughputLossPct,
                remainingThroughputPct,
                portals.size(),
                operationalExits,
                cutOffExits,
                List.copyOf(exitStatuses),
                List.copyOf(zoneImpacts)
        );
    }

    private void crossLinkDisabledExits(List<InfrastructureElement> elements,
                                        List<PathwayNode> nodes,
                                        List<EmergencyAsset> assets,
                                        Set<Long> disabledInfraIds,
                                        Set<Long> disabledNodeIds,
                                        Set<Long> disabledAssetIds) {
        Map<Long, InfrastructureElement> exitInfraById = new LinkedHashMap<>();
        for (InfrastructureElement el : elements) {
            if (el != null && el.isActive() && el.getElementType() != null && el.getElementType().isExitPortal()
                    && el.getId() != null) {
                exitInfraById.put(el.getId(), el);
            }
        }

        // Disabled asset -> matching proximate node / infra exit
        for (EmergencyAsset asset : assets) {
            if (asset == null || asset.getAssetType() != EmergencyAssetType.EMERGENCY_EXIT
                    || asset.getId() == null || !disabledAssetIds.contains(asset.getId())) {
                continue;
            }
            GeometryUtil.Point2D anchor = resolveAssetPoint(asset);
            if (anchor == null) {
                continue;
            }
            for (InfrastructureElement el : exitInfraById.values()) {
                double dist = Math.hypot(el.getCenterX() - anchor.x(), el.getCenterY() - anchor.y());
                if (dist <= Math.max(el.getWidthM(), el.getHeightM()) / 2.0 + 2.0) {
                    disabledInfraIds.add(el.getId());
                }
            }
            for (PathwayNode node : nodes) {
                if (node != null && node.getId() != null && isAnyExitNode(node)) {
                    double dist = Math.hypot(node.getX() - anchor.x(), node.getY() - anchor.y());
                    if (dist <= 2.0) {
                        disabledNodeIds.add(node.getId());
                    }
                }
            }
        }

        // Infra <-> Node linking (explicit FK + proximity fallback for unlinked nodes)
        for (PathwayNode node : nodes) {
            if (node == null) {
                continue;
            }
            InfrastructureElement linked = node.getLinkedInfra();
            if (linked != null && linked.getId() != null && linked.isActive()
                    && linked.getElementType() != null && linked.getElementType().isExitPortal()) {
                if (disabledInfraIds.contains(linked.getId()) && node.getId() != null) {
                    disabledNodeIds.add(node.getId());
                } else if (node.getId() != null && disabledNodeIds.contains(node.getId())) {
                    disabledInfraIds.add(linked.getId());
                }
                continue;
            }

            if (isAnyExitNode(node)) {
                for (InfrastructureElement el : exitInfraById.values()) {
                    double threshold = Math.max(el.getWidthM(), el.getHeightM()) / 2.0 + 2.0;
                    double dist = Math.hypot(node.getX() - el.getCenterX(), node.getY() - el.getCenterY());
                    if (dist <= threshold) {
                        if (disabledInfraIds.contains(el.getId()) && node.getId() != null) {
                            disabledNodeIds.add(node.getId());
                        } else if (node.getId() != null && disabledNodeIds.contains(node.getId())) {
                            disabledInfraIds.add(el.getId());
                        }
                    }
                }
            }
        }
    }

    private void classifySinkKeys(MovementGraph graph,
                                  Set<String> dedicatedEmergencySinks,
                                  Set<String> normalExitSinks) {
        for (GraphNode gn : graph.nodes()) {
            PathwayNode n = gn.entity();
            InfrastructureElement infra = n.getLinkedInfra();
            FunctionalZone zone = n.getLinkedZone();

            boolean isEmergency = n.getNodeType() == PathwayNodeType.EMERGENCY_EXIT
                    || (infra != null && infra.isActive() && infra.getElementType() == InfrastructureType.EMERGENCY_EXIT)
                    || (zone != null && zone.getZoneType() == ZoneType.EMERGENCY_CORRIDOR);

            boolean isNormalExit = !isEmergency && (n.getNodeType() == PathwayNodeType.EXIT_GATE
                    || (infra != null && infra.isActive() && infra.getElementType() == InfrastructureType.EXIT_GATE)
                    || (zone != null && zone.getZoneType() == ZoneType.EXIT));

            if (isEmergency) {
                dedicatedEmergencySinks.add(gn.key());
            } else if (isNormalExit) {
                normalExitSinks.add(gn.key());
            }
        }
    }

    private List<BaselineOrigin> collectZoneOrigins(MovementGraph graph, List<FunctionalZone> zones) {
        List<BaselineOrigin> origins = new ArrayList<>();
        Set<Long> coveredZoneIds = new LinkedHashSet<>();
        Set<FunctionalZone> coveredZoneRefs = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (GraphNode gn : graph.nodes()) {
            PathwayNode n = gn.entity();
            if (n.getNodeType() != PathwayNodeType.ZONE_HUB) {
                continue;
            }
            FunctionalZone lz = n.getLinkedZone();
            if (lz != null && (lz.getZoneType() == ZoneType.ENTRY
                    || lz.getZoneType() == ZoneType.EXIT
                    || lz.getZoneType() == ZoneType.EMERGENCY_CORRIDOR
                    || lz.getZoneType() == ZoneType.RESTRICTED)) {
                continue;
            }
            InfrastructureElement li = n.getLinkedInfra();
            if (li != null && li.isActive() && li.getElementType() != null && li.getElementType().isExitPortal()) {
                continue;
            }
            if (lz != null) {
                coveredZoneRefs.add(lz);
                if (lz.getId() != null) {
                    coveredZoneIds.add(lz.getId());
                }
            }
            origins.add(new BaselineOrigin(
                    gn.key(),
                    lz != null ? lz.getId() : null,
                    lz != null ? lz.getZoneCode() : "HUB",
                    lz != null ? lz.getName() : n.getLabel(),
                    n.getId(),
                    n.getLabel(),
                    n.getX(),
                    n.getY()
            ));
        }

        // Include AUDIENCE and FOOD_STALLS zones that lack any linked or spatially contained ZONE_HUB
        for (FunctionalZone zone : zones) {
            if (zone == null || (zone.getZoneType() != ZoneType.AUDIENCE && zone.getZoneType() != ZoneType.FOOD_STALLS)) {
                continue;
            }
            if (coveredZoneRefs.contains(zone) || (zone.getId() != null && coveredZoneIds.contains(zone.getId()))) {
                continue;
            }
            Geometry zGeom = safeParsePolygonal(zone.getPolygonWkt());
            if (zGeom == null) {
                continue;
            }
            boolean hasContainedNode = false;
            for (GraphNode gn : graph.nodes()) {
                Point pt = geometryUtil.getGeometryFactory().createPoint(
                        new Coordinate(gn.entity().getX(), gn.entity().getY()));
                if (zGeom.distance(pt) <= 0.5) {
                    hasContainedNode = true;
                    break;
                }
            }
            if (!hasContainedNode) {
                GeometryUtil.Point2D center = geometryUtil.interiorPoint(zGeom);
                origins.add(new BaselineOrigin(
                        null,
                        zone.getId(),
                        zone.getZoneCode(),
                        zone.getName(),
                        null,
                        null,
                        center != null ? center.x() : 0.0,
                        center != null ? center.y() : 0.0
                ));
            }
        }
        return origins;
    }

    private List<ExitPortalDescriptor> collectExitPortals(MovementGraph baselineGraph,
                                                          List<InfrastructureElement> elements,
                                                          List<EmergencyAsset> assets,
                                                          Set<Long> disabledInfraIds,
                                                          Set<Long> disabledNodeIds,
                                                          Set<Long> disabledAssetIds,
                                                          boolean emergencyExitsOnly) {
        List<ExitPortalDescriptor> portals = new ArrayList<>();
        Set<String> matchedNodeKeys = new LinkedHashSet<>();

        for (int i = 0; i < elements.size(); i++) {
            InfrastructureElement el = elements.get(i);
            if (el == null || !el.isActive() || el.getElementType() == null || !el.getElementType().isExitPortal()) {
                continue;
            }
            boolean isEmergency = el.getElementType() == InfrastructureType.EMERGENCY_EXIT;
            if (emergencyExitsOnly && !isEmergency) {
                continue;
            }
            GraphNode matchedNode = findMatchingExitNode(baselineGraph, el);
            if (matchedNode != null) {
                matchedNodeKeys.add(matchedNode.key());
            }
            boolean directlyDisabled = (el.getId() != null && disabledInfraIds.contains(el.getId()))
                    || (matchedNode != null && matchedNode.entity().getId() != null
                    && disabledNodeIds.contains(matchedNode.entity().getId()));
            double widthM = el.getWidthM() > 0.0 ? el.getWidthM() : el.getElementType().getDefaultWidthM();
            Double throughput = el.getThroughputPersonsPerMin() != null
                    ? el.getThroughputPersonsPerMin()
                    : el.getElementType().getDefaultThroughputPerMin();

            portals.add(new ExitPortalDescriptor(
                    "INFRA-" + (el.getId() != null ? el.getId() : i),
                    "INFRASTRUCTURE_EXIT",
                    el.getId(),
                    matchedNode != null ? matchedNode.entity().getId() : null,
                    null,
                    matchedNode != null ? matchedNode.key() : null,
                    el.getLabel(),
                    isEmergency,
                    widthM,
                    throughput,
                    directlyDisabled,
                    el.getCenterX(),
                    el.getCenterY()
            ));
        }

        for (GraphNode gn : baselineGraph.nodes()) {
            if (matchedNodeKeys.contains(gn.key())) {
                continue;
            }
            PathwayNode n = gn.entity();
            InfrastructureElement linkedInfra = n.getLinkedInfra();
            FunctionalZone linkedZone = n.getLinkedZone();
            boolean isEmergency = n.getNodeType() == PathwayNodeType.EMERGENCY_EXIT
                    || (linkedInfra != null && linkedInfra.isActive() && linkedInfra.getElementType() == InfrastructureType.EMERGENCY_EXIT)
                    || (linkedZone != null && linkedZone.getZoneType() == ZoneType.EMERGENCY_CORRIDOR);
            boolean isNormalExit = !isEmergency && (n.getNodeType() == PathwayNodeType.EXIT_GATE
                    || (linkedInfra != null && linkedInfra.isActive() && linkedInfra.getElementType() == InfrastructureType.EXIT_GATE)
                    || (linkedZone != null && linkedZone.getZoneType() == ZoneType.EXIT));

            if (!isEmergency && !isNormalExit) {
                continue;
            }
            if (emergencyExitsOnly && !isEmergency) {
                continue;
            }

            double incidentWidthSum = 0.0;
            for (GraphEdge ge : baselineGraph.undirectedEdges(gn.key())) {
                incidentWidthSum += ge.widthM();
            }
            double widthM = incidentWidthSum > 0.0 ? incidentWidthSum : FALLBACK_UNLINKED_EXIT_WIDTH_M;
            boolean directlyDisabled = n.getId() != null && disabledNodeIds.contains(n.getId());

            portals.add(new ExitPortalDescriptor(
                    "NODE-" + (n.getId() != null ? n.getId() : gn.key()),
                    "PATHWAY_NODE",
                    null,
                    n.getId(),
                    null,
                    gn.key(),
                    n.getLabel(),
                    isEmergency,
                    widthM,
                    null,
                    directlyDisabled,
                    n.getX(),
                    n.getY()
            ));
        }

        for (int i = 0; i < assets.size(); i++) {
            EmergencyAsset asset = assets.get(i);
            if (asset == null || asset.getAssetType() != EmergencyAssetType.EMERGENCY_EXIT) {
                continue;
            }
            GeometryUtil.Point2D anchor = resolveAssetPoint(asset);
            if (anchor == null) {
                continue;
            }
            boolean alreadyCounted = false;
            for (ExitPortalDescriptor existing : portals) {
                if (Math.hypot(existing.x() - anchor.x(), existing.y() - anchor.y()) <= 2.0) {
                    alreadyCounted = true;
                    break;
                }
            }
            if (alreadyCounted) {
                continue;
            }
            double widthM = (asset.getMinWidthM() != null && asset.getMinWidthM() > 0.0)
                    ? asset.getMinWidthM()
                    : EmergencyAssetType.EMERGENCY_EXIT.getDefaultMinWidthM();
            boolean directlyDisabled = asset.getId() != null && disabledAssetIds.contains(asset.getId());
            portals.add(new ExitPortalDescriptor(
                    "ASSET-" + (asset.getId() != null ? asset.getId() : i),
                    "EMERGENCY_ASSET",
                    null,
                    null,
                    asset.getId(),
                    null,
                    asset.getLabel(),
                    true,
                    widthM,
                    null,
                    directlyDisabled,
                    anchor.x(),
                    anchor.y()
            ));
        }

        return portals;
    }

    private GraphNode findMatchingExitNode(MovementGraph graph, InfrastructureElement el) {
        for (GraphNode gn : graph.nodes()) {
            InfrastructureElement li = gn.entity().getLinkedInfra();
            if (li != null && (li == el || (li.getId() != null && li.getId().equals(el.getId())))) {
                return gn;
            }
        }
        double maxDist = Math.max(el.getWidthM(), el.getHeightM()) / 2.0 + 2.0;
        GraphNode best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (GraphNode gn : graph.nodes()) {
            if (!isAnyExitNode(gn.entity())) {
                continue;
            }
            double dist = Math.hypot(gn.entity().getX() - el.getCenterX(), gn.entity().getY() - el.getCenterY());
            if (dist <= maxDist && dist < bestDist) {
                bestDist = dist;
                best = gn;
            }
        }
        return best;
    }

    private boolean isAnyExitNode(PathwayNode node) {
        if (node.getNodeType() == PathwayNodeType.EXIT_GATE || node.getNodeType() == PathwayNodeType.EMERGENCY_EXIT) {
            return true;
        }
        InfrastructureElement li = node.getLinkedInfra();
        return li != null && li.isActive() && li.getElementType() != null && li.getElementType().isExitPortal();
    }

    private String resolveEndNodeLabel(MovementGraph graph, PathResult path) {
        if (path == null || path.nodeKeys().isEmpty()) {
            return null;
        }
        String lastKey = path.nodeKeys().get(path.nodeKeys().size() - 1);
        GraphNode gn = graph.node(lastKey);
        return gn != null ? gn.entity().getLabel() : lastKey;
    }

    private List<Long> resolveOrderedNodeIds(MovementGraph graph, List<String> nodeKeys) {
        List<Long> ids = new ArrayList<>();
        for (String key : nodeKeys) {
            GraphNode gn = graph.node(key);
            if (gn != null && gn.entity().getId() != null) {
                ids.add(gn.entity().getId());
            }
        }
        return List.copyOf(ids);
    }

    private List<Long> resolveOrderedEdgeIds(Map<String, GraphEdge> edgeByKey, List<String> edgeKeys) {
        List<Long> ids = new ArrayList<>();
        for (String key : edgeKeys) {
            GraphEdge ge = edgeByKey.get(key);
            if (ge != null && ge.entity().getId() != null) {
                ids.add(ge.entity().getId());
            }
        }
        return List.copyOf(ids);
    }

    private List<GeometryUtil.Point2D> stitchBackupPathPoints(MovementGraph graph,
                                                            Map<String, GraphEdge> edgeByKey,
                                                            PathResult path) {
        List<GeometryUtil.Point2D> points = new ArrayList<>();
        List<String> nodeKeys = path.nodeKeys();
        List<String> edgeKeys = path.edgeKeys();
        for (int i = 0; i < edgeKeys.size(); i++) {
            GraphEdge ge = edgeByKey.get(edgeKeys.get(i));
            if (ge == null) {
                continue;
            }
            String uKey = i < nodeKeys.size() ? nodeKeys.get(i) : ge.fromKey();
            List<GeometryUtil.Point2D> segPts = new ArrayList<>(extractEdgePoints(ge));
            if (!ge.fromKey().equals(uKey)) {
                Collections.reverse(segPts);
            }
            for (GeometryUtil.Point2D pt : segPts) {
                if (points.isEmpty()) {
                    points.add(pt);
                } else {
                    GeometryUtil.Point2D last = points.get(points.size() - 1);
                    if (Math.hypot(pt.x() - last.x(), pt.y() - last.y()) > 1e-4) {
                        points.add(pt);
                    }
                }
            }
        }
        if (points.isEmpty() && !nodeKeys.isEmpty()) {
            for (String nk : nodeKeys) {
                GraphNode gn = graph.node(nk);
                if (gn != null) {
                    points.add(new GeometryUtil.Point2D(gn.entity().getX(), gn.entity().getY()));
                }
            }
        }
        return List.copyOf(points);
    }

    private List<GeometryUtil.Point2D> extractEdgePoints(GraphEdge ge) {
        PathwayEdge edge = ge.entity();
        if (edge.getPolylineWkt() != null && !edge.getPolylineWkt().isBlank()) {
            try {
                LineString ls = geometryUtil.parseLineString(edge.getPolylineWkt());
                List<GeometryUtil.Point2D> pts = geometryUtil.extractLinePoints(ls);
                if (pts.size() >= 2) {
                    return pts;
                }
            } catch (IllegalArgumentException ignored) {
                // Fall back to endpoint chord
            }
        }
        return List.of(
                new GeometryUtil.Point2D(edge.getFromNode().getX(), edge.getFromNode().getY()),
                new GeometryUtil.Point2D(edge.getToNode().getX(), edge.getToNode().getY())
        );
    }

    private GeometryUtil.Point2D resolveAssetPoint(EmergencyAsset asset) {
        if (asset.getGeometryWkt() == null || asset.getGeometryWkt().isBlank()) {
            return null;
        }
        try {
            Geometry geom = geometryUtil.parseGeometry(asset.getGeometryWkt());
            if (geom instanceof Point pt) {
                return new GeometryUtil.Point2D(pt.getX(), pt.getY());
            }
            if (geom instanceof LineString line) {
                return geometryUtil.lineMidpoint(line);
            }
            return geometryUtil.interiorPoint(geom);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Geometry safeParsePolygonal(String wkt) {
        if (wkt == null || wkt.isBlank()) {
            return null;
        }
        try {
            return geometryUtil.parsePolygonal(wkt);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private int statusSortRank(ZoneOutageStatus status) {
        return switch (status) {
            case NEWLY_ISOLATED -> 1;
            case REROUTED -> 2;
            case ALREADY_ISOLATED -> 3;
            case UNAFFECTED -> 4;
        };
    }

    private Set<Long> safeIdSet(Set<Long> input) {
        if (input == null || input.isEmpty()) {
            return Set.of();
        }
        Set<Long> out = new LinkedHashSet<>();
        for (Long id : input) {
            if (Objects.nonNull(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private List<Long> sortedIds(Set<Long> ids) {
        return ids.stream().sorted().toList();
    }
}
