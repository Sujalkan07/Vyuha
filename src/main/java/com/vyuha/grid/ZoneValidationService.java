package com.vyuha.grid;

import com.vyuha.common.GeometryUtil;
import com.vyuha.venue.AreaBreakdownService;
import com.vyuha.venue.ExclusionArea;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;

/**
 * Pure-Java JTS zone integrity analyzer.
 * Detects unassigned usable space, zone overlaps (> 0.1 m²), out-of-bounds areas,
 * and cut-off / isolated walkable zones without > 1.0 m walkable adjacency.
 */
@Service
public class ZoneValidationService {

    public static final double OVERLAP_THRESHOLD_SQM = 0.1;
    public static final double SHARED_EDGE_MIN_METERS = 1.0;
    public static final double UNASSIGNED_THRESHOLD_SQM = 0.1;

    private final GeometryUtil geometryUtil;
    private final AreaBreakdownService areaBreakdownService;

    public ZoneValidationService(GeometryUtil geometryUtil, AreaBreakdownService areaBreakdownService) {
        this.geometryUtil = geometryUtil;
        this.areaBreakdownService = areaBreakdownService;
    }

    public GridDtos.ZoneValidationResult validate(Polygon boundary,
                                                  List<ExclusionArea> exclusions,
                                                  List<FunctionalZone> zones) {
        if (boundary == null || boundary.isEmpty()) {
            GridDtos.ZoneCoverageSummary emptySummary = new GridDtos.ZoneCoverageSummary(
                    false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    zones != null ? zones.size() : 0, 0
            );
            return new GridDtos.ZoneValidationResult(
                    emptySummary,
                    List.of(new GridDtos.ZoneFindingDto(
                            "BOUNDARY_MISSING",
                            "INFO",
                            "Outer venue boundary required",
                            "Draw the outer venue boundary in Site Plan mode before generating a grid or assigning zones.",
                            "0.00 m²",
                            null,
                            null,
                            List.of()
                    )),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        double grossAreaSqm = geometryUtil.polygonalArea(boundary);
        Geometry usableGeom = areaBreakdownService.computeUsableGeometry(boundary, exclusions);
        double totalUsableSqm = Math.max(0.0, geometryUtil.polygonalArea(usableGeom));
        Geometry walkableFloor = computeWalkableFloor(boundary, exclusions);

        List<FunctionalZone> safeZones = zones != null ? zones : List.of();
        List<Geometry> clippedZones = new ArrayList<>(safeZones.size());
        List<Geometry> effectiveWalkableZones = new ArrayList<>(safeZones.size());
        List<Double> perZoneClippedAreas = new ArrayList<>(safeZones.size());
        List<Double> perZoneUsableAreas = new ArrayList<>(safeZones.size());
        List<GridDtos.ZoneFindingDto> findings = new ArrayList<>();

        for (FunctionalZone zone : safeZones) {
            Geometry rawGeom = geometryUtil.parsePolygonal(zone.getPolygonWkt());
            double rawArea = geometryUtil.polygonalArea(rawGeom);
            Geometry clipped = boundary.intersection(rawGeom);
            double clippedArea = geometryUtil.polygonalArea(clipped);
            double outsideArea = Math.max(0.0, rawArea - clippedArea);

            Geometry zoneUsable = usableGeom.isEmpty() || clipped.isEmpty()
                    ? geometryUtil.getGeometryFactory().createGeometryCollection()
                    : clipped.intersection(usableGeom);
            double zoneUsableArea = Math.max(0.0, geometryUtil.polygonalArea(zoneUsable));

            Geometry effWalkable = zone.getZoneType().isWalkable() && !clipped.isEmpty() && !walkableFloor.isEmpty()
                    ? clipped.intersection(walkableFloor)
                    : geometryUtil.getGeometryFactory().createGeometryCollection();

            clippedZones.add(clipped);
            effectiveWalkableZones.add(effWalkable);
            perZoneClippedAreas.add(clippedArea);
            perZoneUsableAreas.add(zoneUsableArea);

            if (outsideArea > UNASSIGNED_THRESHOLD_SQM) {
                GeometryUtil.Point2D pt = geometryUtil.interiorPoint(rawGeom);
                findings.add(new GridDtos.ZoneFindingDto(
                        "ZONE_OUTSIDE_BOUNDARY",
                        "LOW",
                        "Zone extends outside venue boundary: " + zone.getZoneCode() + " (" + zone.getName() + ")",
                        "Part of this zone falls outside the outer venue boundary and is excluded from usable area.",
                        String.format(Locale.US, "%.2f m² outside", outsideArea),
                        pt != null ? pt.x() : null,
                        pt != null ? pt.y() : null,
                        zone.getId() != null ? List.of(zone.getId()) : List.of()
                ));
            }
        }

        // 1. Zone Overlap Detection (> 0.1 m²)
        List<Geometry> overlapGeometries = new ArrayList<>();
        for (int i = 0; i < safeZones.size(); i++) {
            for (int j = i + 1; j < safeZones.size(); j++) {
                Geometry gi = clippedZones.get(i);
                Geometry gj = clippedZones.get(j);
                if (gi.isEmpty() || gj.isEmpty()
                        || !gi.getEnvelopeInternal().intersects(gj.getEnvelopeInternal())) {
                    continue;
                }
                Geometry overlap = gi.intersection(gj);
                double overlapArea = geometryUtil.polygonalArea(overlap);
                if (overlapArea > OVERLAP_THRESHOLD_SQM) {
                    overlapGeometries.add(overlap);
                    FunctionalZone zi = safeZones.get(i);
                    FunctionalZone zj = safeZones.get(j);
                    GeometryUtil.Point2D pt = geometryUtil.interiorPoint(overlap);
                    List<Long> ids = new ArrayList<>(2);
                    if (zi.getId() != null) ids.add(zi.getId());
                    if (zj.getId() != null) ids.add(zj.getId());
                    findings.add(new GridDtos.ZoneFindingDto(
                            "ZONE_OVERLAP",
                            "HIGH",
                            "Overlapping zones: " + zi.getZoneCode() + " and " + zj.getZoneCode(),
                            "Functional zones overlap and cannot both occupy the same floor space without splitting or trimming.",
                            String.format(Locale.US, "%.2f m² overlap", overlapArea),
                            pt != null ? pt.x() : null,
                            pt != null ? pt.y() : null,
                            List.copyOf(ids)
                    ));
                }
            }
        }
        double totalOverlapAreaSqm = geometryUtil.polygonalArea(geometryUtil.unionPolygons(overlapGeometries));

        // 2. Unassigned Space Detection
        Geometry allZonesClippedUnion = geometryUtil.unionPolygons(clippedZones);
        double assignedUsableSqm = usableGeom.isEmpty() || allZonesClippedUnion.isEmpty()
                ? 0.0
                : Math.min(totalUsableSqm, geometryUtil.polygonalArea(usableGeom.intersection(allZonesClippedUnion)));
        Geometry unassignedUsableGeom = allZonesClippedUnion.isEmpty()
                ? usableGeom
                : usableGeom.difference(allZonesClippedUnion);
        double unassignedUsableSqm = Math.max(0.0, geometryUtil.polygonalArea(unassignedUsableGeom));
        double assignedPct = totalUsableSqm > 0.0 ? Math.min(100.0, (assignedUsableSqm / totalUsableSqm) * 100.0) : 0.0;

        if (unassignedUsableSqm > UNASSIGNED_THRESHOLD_SQM) {
            GeometryUtil.Point2D pt = geometryUtil.interiorPoint(unassignedUsableGeom);
            String sev = safeZones.isEmpty() ? "INFO" : "LOW";
            String title = safeZones.isEmpty()
                    ? "No functional zones assigned yet"
                    : "Unassigned usable gathering space detected";
            findings.add(new GridDtos.ZoneFindingDto(
                    "UNASSIGNED_SPACE",
                    sev,
                    title,
                    "Net usable space inside the boundary is not yet assigned to any functional zone.",
                    String.format(Locale.US, "%.2f m² unassigned (%.1f%%)",
                            unassignedUsableSqm,
                            totalUsableSqm > 0.0 ? (unassignedUsableSqm / totalUsableSqm) * 100.0 : 0.0),
                    pt != null ? pt.x() : null,
                    pt != null ? pt.y() : null,
                    List.of()
            ));
        }

        // 3. Cut-Off / Isolated Zone Detection
        boolean[] isolatedFlags = detectIsolatedWalkableZones(
                boundary, walkableFloor, allZonesClippedUnion, safeZones, effectiveWalkableZones);

        List<Long> isolatedZoneIds = new ArrayList<>();
        int isolatedCount = 0;
        for (int i = 0; i < safeZones.size(); i++) {
            if (!isolatedFlags[i]) {
                continue;
            }
            isolatedCount++;
            FunctionalZone zone = safeZones.get(i);
            if (zone.getId() != null) {
                isolatedZoneIds.add(zone.getId());
            }
            String sev = (zone.getZoneType() == ZoneType.AUDIENCE || zone.getZoneType() == ZoneType.MEDICAL)
                    ? "CRITICAL"
                    : "HIGH";
            GeometryUtil.Point2D pt = geometryUtil.interiorPoint(
                    clippedZones.get(i).isEmpty()
                            ? geometryUtil.parsePolygonal(zone.getPolygonWkt())
                            : clippedZones.get(i));
            findings.add(new GridDtos.ZoneFindingDto(
                    "ISOLATED_ZONE",
                    sev,
                    "Cut-off / isolated zone: " + zone.getZoneCode() + " (" + zone.getName() + ")",
                    "Zone is encircled by restricted areas or exclusions without a walkable connection wider than 1.0 m.",
                    String.format(Locale.US, "%.2f m² cut off", perZoneClippedAreas.get(i)),
                    pt != null ? pt.x() : null,
                    pt != null ? pt.y() : null,
                    zone.getId() != null ? List.of(zone.getId()) : List.of()
            ));
        }

        GridDtos.ZoneCoverageSummary coverage = new GridDtos.ZoneCoverageSummary(
                true,
                grossAreaSqm,
                totalUsableSqm,
                assignedUsableSqm,
                unassignedUsableSqm,
                assignedPct,
                totalOverlapAreaSqm,
                safeZones.size(),
                isolatedCount
        );

        return new GridDtos.ZoneValidationResult(
                coverage,
                List.copyOf(findings),
                List.copyOf(isolatedZoneIds),
                List.copyOf(perZoneClippedAreas),
                List.copyOf(perZoneUsableAreas)
        );
    }

    private Geometry computeWalkableFloor(Polygon boundary, List<ExclusionArea> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) {
            return boundary;
        }
        List<Geometry> solidClipped = new ArrayList<>();
        for (ExclusionArea ex : exclusions) {
            if (ex == null || ex.getPolygonWkt() == null || ex.getPolygonWkt().isBlank()) {
                continue;
            }
            if (ex.getExclusionType() != null && ex.getExclusionType().isCorridor()) {
                continue; // Reserved walkways/corridors remain walkable floor
            }
            Polygon exPoly = geometryUtil.parsePolygon(ex.getPolygonWkt());
            Geometry clipped = boundary.intersection(exPoly);
            if (geometryUtil.polygonalArea(clipped) > 1e-9) {
                solidClipped.add(clipped);
            }
        }
        Geometry solidUnion = geometryUtil.unionPolygons(solidClipped);
        return solidUnion.isEmpty() ? boundary : boundary.difference(solidUnion);
    }

    private boolean[] detectIsolatedWalkableZones(Polygon boundary,
                                                  Geometry walkableFloor,
                                                  Geometry allZonesClippedUnion,
                                                  List<FunctionalZone> zones,
                                                  List<Geometry> effectiveWalkableZones) {
        boolean[] isolated = new boolean[zones.size()];
        if (zones.isEmpty()) {
            return isolated;
        }

        record GraphNode(int zoneIndex, Geometry geometry, boolean accessAnchor, boolean touchesOuterBoundary) {
        }

        List<GraphNode> nodes = new ArrayList<>();
        int[] zoneToNodeIndex = new int[zones.size()];
        for (int i = 0; i < zones.size(); i++) {
            zoneToNodeIndex[i] = -1;
            FunctionalZone zone = zones.get(i);
            if (!zone.getZoneType().isWalkable()) {
                continue;
            }
            Geometry eff = effectiveWalkableZones.get(i);
            if (geometryUtil.polygonalArea(eff) < GeometryUtil.MIN_POLYGON_AREA_SQM) {
                isolated[i] = true;
                continue;
            }
            boolean touchesOuter = geometryUtil.sharedBoundaryLength(eff, boundary) > SHARED_EDGE_MIN_METERS;
            zoneToNodeIndex[i] = nodes.size();
            nodes.add(new GraphNode(i, eff, zone.getZoneType().isAccessAnchor(), touchesOuter));
        }

        if (nodes.isEmpty()) {
            return isolated;
        }

        // Include open unassigned walkable pockets/corridors as walkable bridges
        Geometry unassignedWalkable = allZonesClippedUnion.isEmpty()
                ? walkableFloor
                : walkableFloor.difference(allZonesClippedUnion);
        for (Polygon openPiece : geometryUtil.extractPolygons(unassignedWalkable)) {
            if (openPiece.getArea() > UNASSIGNED_THRESHOLD_SQM) {
                boolean touchesOuter = geometryUtil.sharedBoundaryLength(openPiece, boundary) > SHARED_EDGE_MIN_METERS;
                nodes.add(new GraphNode(-1, openPiece, false, touchesOuter));
            }
        }

        int n = nodes.size();
        List<List<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            Geometry gi = nodes.get(i).geometry();
            for (int j = i + 1; j < n; j++) {
                Geometry gj = nodes.get(j).geometry();
                if (!gi.getEnvelopeInternal().intersects(gj.getEnvelopeInternal())) {
                    continue;
                }
                double overlap = geometryUtil.polygonalArea(gi.intersection(gj));
                boolean connected = overlap > OVERLAP_THRESHOLD_SQM
                        || geometryUtil.sharedBoundaryLength(gi, gj) > SHARED_EDGE_MIN_METERS;
                if (connected) {
                    adj.get(i).add(j);
                    adj.get(j).add(i);
                }
            }
        }

        boolean hasAccessAnchor = nodes.stream().anyMatch(GraphNode::accessAnchor);
        boolean[] reachable = new boolean[n];

        if (hasAccessAnchor) {
            Deque<Integer> queue = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                if (nodes.get(i).accessAnchor()) {
                    reachable[i] = true;
                    queue.add(i);
                }
            }
            bfs(queue, adj, reachable);
        } else {
            // No Entry/Exit/Emergency Corridor anchor drawn yet: pick primary outer-connected component
            boolean[] visited = new boolean[n];
            List<Integer> bestComponent = List.of();
            boolean bestTouchesOuter = false;
            double bestArea = -1.0;

            for (int i = 0; i < n; i++) {
                if (visited[i]) {
                    continue;
                }
                List<Integer> component = new ArrayList<>();
                Deque<Integer> queue = new ArrayDeque<>();
                visited[i] = true;
                queue.add(i);
                while (!queue.isEmpty()) {
                    int curr = queue.poll();
                    component.add(curr);
                    for (int next : adj.get(curr)) {
                        if (!visited[next]) {
                            visited[next] = true;
                            queue.add(next);
                        }
                    }
                }

                boolean touchesOuter = false;
                double totalArea = 0.0;
                for (int idx : component) {
                    GraphNode node = nodes.get(idx);
                    if (node.touchesOuterBoundary()) {
                        touchesOuter = true;
                    }
                    totalArea += geometryUtil.polygonalArea(node.geometry());
                }

                boolean better = (touchesOuter && !bestTouchesOuter)
                        || (touchesOuter == bestTouchesOuter && totalArea > bestArea);
                if (better) {
                    bestComponent = component;
                    bestTouchesOuter = touchesOuter;
                    bestArea = totalArea;
                }
            }
            Set<Integer> primarySet = new HashSet<>(bestComponent);
            for (int idx : primarySet) {
                reachable[idx] = true;
            }
        }

        for (int zIdx = 0; zIdx < zones.size(); zIdx++) {
            int nodeIdx = zoneToNodeIndex[zIdx];
            if (nodeIdx >= 0 && !reachable[nodeIdx]) {
                isolated[zIdx] = true;
            }
        }
        return isolated;
    }

    private void bfs(Deque<Integer> queue, List<List<Integer>> adj, boolean[] visited) {
        while (!queue.isEmpty()) {
            int curr = queue.poll();
            for (int next : adj.get(curr)) {
                if (!visited[next]) {
                    visited[next] = true;
                    queue.add(next);
                }
            }
        }
    }
}
