package com.vyuha.venue;

import com.vyuha.common.GeometryUtil;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;

/**
 * Pure-Java JTS space breakdown engine.
 * Clips all exclusion polygons to the outer venue boundary and unions overlapping exclusions
 * to guarantee zero double-counting when calculating Net Usable Gathering Area.
 */
@Service
public class AreaBreakdownService {

    private final GeometryUtil geometryUtil;

    public AreaBreakdownService(GeometryUtil geometryUtil) {
        this.geometryUtil = geometryUtil;
    }

    /**
     * Reusable JTS geometry contract (also used by Phase 4 grid clipping & capacity calculations)
     * returning the exact net usable polygon/multipolygon after subtracting unioned exclusions.
     */
    public Geometry computeUsableGeometry(Polygon boundary, List<ExclusionArea> exclusions) {
        if (boundary == null || boundary.isEmpty()) {
            return geometryUtil.getGeometryFactory().createGeometryCollection();
        }
        if (exclusions == null || exclusions.isEmpty()) {
            return boundary;
        }
        List<Geometry> clipped = new ArrayList<>();
        for (ExclusionArea exclusion : exclusions) {
            if (exclusion == null || exclusion.getPolygonWkt() == null || exclusion.getPolygonWkt().isBlank()) {
                continue;
            }
            Polygon exclPoly = geometryUtil.parsePolygon(exclusion.getPolygonWkt());
            Geometry intersection = boundary.intersection(exclPoly);
            if (geometryUtil.polygonalArea(intersection) > 1e-9) {
                clipped.add(intersection);
            }
        }
        Geometry unioned = geometryUtil.unionPolygons(clipped);
        if (unioned.isEmpty()) {
            return boundary;
        }
        return boundary.difference(unioned);
    }

    public AreaBreakdownResult calculate(String boundaryWkt, List<ExclusionArea> exclusions) {
        if (boundaryWkt == null || boundaryWkt.isBlank()) {
            return AreaBreakdownResult.empty();
        }

        Polygon boundary = geometryUtil.parsePolygon(boundaryWkt);
        double grossAreaSqm = geometryUtil.polygonalArea(boundary);
        double boundaryPerimeterM = boundary.getLength();
        GeometryUtil.Bounds boundaryBounds = geometryUtil.bounds(boundary);

        List<Geometry> restrictedClipped = new ArrayList<>();
        List<Geometry> corridorClipped = new ArrayList<>();
        List<Geometry> allClipped = new ArrayList<>();
        Map<ExclusionType, Double> categoryTotals = new EnumMap<>(ExclusionType.class);
        for (ExclusionType type : ExclusionType.values()) {
            categoryTotals.put(type, 0.0);
        }

        List<AreaBreakdownResult.ExclusionBreakdownItem> itemBreakdowns = new ArrayList<>();
        double sumRawClippedSqm = 0.0;
        double totalOutsideBoundarySqm = 0.0;

        if (exclusions != null) {
            for (ExclusionArea exclusion : exclusions) {
                if (exclusion == null || exclusion.getPolygonWkt() == null || exclusion.getPolygonWkt().isBlank()) {
                    continue;
                }
                Polygon exclPoly = geometryUtil.parsePolygon(exclusion.getPolygonWkt());
                double rawArea = geometryUtil.polygonalArea(exclPoly);
                Geometry clippedGeom = boundary.intersection(exclPoly);
                double clippedArea = geometryUtil.polygonalArea(clippedGeom);
                double outsideArea = Math.max(0.0, rawArea - clippedArea);

                sumRawClippedSqm += clippedArea;
                totalOutsideBoundarySqm += outsideArea;

                ExclusionType type = exclusion.getExclusionType() != null
                        ? exclusion.getExclusionType()
                        : ExclusionType.RESTRICTED;
                categoryTotals.put(type, categoryTotals.get(type) + clippedArea);

                if (clippedArea > 1e-9) {
                    allClipped.add(clippedGeom);
                    if (type.isCorridor()) {
                        corridorClipped.add(clippedGeom);
                    } else {
                        restrictedClipped.add(clippedGeom);
                    }
                }

                itemBreakdowns.add(new AreaBreakdownResult.ExclusionBreakdownItem(
                        exclusion.getId(),
                        exclusion.getLabel(),
                        type,
                        type.isCorridor(),
                        rawArea,
                        clippedArea,
                        outsideArea,
                        geometryUtil.centroid(clippedArea > 1e-9 ? clippedGeom : exclPoly)
                ));
            }
        }

        Geometry restrictedUnion = geometryUtil.unionPolygons(restrictedClipped);
        Geometry corridorRawUnion = geometryUtil.unionPolygons(corridorClipped);
        Geometry deduplicatedCorridors = corridorRawUnion;
        if (!corridorRawUnion.isEmpty() && !restrictedUnion.isEmpty()) {
            deduplicatedCorridors = corridorRawUnion.difference(restrictedUnion);
        }

        Geometry totalExcludedUnion = geometryUtil.unionPolygons(allClipped);
        Geometry usableGeom = totalExcludedUnion.isEmpty()
                ? boundary
                : boundary.difference(totalExcludedUnion);

        double restrictedAreaSqm = geometryUtil.polygonalArea(restrictedUnion);
        double corridorsAreaSqm = geometryUtil.polygonalArea(deduplicatedCorridors);
        double excludedTotalAreaSqm = geometryUtil.polygonalArea(totalExcludedUnion);
        double usableAreaSqm = Math.max(0.0, geometryUtil.polygonalArea(usableGeom));
        double overlapDeduplicatedSqm = Math.max(0.0, sumRawClippedSqm - excludedTotalAreaSqm);

        Map<String, Double> areaByCategory = new LinkedHashMap<>();
        for (ExclusionType type : ExclusionType.values()) {
            areaByCategory.put(type.name(), categoryTotals.get(type));
        }

        double usablePct = grossAreaSqm > 0.0 ? (usableAreaSqm / grossAreaSqm) * 100.0 : 0.0;
        double restrictedPct = grossAreaSqm > 0.0 ? (restrictedAreaSqm / grossAreaSqm) * 100.0 : 0.0;
        double corridorsPct = grossAreaSqm > 0.0 ? (corridorsAreaSqm / grossAreaSqm) * 100.0 : 0.0;
        double excludedPct = grossAreaSqm > 0.0 ? (excludedTotalAreaSqm / grossAreaSqm) * 100.0 : 0.0;

        return new AreaBreakdownResult(
                true,
                grossAreaSqm,
                boundaryPerimeterM,
                restrictedAreaSqm,
                corridorsAreaSqm,
                excludedTotalAreaSqm,
                usableAreaSqm,
                usablePct,
                restrictedPct,
                corridorsPct,
                excludedPct,
                overlapDeduplicatedSqm,
                totalOutsideBoundarySqm,
                Map.copyOf(areaByCategory),
                List.copyOf(itemBreakdowns),
                boundaryBounds
        );
    }
}
