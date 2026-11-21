package com.vyuha.emergency;

import com.vyuha.common.GeometryUtil;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.NetworkLayer;
import com.vyuha.network.PathwayEdge;
import com.vyuha.network.PathwayNode;
import com.vyuha.network.PathwayNodeType;
import com.vyuha.venue.ExclusionArea;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;

/**
 * Pure-Java emergency access and preparedness validation engine.
 * Checks ambulance/fire routes, infrastructure lanes, and emergency pathway edges
 * against the 3.50 m minimum clear-width rule and obstacle-free geometry requirements.
 */
@Service
public class EmergencyValidationService {

    public static final double MIN_EMERGENCY_VEHICLE_WIDTH_M = 3.5;
    public static final double OVERLAP_THRESHOLD_SQM = 0.01;
    public static final double ROUTE_OVERLAP_THRESHOLD_SQM = 0.05;

    private final GeometryUtil geometryUtil;

    public EmergencyValidationService(GeometryUtil geometryUtil) {
        this.geometryUtil = geometryUtil;
    }

    private record PreparedObstacle(
            String label,
            InfrastructureType elementType,
            Polygon geometry
    ) {
    }

    public EmergencyDtos.EmergencyValidationSummary validate(Polygon boundary,
                                                             List<ExclusionArea> exclusions,
                                                             List<InfrastructureElement> elements,
                                                             List<PathwayNode> nodes,
                                                             List<PathwayEdge> edges,
                                                             List<EmergencyAsset> assets,
                                                             List<EmergencyContact> contacts) {
        List<ExclusionArea> safeExclusions = exclusions != null ? exclusions : List.of();
        List<InfrastructureElement> safeElements = elements != null ? elements : List.of();
        List<PathwayNode> safeNodes = nodes != null ? nodes : List.of();
        List<PathwayEdge> safeEdges = edges != null ? edges : List.of();
        List<EmergencyAsset> safeAssets = assets != null ? assets : List.of();
        List<EmergencyContact> safeContacts = contacts != null ? contacts : List.of();

        List<PreparedObstacle> elementObstacles = prepareElementObstacles(safeElements);
        List<PreparedObstacle> exclusionObstacles = prepareExclusionObstacles(safeExclusions);

        List<EmergencyDtos.VehicleRouteStatusDto> routeStatuses = new ArrayList<>();
        List<EmergencyDtos.EmergencyValidationFinding> findings = new ArrayList<>();

        boolean hasAmbulanceAccess = false;
        boolean hasFireAccess = false;
        int assemblyPointCount = 0;

        List<Point> countedExitPoints = new ArrayList<>();
        int emergencyExitCount = 0;

        // 1. Validate EmergencyAsset items
        for (int i = 0; i < safeAssets.size(); i++) {
            EmergencyAsset asset = safeAssets.get(i);
            if (asset == null || asset.getAssetType() == null) {
                continue;
            }
            Geometry geom = safeParseGeometry(asset.getGeometryWkt());
            if (geom == null) {
                continue;
            }
            String assetKey = asset.getId() != null ? String.valueOf(asset.getId()) : "IDX-" + i;
            EmergencyAssetType type = asset.getAssetType();

            if (type == EmergencyAssetType.AMBULANCE_ROUTE || type == EmergencyAssetType.FIRE_ROUTE) {
                if (type == EmergencyAssetType.AMBULANCE_ROUTE) {
                    hasAmbulanceAccess = true;
                } else {
                    hasFireAccess = true;
                }
                validateVehicleAssetRoute(asset, assetKey, geom, elementObstacles, exclusionObstacles, routeStatuses, findings);
            } else if (type == EmergencyAssetType.ASSEMBLY_POINT) {
                assemblyPointCount++;
                if (boundary != null && !boundary.isEmpty() && boundary.contains(geom)) {
                    GeometryUtil.Point2D anchor = resolveAnchorPoint(geom);
                    findings.add(new EmergencyDtos.EmergencyValidationFinding(
                            "ASSEMBLY_POINT_INSIDE_VENUE:ASSET-" + assetKey,
                            FindingSeverity.LOW,
                            asset.getLabel() + " lies inside the venue outer boundary",
                            "External assembly points are typically positioned outside the main venue perimeter to prevent crowding near exits.",
                            "Contained inside boundary",
                            "Verify external muster clearance outside site perimeter",
                            anchor != null ? anchor.x() : null,
                            anchor != null ? anchor.y() : null,
                            "EMERGENCY_ASSET",
                            asset.getId()
                    ));
                }
            }
        }

        // 2. Validate Phase 5 InfrastructureElement emergency lanes
        for (int i = 0; i < safeElements.size(); i++) {
            InfrastructureElement el = safeElements.get(i);
            if (el == null || !el.isActive() || el.getElementType() == null) {
                continue;
            }
            if (el.getElementType() == InfrastructureType.EMERGENCY_EXIT) {
                emergencyExitCount++;
                countedExitPoints.add(geometryUtil.buildPoint(el.getCenterX(), el.getCenterY()));
            }
            if (!el.getElementType().isEmergencyLane()) {
                continue;
            }
            if (el.getElementType() == InfrastructureType.AMBULANCE_LANE) {
                hasAmbulanceAccess = true;
            } else if (el.getElementType() == InfrastructureType.FIRE_ACCESS_LANE) {
                hasFireAccess = true;
            }
            validateInfrastructureLane(el, i, elementObstacles, exclusionObstacles, routeStatuses, findings);
        }

        // 3. Validate Phase 6 EMERGENCY_ACCESS PathwayEdges
        for (int i = 0; i < safeEdges.size(); i++) {
            PathwayEdge edge = safeEdges.get(i);
            if (edge == null || !edge.isActive() || edge.getNetworkLayer() != NetworkLayer.EMERGENCY_ACCESS) {
                continue;
            }
            validateEmergencyPathwayEdge(edge, i, elementObstacles, exclusionObstacles, routeStatuses, findings);
        }

        // 4. Count unique emergency exits across Phase 6 nodes & Phase 8 assets
        for (PathwayNode node : safeNodes) {
            if (node == null || node.getNodeType() != PathwayNodeType.EMERGENCY_EXIT) {
                continue;
            }
            boolean linkedToInfra = node.getLinkedInfra() != null
                    && node.getLinkedInfra().isActive()
                    && node.getLinkedInfra().getElementType() == InfrastructureType.EMERGENCY_EXIT;
            if (linkedToInfra) {
                continue;
            }
            Point pt = geometryUtil.buildPoint(node.getX(), node.getY());
            if (!isNearAnyPoint(pt, countedExitPoints, 2.0)) {
                emergencyExitCount++;
                countedExitPoints.add(pt);
            }
        }

        for (EmergencyAsset asset : safeAssets) {
            if (asset == null || asset.getAssetType() != EmergencyAssetType.EMERGENCY_EXIT) {
                continue;
            }
            Geometry geom = safeParseGeometry(asset.getGeometryWkt());
            GeometryUtil.Point2D anchor = resolveAnchorPoint(geom);
            if (anchor == null) {
                emergencyExitCount++;
                continue;
            }
            Point pt = geometryUtil.buildPoint(anchor.x(), anchor.y());
            if (!isNearAnyPoint(pt, countedExitPoints, 2.0)) {
                emergencyExitCount++;
                countedExitPoints.add(pt);
            }
        }

        // 5. Preparedness completeness checks
        if (emergencyExitCount == 0) {
            findings.add(new EmergencyDtos.EmergencyValidationFinding(
                    "NO_EMERGENCY_EXIT",
                    FindingSeverity.HIGH,
                    "No dedicated emergency exit designated",
                    "Designate at least one dedicated Emergency Exit in Infrastructure or the Emergency Workspace.",
                    "0 emergency exits",
                    "At least 1 dedicated emergency exit required for egress contingency",
                    null,
                    null,
                    null,
                    null
            ));
        }

        if (assemblyPointCount == 0) {
            findings.add(new EmergencyDtos.EmergencyValidationFinding(
                    "NO_ASSEMBLY_POINT",
                    FindingSeverity.LOW,
                    "No external assembly point designated",
                    "Mark at least one external muster / assembly point with capacity notes for emergency staging.",
                    "0 assembly points",
                    "Recommended: >= 1 designated assembly point",
                    null,
                    null,
                    null,
                    null
            ));
        }

        if (!hasAmbulanceAccess) {
            findings.add(new EmergencyDtos.EmergencyValidationFinding(
                    "MISSING_AMBULANCE_ACCESS",
                    FindingSeverity.HIGH,
                    "No ambulance access route or lane defined",
                    "Draw an Ambulance Route or place an Ambulance Access Lane with >= 3.50 m clear width.",
                    "0 ambulance routes",
                    "Required: >= 1 unobstructed ambulance route (>= 3.50 m)",
                    null,
                    null,
                    null,
                    null
            ));
        }

        if (!hasFireAccess) {
            findings.add(new EmergencyDtos.EmergencyValidationFinding(
                    "MISSING_FIRE_ACCESS",
                    FindingSeverity.HIGH,
                    "No fire brigade access route or lane defined",
                    "Draw a Fire Access Route or place a Fire Access Lane with >= 3.50 m clear width.",
                    "0 fire access routes",
                    "Required: >= 1 unobstructed fire access route (>= 3.50 m)",
                    null,
                    null,
                    null,
                    null
            ));
        }

        if (safeContacts.isEmpty()) {
            findings.add(new EmergencyDtos.EmergencyValidationFinding(
                    "NO_EMERGENCY_CONTACTS",
                    FindingSeverity.HIGH,
                    "Emergency contacts directory is empty",
                    "Record prioritized external contacts (Police, Fire Brigade, Ambulance, Incident Commander) for statutory coordination.",
                    "0 contacts recorded",
                    "Recommended: prioritized local emergency agency contacts",
                    null,
                    null,
                    null,
                    null
            ));
        }

        findings.sort(Comparator
                .comparingInt((EmergencyDtos.EmergencyValidationFinding f) -> f.severity().sortRank())
                .thenComparing(EmergencyDtos.EmergencyValidationFinding::code));

        int compliantRoutes = 0;
        int narrowRoutes = 0;
        int obstructedRoutes = 0;
        for (EmergencyDtos.VehicleRouteStatusDto status : routeStatuses) {
            if (status.compliant()) {
                compliantRoutes++;
            }
            if (!status.widthCompliant()) {
                narrowRoutes++;
            }
            if (status.obstacleCount() > 0) {
                obstructedRoutes++;
            }
        }
        boolean hasCritical = findings.stream().anyMatch(f -> f.severity() == FindingSeverity.CRITICAL);

        return new EmergencyDtos.EmergencyValidationSummary(
                routeStatuses.size(),
                compliantRoutes,
                narrowRoutes,
                obstructedRoutes,
                emergencyExitCount,
                assemblyPointCount,
                safeContacts.size(),
                hasCritical,
                List.copyOf(routeStatuses),
                List.copyOf(findings)
        );
    }

    private void validateVehicleAssetRoute(EmergencyAsset asset,
                                           String assetKey,
                                           Geometry geom,
                                           List<PreparedObstacle> elementObstacles,
                                           List<PreparedObstacle> exclusionObstacles,
                                           List<EmergencyDtos.VehicleRouteStatusDto> routeStatuses,
                                           List<EmergencyDtos.EmergencyValidationFinding> findings) {
        Double clearWidthM;
        Geometry corridorGeom;
        GeometryUtil.Point2D anchor = resolveAnchorPoint(geom);

        if (geom instanceof LineString line) {
            clearWidthM = asset.getMinWidthM() != null ? geometryUtil.round2(asset.getMinWidthM()) : null;
            double effectiveBufferHalf = (clearWidthM != null && clearWidthM > 0.5)
                    ? clearWidthM / 2.0
                    : MIN_EMERGENCY_VEHICLE_WIDTH_M / 2.0;
            corridorGeom = geometryUtil.bufferLineFlat(line, effectiveBufferHalf);
        } else {
            double geomMinWidth = new MinimumDiameter(geom).getLength();
            if (asset.getMinWidthM() != null && asset.getMinWidthM() > 0.0) {
                clearWidthM = geometryUtil.round2(Math.min(asset.getMinWidthM(), geomMinWidth));
            } else {
                clearWidthM = geometryUtil.round2(geomMinWidth);
            }
            corridorGeom = geom;
        }

        boolean widthCompliant = clearWidthM != null && clearWidthM >= MIN_EMERGENCY_VEHICLE_WIDTH_M - 1e-6;
        if (!widthCompliant) {
            String measured = clearWidthM != null
                    ? String.format(Locale.US, "%.2f m clear width (min %.2f m)", clearWidthM, MIN_EMERGENCY_VEHICLE_WIDTH_M)
                    : "Width not specified (min 3.50 m)";
            findings.add(new EmergencyDtos.EmergencyValidationFinding(
                    "EMERGENCY_ROUTE_NARROW:ASSET-" + assetKey,
                    FindingSeverity.CRITICAL,
                    asset.getLabel() + " is narrower than 3.50 m minimum",
                    "Emergency vehicle routes must maintain at least 3.50 m unobstructed width for ambulance or fire apparatus access.",
                    measured,
                    String.format(Locale.US, "Minimum emergency vehicle width: %.2f m", MIN_EMERGENCY_VEHICLE_WIDTH_M),
                    anchor != null ? anchor.x() : null,
                    anchor != null ? anchor.y() : null,
                    "EMERGENCY_ASSET",
                    asset.getId()
            ));
        }

        Set<String> obstacleLabels = new LinkedHashSet<>();
        GeometryUtil.Point2D firstConflictPt = collectObstacles(
                corridorGeom,
                ROUTE_OVERLAP_THRESHOLD_SQM,
                elementObstacles,
                exclusionObstacles,
                false,
                obstacleLabels
        );

        if (!obstacleLabels.isEmpty()) {
            GeometryUtil.Point2D pt = firstConflictPt != null ? firstConflictPt : anchor;
            findings.add(new EmergencyDtos.EmergencyValidationFinding(
                    "EMERGENCY_ROUTE_OBSTRUCTED:ASSET-" + assetKey,
                    FindingSeverity.CRITICAL,
                    asset.getLabel() + " is obstructed by " + String.join(", ", obstacleLabels),
                    "Physical structure or solid exclusion intrudes into the designated emergency vehicle route clearance envelope.",
                    obstacleLabels.size() + " obstruction(s): " + String.join(", ", obstacleLabels),
                    "Emergency vehicle routes must maintain 0 obstructions",
                    pt != null ? pt.x() : null,
                    pt != null ? pt.y() : null,
                    "EMERGENCY_ASSET",
                    asset.getId()
            ));
        }

        routeStatuses.add(new EmergencyDtos.VehicleRouteStatusDto(
                "ASSET-" + assetKey,
                "EMERGENCY_ASSET",
                asset.getId(),
                asset.getLabel(),
                asset.getAssetType().getDisplayName(),
                clearWidthM,
                MIN_EMERGENCY_VEHICLE_WIDTH_M,
                widthCompliant,
                obstacleLabels.size(),
                List.copyOf(obstacleLabels),
                widthCompliant && obstacleLabels.isEmpty(),
                anchor != null ? anchor.x() : null,
                anchor != null ? anchor.y() : null
        ));
    }

    private void validateInfrastructureLane(InfrastructureElement el,
                                            int index,
                                            List<PreparedObstacle> elementObstacles,
                                            List<PreparedObstacle> exclusionObstacles,
                                            List<EmergencyDtos.VehicleRouteStatusDto> routeStatuses,
                                            List<EmergencyDtos.EmergencyValidationFinding> findings) {
        String key = el.getId() != null ? String.valueOf(el.getId()) : "IDX-" + index;
        double clearWidthM = geometryUtil.round2(Math.min(el.getWidthM(), el.getHeightM()));
        boolean widthCompliant = clearWidthM >= MIN_EMERGENCY_VEHICLE_WIDTH_M - 1e-6;
        Polygon footprint = resolveFootprint(el);

        if (!widthCompliant) {
            findings.add(new EmergencyDtos.EmergencyValidationFinding(
                    "EMERGENCY_LANE_NARROW:INFRA-" + key,
                    FindingSeverity.CRITICAL,
                    el.getLabel() + " width is below 3.50 m minimum",
                    "Placed emergency vehicle access lane is narrower than the 3.50 m clear-width requirement.",
                    String.format(Locale.US, "%.2f m lane width (min %.2f m)", clearWidthM, MIN_EMERGENCY_VEHICLE_WIDTH_M),
                    String.format(Locale.US, "Minimum emergency vehicle width: %.2f m", MIN_EMERGENCY_VEHICLE_WIDTH_M),
                    el.getCenterX(),
                    el.getCenterY(),
                    "INFRASTRUCTURE_LANE",
                    el.getId()
            ));
        }

        Set<String> obstacleLabels = new LinkedHashSet<>();
        GeometryUtil.Point2D firstConflictPt = collectObstacles(
                footprint,
                OVERLAP_THRESHOLD_SQM,
                elementObstacles,
                exclusionObstacles,
                false,
                obstacleLabels
        );

        if (!obstacleLabels.isEmpty()) {
            findings.add(new EmergencyDtos.EmergencyValidationFinding(
                    "EMERGENCY_LANE_OBSTRUCTED:INFRA-" + key,
                    FindingSeverity.CRITICAL,
                    el.getLabel() + " has obstacle intrusion (" + String.join(", ", obstacleLabels) + ")",
                    "Physical structure or solid site exclusion overlaps the placed emergency access lane.",
                    obstacleLabels.size() + " obstruction(s): " + String.join(", ", obstacleLabels),
                    "Emergency access lanes must remain 100% unobstructed",
                    firstConflictPt != null ? firstConflictPt.x() : el.getCenterX(),
                    firstConflictPt != null ? firstConflictPt.y() : el.getCenterY(),
                    "INFRASTRUCTURE_LANE",
                    el.getId()
            ));
        }

        routeStatuses.add(new EmergencyDtos.VehicleRouteStatusDto(
                "INFRA-" + key,
                "INFRASTRUCTURE_LANE",
                el.getId(),
                el.getLabel(),
                el.getElementType().getDisplayName(),
                clearWidthM,
                MIN_EMERGENCY_VEHICLE_WIDTH_M,
                widthCompliant,
                obstacleLabels.size(),
                List.copyOf(obstacleLabels),
                widthCompliant && obstacleLabels.isEmpty(),
                el.getCenterX(),
                el.getCenterY()
        ));
    }

    private void validateEmergencyPathwayEdge(PathwayEdge edge,
                                              int index,
                                              List<PreparedObstacle> elementObstacles,
                                              List<PreparedObstacle> exclusionObstacles,
                                              List<EmergencyDtos.VehicleRouteStatusDto> routeStatuses,
                                              List<EmergencyDtos.EmergencyValidationFinding> findings) {
        String key = edge.getId() != null ? String.valueOf(edge.getId()) : "IDX-" + index;
        String edgeLabel = formatEdgeLabel(edge);
        LineString line = resolveEdgeLine(edge);
        GeometryUtil.Point2D mid = line != null ? geometryUtil.lineMidpoint(line) : null;
        double clearWidthM = geometryUtil.round2(edge.getWidthM());
        boolean widthCompliant = clearWidthM >= MIN_EMERGENCY_VEHICLE_WIDTH_M - 1e-6;

        if (!widthCompliant) {
            findings.add(new EmergencyDtos.EmergencyValidationFinding(
                    "EMERGENCY_CORRIDOR_NARROW:EDGE-" + key,
                    FindingSeverity.CRITICAL,
                    edgeLabel + " is narrower than 3.50 m emergency corridor minimum",
                    "Emergency-access pathway edge falls below the 3.50 m minimum clear width.",
                    String.format(Locale.US, "%.2f m corridor width (min %.2f m)", clearWidthM, MIN_EMERGENCY_VEHICLE_WIDTH_M),
                    String.format(Locale.US, "Minimum emergency access width: %.2f m", MIN_EMERGENCY_VEHICLE_WIDTH_M),
                    mid != null ? mid.x() : null,
                    mid != null ? mid.y() : null,
                    "PATHWAY_EDGE",
                    edge.getId()
            ));
        }

        Set<String> obstacleLabels = new LinkedHashSet<>();
        GeometryUtil.Point2D firstConflictPt = null;
        if (line != null) {
            double halfW = Math.max(0.5, edge.getWidthM()) / 2.0;
            Geometry corridorGeom = geometryUtil.bufferLineFlat(line, halfW);
            firstConflictPt = collectObstacles(
                    corridorGeom,
                    ROUTE_OVERLAP_THRESHOLD_SQM,
                    elementObstacles,
                    exclusionObstacles,
                    true,
                    obstacleLabels
            );
        }

        if (!obstacleLabels.isEmpty()) {
            GeometryUtil.Point2D pt = firstConflictPt != null ? firstConflictPt : mid;
            findings.add(new EmergencyDtos.EmergencyValidationFinding(
                    "EMERGENCY_CORRIDOR_OBSTRUCTED:EDGE-" + key,
                    FindingSeverity.CRITICAL,
                    edgeLabel + " obstructed by " + String.join(", ", obstacleLabels),
                    "Rigid structure or site exclusion intersects the emergency pathway corridor envelope.",
                    obstacleLabels.size() + " obstruction(s): " + String.join(", ", obstacleLabels),
                    "Emergency corridors must remain unobstructed",
                    pt != null ? pt.x() : null,
                    pt != null ? pt.y() : null,
                    "PATHWAY_EDGE",
                    edge.getId()
            ));
        }

        routeStatuses.add(new EmergencyDtos.VehicleRouteStatusDto(
                "EDGE-" + key,
                "PATHWAY_EDGE",
                edge.getId(),
                edgeLabel,
                "Emergency Pathway",
                clearWidthM,
                MIN_EMERGENCY_VEHICLE_WIDTH_M,
                widthCompliant,
                obstacleLabels.size(),
                List.copyOf(obstacleLabels),
                widthCompliant && obstacleLabels.isEmpty(),
                mid != null ? mid.x() : null,
                mid != null ? mid.y() : null
        ));
    }

    private GeometryUtil.Point2D collectObstacles(Geometry corridorGeom,
                                                  double overlapThresholdSqm,
                                                  List<PreparedObstacle> elementObstacles,
                                                  List<PreparedObstacle> exclusionObstacles,
                                                  boolean excludePortalsAndNonRigid,
                                                  Set<String> obstacleLabels) {
        GeometryUtil.Point2D firstPoint = null;
        for (PreparedObstacle obs : elementObstacles) {
            InfrastructureType type = obs.elementType();
            if (type.isEmergencyLane() || type == InfrastructureType.EMERGENCY_EXIT) {
                continue;
            }
            if (excludePortalsAndNonRigid && (!type.isRigidStructure() || type.isExitPortal() || type == InfrastructureType.ENTRY_GATE)) {
                continue;
            }
            if (!corridorGeom.getEnvelopeInternal().intersects(obs.geometry().getEnvelopeInternal())) {
                continue;
            }
            Geometry inter = corridorGeom.intersection(obs.geometry());
            if (geometryUtil.polygonalArea(inter) > overlapThresholdSqm) {
                obstacleLabels.add(obs.label());
                if (firstPoint == null) {
                    firstPoint = geometryUtil.interiorPoint(inter);
                }
            }
        }
        for (PreparedObstacle ex : exclusionObstacles) {
            if (!corridorGeom.getEnvelopeInternal().intersects(ex.geometry().getEnvelopeInternal())) {
                continue;
            }
            Geometry inter = corridorGeom.intersection(ex.geometry());
            if (geometryUtil.polygonalArea(inter) > overlapThresholdSqm) {
                obstacleLabels.add(ex.label());
                if (firstPoint == null) {
                    firstPoint = geometryUtil.interiorPoint(inter);
                }
            }
        }
        return firstPoint;
    }

    private List<PreparedObstacle> prepareElementObstacles(List<InfrastructureElement> elements) {
        List<PreparedObstacle> list = new ArrayList<>();
        for (InfrastructureElement el : elements) {
            if (el == null || !el.isActive() || el.getElementType() == null) {
                continue;
            }
            list.add(new PreparedObstacle(el.getLabel(), el.getElementType(), resolveFootprint(el)));
        }
        return list;
    }

    private List<PreparedObstacle> prepareExclusionObstacles(List<ExclusionArea> exclusions) {
        List<PreparedObstacle> list = new ArrayList<>();
        for (ExclusionArea ex : exclusions) {
            if (ex == null || ex.getExclusionType() == null || ex.getExclusionType().isCorridor()
                    || ex.getPolygonWkt() == null || ex.getPolygonWkt().isBlank()) {
                continue;
            }
            try {
                Polygon poly = geometryUtil.parsePolygon(ex.getPolygonWkt());
                list.add(new PreparedObstacle(ex.getLabel(), null, poly));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed exclusion geometry
            }
        }
        return list;
    }

    private Polygon resolveFootprint(InfrastructureElement el) {
        if (el.getFootprintWkt() != null && !el.getFootprintWkt().isBlank()) {
            try {
                return geometryUtil.parsePolygon(el.getFootprintWkt());
            } catch (IllegalArgumentException ignored) {
                // Fall back to rotated rectangle
            }
        }
        return geometryUtil.buildRotatedRectangle(
                el.getCenterX(), el.getCenterY(), el.getWidthM(), el.getHeightM(), el.getRotationDeg());
    }

    private LineString resolveEdgeLine(PathwayEdge edge) {
        if (edge.getPolylineWkt() != null && !edge.getPolylineWkt().isBlank()) {
            try {
                return geometryUtil.parseLineString(edge.getPolylineWkt());
            } catch (IllegalArgumentException ignored) {
                // Fall back to endpoint chord
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

    private Geometry safeParseGeometry(String wkt) {
        if (wkt == null || wkt.isBlank()) {
            return null;
        }
        try {
            return geometryUtil.parseGeometry(wkt);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private GeometryUtil.Point2D resolveAnchorPoint(Geometry geom) {
        if (geom == null || geom.isEmpty()) {
            return null;
        }
        if (geom instanceof Point pt) {
            return new GeometryUtil.Point2D(geometryUtil.round2(pt.getX()), geometryUtil.round2(pt.getY()));
        }
        if (geom instanceof LineString line) {
            return geometryUtil.lineMidpoint(line);
        }
        return geometryUtil.interiorPoint(geom);
    }

    private boolean isNearAnyPoint(Point pt, List<Point> existing, double maxDistM) {
        for (Point other : existing) {
            if (pt.distance(other) <= maxDistM + 1e-6) {
                return true;
            }
        }
        return false;
    }

    private String formatEdgeLabel(PathwayEdge edge) {
        if (edge.getLabel() != null && !edge.getLabel().isBlank()) {
            return edge.getLabel();
        }
        String from = edge.getFromNode() != null ? edge.getFromNode().getLabel() : "Start";
        String to = edge.getToNode() != null ? edge.getToNode().getLabel() : "End";
        return from + " -> " + to;
    }
}
