package com.vyuha.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.GridDtos;
import com.vyuha.grid.ZoneType;
import com.vyuha.grid.ZoneValidationService;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Service;

/**
 * Pure-Java JTS spatial conflict detection engine for placed infrastructure elements.
 * Evaluates rotated footprints against emergency corridors/lanes, exit clearance aprons,
 * overlapping rigid structures, venue boundaries, walkable access reachability, and water hazards.
 */
@Service
public class ConflictDetectionService {

    public static final double OVERLAP_THRESHOLD_SQM = 0.01;
    public static final double OUT_OF_BOUNDS_THRESHOLD_SQM = 0.05;
    public static final double SERVICE_MAX_ACCESS_DISTANCE_M = 2.0;
    public static final double WATER_CORRIDOR_MIN_WIDTH_M = 3.0;
    public static final double WATER_UTILITY_PROXIMITY_M = 2.0;

    private final GeometryUtil geometryUtil;
    private final ZoneValidationService zoneValidationService;
    private final ObjectMapper objectMapper;

    public ConflictDetectionService(GeometryUtil geometryUtil,
                                    ZoneValidationService zoneValidationService,
                                    ObjectMapper objectMapper) {
        this.geometryUtil = geometryUtil;
        this.zoneValidationService = zoneValidationService;
        this.objectMapper = objectMapper;
    }

    public record DetectedConflict(
            String findingKey,
            ConflictType conflictType,
            FindingSeverity severity,
            String title,
            String description,
            String measuredValue,
            Double locationX,
            Double locationY,
            List<Long> involvedElementIds
    ) {
    }

    private record PreparedElement(
            InfrastructureElement element,
            String key,
            Polygon footprint,
            InfrastructureDtos.ElementMetadata metadata
    ) {
    }

    private record RouteTarget(
            String key,
            String label,
            Long elementId,
            Geometry geometry
    ) {
    }

    public List<DetectedConflict> detect(Polygon boundary,
                                         List<ExclusionArea> exclusions,
                                         List<FunctionalZone> zones,
                                         List<InfrastructureElement> elements) {
        List<ExclusionArea> safeExclusions = exclusions != null ? exclusions : List.of();
        List<FunctionalZone> safeZones = zones != null ? zones : List.of();
        List<InfrastructureElement> safeElements = elements != null ? elements : List.of();

        List<PreparedElement> activeElements = new ArrayList<>();
        for (int i = 0; i < safeElements.size(); i++) {
            InfrastructureElement el = safeElements.get(i);
            if (el == null || !el.isActive() || el.getElementType() == null) {
                continue;
            }
            Polygon footprint = resolveFootprint(el);
            InfrastructureDtos.ElementMetadata meta = InfrastructureDtos.ElementMetadata.parse(
                    objectMapper, el.getMetadataJson());
            activeElements.add(new PreparedElement(el, elementKey(el, i), footprint, meta));
        }

        if (activeElements.isEmpty()) {
            return List.of();
        }

        List<DetectedConflict> detected = new ArrayList<>();

        detectEmergencyIntrusion(boundary, safeZones, activeElements, detected);
        detectExitObstructions(activeElements, detected);
        detectOverlappingRigidStructures(activeElements, detected);
        detectOutOfBounds(boundary, activeElements, detected);
        detectUnreachableServicePosts(boundary, safeExclusions, safeZones, activeElements, detected);
        detectWaterUtilityHazards(boundary, safeExclusions, safeZones, activeElements, detected);

        detected.sort(Comparator
                .comparingInt((DetectedConflict c) -> c.severity().sortRank())
                .thenComparing(DetectedConflict::findingKey));
        return List.copyOf(detected);
    }

    private void detectEmergencyIntrusion(Polygon boundary,
                                          List<FunctionalZone> zones,
                                          List<PreparedElement> activeElements,
                                          List<DetectedConflict> out) {
        List<RouteTarget> emergencyRoutes = new ArrayList<>();
        for (int i = 0; i < zones.size(); i++) {
            FunctionalZone zone = zones.get(i);
            if (zone == null || zone.getZoneType() != ZoneType.EMERGENCY_CORRIDOR
                    || zone.getPolygonWkt() == null || zone.getPolygonWkt().isBlank()) {
                continue;
            }
            Geometry geom = geometryUtil.parsePolygonal(zone.getPolygonWkt());
            if (boundary != null) {
                geom = boundary.intersection(geom);
            }
            if (geometryUtil.polygonalArea(geom) > OVERLAP_THRESHOLD_SQM) {
                String zKey = zone.getZoneCode() != null && !zone.getZoneCode().isBlank()
                        ? "ZONE-" + zone.getZoneCode()
                        : "ZONE-IDX-" + i;
                emergencyRoutes.add(new RouteTarget(zKey, zone.getName(), null, geom));
            }
        }
        for (PreparedElement pe : activeElements) {
            if (pe.element().getElementType().isEmergencyLane()) {
                emergencyRoutes.add(new RouteTarget(
                        pe.key(), pe.element().getLabel(), pe.element().getId(), pe.footprint()));
            }
        }
        if (emergencyRoutes.isEmpty()) {
            return;
        }

        for (PreparedElement pe : activeElements) {
            InfrastructureType type = pe.element().getElementType();
            if (type.isEmergencyLane() || type == InfrastructureType.EMERGENCY_EXIT) {
                continue;
            }
            for (RouteTarget route : emergencyRoutes) {
                if (!pe.footprint().getEnvelopeInternal().intersects(route.geometry().getEnvelopeInternal())) {
                    continue;
                }
                Geometry overlap = pe.footprint().intersection(route.geometry());
                double overlapArea = geometryUtil.polygonalArea(overlap);
                if (overlapArea > OVERLAP_THRESHOLD_SQM) {
                    GeometryUtil.Point2D pt = geometryUtil.interiorPoint(overlap);
                    out.add(new DetectedConflict(
                            "EMERGENCY_LANE_INTRUSION:" + pe.key() + ":" + route.key(),
                            ConflictType.EMERGENCY_LANE_INTRUSION,
                            FindingSeverity.CRITICAL,
                            pe.element().getLabel() + " intrudes into " + route.label(),
                            "Physical structure encroaches on an emergency corridor or emergency vehicle access lane.",
                            String.format(Locale.US, "%.2f m² overlap", overlapArea),
                            pt != null ? pt.x() : pe.element().getCenterX(),
                            pt != null ? pt.y() : pe.element().getCenterY(),
                            elementIds(pe.element().getId(), route.elementId())
                    ));
                }
            }
        }
    }

    private void detectExitObstructions(List<PreparedElement> activeElements,
                                        List<DetectedConflict> out) {
        List<PreparedElement> exits = activeElements.stream()
                .filter(pe -> pe.element().getElementType().isExitPortal())
                .toList();
        if (exits.isEmpty()) {
            return;
        }

        for (PreparedElement exit : exits) {
            double bufferM = exit.metadata().effectiveClearanceBufferM();
            Geometry apron = exit.footprint().buffer(bufferM);

            for (PreparedElement other : activeElements) {
                if (other == exit || !other.element().getElementType().isObstructsExit()) {
                    continue;
                }
                if (!apron.getEnvelopeInternal().intersects(other.footprint().getEnvelopeInternal())) {
                    continue;
                }
                double dist = exit.footprint().distance(other.footprint());
                if (dist < bufferM - 1e-6) {
                    Geometry apronOverlap = apron.intersection(other.footprint());
                    GeometryUtil.Point2D pt = geometryUtil.interiorPoint(apronOverlap);
                    if (pt == null) {
                        Coordinate[] nearest = DistanceOp.nearestPoints(exit.footprint(), other.footprint());
                        if (nearest != null && nearest.length >= 2) {
                            pt = new GeometryUtil.Point2D(
                                    (nearest[0].x + nearest[1].x) / 2.0,
                                    (nearest[0].y + nearest[1].y) / 2.0);
                        }
                    }
                    String measured = dist < 1e-6
                            ? String.format(Locale.US, "0.00 m (direct overlap, min %.2f m)", bufferM)
                            : String.format(Locale.US, "%.2f m clearance (min %.2f m)", dist, bufferM);
                    out.add(new DetectedConflict(
                            "EXIT_OBSTRUCTION:" + exit.key() + ":" + other.key(),
                            ConflictType.EXIT_OBSTRUCTION,
                            FindingSeverity.CRITICAL,
                            other.element().getLabel() + " obstructs " + exit.element().getLabel(),
                            "Solid structure sits inside the required exit clearance apron.",
                            measured,
                            pt != null ? pt.x() : exit.element().getCenterX(),
                            pt != null ? pt.y() : exit.element().getCenterY(),
                            elementIds(exit.element().getId(), other.element().getId())
                    ));
                }
            }
        }
    }

    private void detectOverlappingRigidStructures(List<PreparedElement> activeElements,
                                                  List<DetectedConflict> out) {
        for (int i = 0; i < activeElements.size(); i++) {
            PreparedElement a = activeElements.get(i);
            if (!a.element().getElementType().isRigidStructure()) {
                continue;
            }
            for (int j = i + 1; j < activeElements.size(); j++) {
                PreparedElement b = activeElements.get(j);
                if (!b.element().getElementType().isRigidStructure()) {
                    continue;
                }
                if (isLinearAlignedBarricade(a) || isLinearAlignedBarricade(b)) {
                    continue;
                }
                if (!a.footprint().getEnvelopeInternal().intersects(b.footprint().getEnvelopeInternal())) {
                    continue;
                }
                Geometry overlap = a.footprint().intersection(b.footprint());
                double overlapArea = geometryUtil.polygonalArea(overlap);
                if (overlapArea > OVERLAP_THRESHOLD_SQM) {
                    boolean aFirst = a.key().compareTo(b.key()) <= 0;
                    String minKey = aFirst ? a.key() : b.key();
                    String maxKey = aFirst ? b.key() : a.key();
                    GeometryUtil.Point2D pt = geometryUtil.interiorPoint(overlap);
                    out.add(new DetectedConflict(
                            "OVERLAPPING_RIGID_STRUCTURES:" + minKey + ":" + maxKey,
                            ConflictType.OVERLAPPING_RIGID_STRUCTURES,
                            FindingSeverity.HIGH,
                            "Overlapping structures: " + a.element().getLabel() + " & " + b.element().getLabel(),
                            "Two physical infrastructure footprints overlap on the venue floor.",
                            String.format(Locale.US, "%.2f m² overlap", overlapArea),
                            pt != null ? pt.x() : a.element().getCenterX(),
                            pt != null ? pt.y() : a.element().getCenterY(),
                            elementIds(a.element().getId(), b.element().getId())
                    ));
                }
            }
        }
    }

    private void detectOutOfBounds(Polygon boundary,
                                   List<PreparedElement> activeElements,
                                   List<DetectedConflict> out) {
        if (boundary == null || boundary.isEmpty()) {
            return;
        }
        for (PreparedElement pe : activeElements) {
            Geometry outside = pe.footprint().difference(boundary);
            double outsideArea = geometryUtil.polygonalArea(outside);
            if (outsideArea > OUT_OF_BOUNDS_THRESHOLD_SQM) {
                GeometryUtil.Point2D pt = geometryUtil.interiorPoint(outside);
                out.add(new DetectedConflict(
                        "OUT_OF_BOUNDS:" + pe.key(),
                        ConflictType.OUT_OF_BOUNDS,
                        FindingSeverity.HIGH,
                        pe.element().getLabel() + " extends outside venue boundary",
                        "Infrastructure footprint extends outside the calibrated outer venue boundary.",
                        String.format(Locale.US, "%.2f m² outside boundary", outsideArea),
                        pt != null ? pt.x() : pe.element().getCenterX(),
                        pt != null ? pt.y() : pe.element().getCenterY(),
                        elementIds(pe.element().getId())
                ));
            }
        }
    }

    private void detectUnreachableServicePosts(Polygon boundary,
                                               List<ExclusionArea> exclusions,
                                               List<FunctionalZone> zones,
                                               List<PreparedElement> activeElements,
                                               List<DetectedConflict> out) {
        List<PreparedElement> servicePosts = activeElements.stream()
                .filter(pe -> pe.element().getElementType().isRequiresWalkableAccess())
                .toList();
        if (servicePosts.isEmpty()) {
            return;
        }

        Set<Long> isolatedZoneIds = Set.of();
        Geometry walkableFloor = null;
        if (boundary != null && !boundary.isEmpty()) {
            GridDtos.ZoneValidationResult validation = zoneValidationService.validate(boundary, exclusions, zones);
            isolatedZoneIds = new HashSet<>(validation.isolatedZoneIds());
            walkableFloor = computeWalkableFloor(boundary, exclusions);
        }

        List<Geometry> accessible = new ArrayList<>();
        for (FunctionalZone zone : zones) {
            if (zone == null || zone.getZoneType() == null || !zone.getZoneType().isWalkable()
                    || zone.getPolygonWkt() == null || zone.getPolygonWkt().isBlank()) {
                continue;
            }
            if (zone.getId() != null && isolatedZoneIds.contains(zone.getId())) {
                continue;
            }
            Geometry zGeom = geometryUtil.parsePolygonal(zone.getPolygonWkt());
            if (walkableFloor != null) {
                zGeom = zGeom.intersection(walkableFloor);
            }
            if (geometryUtil.polygonalArea(zGeom) > OVERLAP_THRESHOLD_SQM) {
                accessible.add(zGeom);
            }
        }

        for (ExclusionArea ex : exclusions) {
            if (ex == null || ex.getExclusionType() != ExclusionType.RESERVED_WALKWAY
                    || ex.getPolygonWkt() == null || ex.getPolygonWkt().isBlank()) {
                continue;
            }
            Geometry exGeom = geometryUtil.parsePolygon(ex.getPolygonWkt());
            if (boundary != null) {
                exGeom = boundary.intersection(exGeom);
            }
            if (geometryUtil.polygonalArea(exGeom) > OVERLAP_THRESHOLD_SQM) {
                accessible.add(exGeom);
            }
        }

        for (PreparedElement pe : activeElements) {
            if (pe.element().getElementType().isEmergencyLane()) {
                accessible.add(pe.footprint());
            }
        }

        for (PreparedElement post : servicePosts) {
            double minDist = Double.POSITIVE_INFINITY;
            for (Geometry acc : accessible) {
                double dist = post.footprint().distance(acc);
                if (dist < minDist) {
                    minDist = dist;
                    if (minDist <= 1e-6) {
                        break;
                    }
                }
            }
            if (minDist > SERVICE_MAX_ACCESS_DISTANCE_M + 1e-6) {
                String measured = Double.isInfinite(minDist)
                        ? "No walkable access defined"
                        : String.format(Locale.US, "%.2f m to nearest access (max %.2f m)",
                                minDist, SERVICE_MAX_ACCESS_DISTANCE_M);
                out.add(new DetectedConflict(
                        "UNREACHABLE_SERVICE_POST:" + post.key(),
                        ConflictType.UNREACHABLE_SERVICE_POST,
                        FindingSeverity.HIGH,
                        post.element().getLabel() + " is isolated from walkable access",
                        "Medical posts and control rooms must sit within 2.0 m of an accessible walkable zone, corridor, or emergency lane.",
                        measured,
                        post.element().getCenterX(),
                        post.element().getCenterY(),
                        elementIds(post.element().getId())
                ));
            }
        }
    }

    private void detectWaterUtilityHazards(Polygon boundary,
                                           List<ExclusionArea> exclusions,
                                           List<FunctionalZone> zones,
                                           List<PreparedElement> activeElements,
                                           List<DetectedConflict> out) {
        List<PreparedElement> waterPoints = activeElements.stream()
                .filter(pe -> pe.element().getElementType() == InfrastructureType.WATER_POINT)
                .toList();
        if (waterPoints.isEmpty()) {
            return;
        }

        List<PreparedElement> stages = activeElements.stream()
                .filter(pe -> pe.element().getElementType() == InfrastructureType.STAGE)
                .toList();

        for (PreparedElement water : waterPoints) {
            // 1. Placed inside narrow walkway (< 3.0 m minimum width)
            for (int i = 0; i < zones.size(); i++) {
                FunctionalZone zone = zones.get(i);
                if (zone == null || zone.getZoneType() != ZoneType.WALKWAY
                        || zone.getPolygonWkt() == null || zone.getPolygonWkt().isBlank()) {
                    continue;
                }
                Geometry zGeom = geometryUtil.parsePolygonal(zone.getPolygonWkt());
                if (boundary != null) {
                    zGeom = boundary.intersection(zGeom);
                }
                for (Polygon piece : geometryUtil.extractPolygons(zGeom)) {
                    if (!water.footprint().getEnvelopeInternal().intersects(piece.getEnvelopeInternal())) {
                        continue;
                    }
                    double overlapArea = geometryUtil.polygonalArea(water.footprint().intersection(piece));
                    if (overlapArea > OVERLAP_THRESHOLD_SQM) {
                        double width = computeMinimumWidth(piece);
                        if (width < WATER_CORRIDOR_MIN_WIDTH_M - 1e-6) {
                            String zKey = zone.getZoneCode() != null && !zone.getZoneCode().isBlank()
                                    ? zone.getZoneCode() : "IDX-" + i;
                            out.add(new DetectedConflict(
                                    "WATER_UTILITY_HAZARD:CORRIDOR:" + water.key() + ":ZONE-" + zKey,
                                    ConflictType.WATER_UTILITY_HAZARD,
                                    FindingSeverity.LOW,
                                    water.element().getLabel() + " sits in narrow walkway " + zone.getName(),
                                    "Water distribution points placed inside narrow walkways (< 3.0 m) create queuing chokes.",
                                    String.format(Locale.US, "%.2f m corridor width (< %.2f m)",
                                            width, WATER_CORRIDOR_MIN_WIDTH_M),
                                    water.element().getCenterX(),
                                    water.element().getCenterY(),
                                    elementIds(water.element().getId())
                            ));
                        }
                    }
                }
            }

            for (int i = 0; i < exclusions.size(); i++) {
                ExclusionArea ex = exclusions.get(i);
                if (ex == null || ex.getPolygonWkt() == null || ex.getPolygonWkt().isBlank()) {
                    continue;
                }
                if (ex.getExclusionType() == ExclusionType.RESERVED_WALKWAY) {
                    Geometry exGeom = geometryUtil.parsePolygon(ex.getPolygonWkt());
                    if (boundary != null) {
                        exGeom = boundary.intersection(exGeom);
                    }
                    for (Polygon piece : geometryUtil.extractPolygons(exGeom)) {
                        if (geometryUtil.polygonalArea(water.footprint().intersection(piece)) > OVERLAP_THRESHOLD_SQM) {
                            double width = computeMinimumWidth(piece);
                            if (width < WATER_CORRIDOR_MIN_WIDTH_M - 1e-6) {
                                String exKey = ex.getId() != null ? String.valueOf(ex.getId()) : "IDX-" + i;
                                out.add(new DetectedConflict(
                                        "WATER_UTILITY_HAZARD:CORRIDOR:" + water.key() + ":EX-" + exKey,
                                        ConflictType.WATER_UTILITY_HAZARD,
                                        FindingSeverity.LOW,
                                        water.element().getLabel() + " sits in narrow corridor " + ex.getLabel(),
                                        "Water distribution points placed inside narrow walkways (< 3.0 m) create queuing chokes.",
                                        String.format(Locale.US, "%.2f m corridor width (< %.2f m)",
                                                width, WATER_CORRIDOR_MIN_WIDTH_M),
                                        water.element().getCenterX(),
                                        water.element().getCenterY(),
                                        elementIds(water.element().getId())
                                ));
                            }
                        }
                    }
                } else if (ex.getExclusionType() == ExclusionType.EQUIPMENT
                        || ex.getExclusionType() == ExclusionType.STAGE) {
                    Geometry exGeom = geometryUtil.parsePolygon(ex.getPolygonWkt());
                    double dist = water.footprint().distance(exGeom);
                    if (dist <= WATER_UTILITY_PROXIMITY_M + 1e-6) {
                        String exKey = ex.getId() != null ? String.valueOf(ex.getId()) : "IDX-" + i;
                        out.add(new DetectedConflict(
                                "WATER_UTILITY_HAZARD:EXCLUSION:" + water.key() + ":EX-" + exKey,
                                ConflictType.WATER_UTILITY_HAZARD,
                                FindingSeverity.LOW,
                                water.element().getLabel() + " near power/stage exclusion " + ex.getLabel(),
                                "Water points should maintain separation from stage power and electrical equipment zones.",
                                String.format(Locale.US, "%.2f m clearance", dist),
                                water.element().getCenterX(),
                                water.element().getCenterY(),
                                elementIds(water.element().getId())
                        ));
                    }
                }
            }

            // 2. Stage rear utility zone check (local y in [-h/2 - 2.5, 0.0], x in [-w/2 - 1.0, +w/2 + 1.0])
            for (PreparedElement stage : stages) {
                Polygon rearZone = buildStageRearZone(stage.element());
                if (water.footprint().intersects(rearZone)) {
                    double dist = water.footprint().distance(stage.footprint());
                    Geometry overlap = water.footprint().intersection(rearZone);
                    GeometryUtil.Point2D pt = geometryUtil.interiorPoint(overlap);
                    out.add(new DetectedConflict(
                            "WATER_UTILITY_HAZARD:STAGE:" + water.key() + ":" + stage.key(),
                            ConflictType.WATER_UTILITY_HAZARD,
                            FindingSeverity.LOW,
                            water.element().getLabel() + " near rear utility zone of " + stage.element().getLabel(),
                            "Water points positioned immediately behind a stage risk contact with high-voltage stage utilities.",
                            String.format(Locale.US, "%.2f m from stage", dist),
                            pt != null ? pt.x() : water.element().getCenterX(),
                            pt != null ? pt.y() : water.element().getCenterY(),
                            elementIds(water.element().getId(), stage.element().getId())
                    ));
                }
            }
        }
    }

    private Polygon buildStageRearZone(InfrastructureElement stage) {
        double hw = stage.getWidthM() / 2.0 + 1.0;
        double hh = stage.getHeightM() / 2.0;
        double cx = stage.getCenterX();
        double cy = stage.getCenterY();
        double rot = stage.getRotationDeg();
        List<GeometryUtil.Point2D> corners = List.of(
                geometryUtil.localToWorld(cx, cy, rot, -hw, -hh - 2.5),
                geometryUtil.localToWorld(cx, cy, rot, hw, -hh - 2.5),
                geometryUtil.localToWorld(cx, cy, rot, hw, 0.0),
                geometryUtil.localToWorld(cx, cy, rot, -hw, 0.0)
        );
        return geometryUtil.buildPolygon(corners);
    }

    private double computeMinimumWidth(Geometry geom) {
        if (geom == null || geom.isEmpty()) {
            return 0.0;
        }
        return new MinimumDiameter(geom).getLength();
    }

    private Geometry computeWalkableFloor(Polygon boundary, List<ExclusionArea> exclusions) {
        List<Geometry> solidClipped = new ArrayList<>();
        for (ExclusionArea ex : exclusions) {
            if (ex == null || ex.getPolygonWkt() == null || ex.getPolygonWkt().isBlank()) {
                continue;
            }
            if (ex.getExclusionType() != null && ex.getExclusionType().isCorridor()) {
                continue;
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

    private Polygon resolveFootprint(InfrastructureElement el) {
        if (el.getFootprintWkt() != null && !el.getFootprintWkt().isBlank()) {
            try {
                return geometryUtil.parsePolygon(el.getFootprintWkt());
            } catch (IllegalArgumentException ignored) {
                // Fall back to exact rotated rectangle construction below
            }
        }
        return geometryUtil.buildRotatedRectangle(
                el.getCenterX(), el.getCenterY(), el.getWidthM(), el.getHeightM(), el.getRotationDeg());
    }

    private boolean isLinearAlignedBarricade(PreparedElement pe) {
        return pe.element().getElementType() == InfrastructureType.BARRICADE
                && pe.metadata().isLinearAligned();
    }

    private String elementKey(InfrastructureElement el, int index) {
        if (el.getElementUid() != null && !el.getElementUid().isBlank()) {
            return el.getElementUid().trim();
        }
        if (el.getId() != null) {
            return "ID-" + el.getId();
        }
        return "IDX-" + index;
    }

    private List<Long> elementIds(Long... ids) {
        List<Long> result = new ArrayList<>();
        for (Long id : ids) {
            if (Objects.nonNull(id) && !result.contains(id)) {
                result.add(id);
            }
        }
        return List.copyOf(result);
    }
}
