package com.vyuha.monitoring;

import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.simulation.SimulationDtos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;

/**
 * Pure-Java bridge mapping Phase 9 crowd simulation agent frames into per-zone counts
 * tagged strictly as PRACTICE_SIMULATION, without contaminating live operational channels.
 */
@Service
public class PracticeSimulationBridgeService {

    private static final double NEAREST_WALKABLE_SNAP_METERS = 2.5;

    private final GeometryUtil geometryUtil;

    public PracticeSimulationBridgeService(GeometryUtil geometryUtil) {
        this.geometryUtil = geometryUtil;
    }

    public record MappedFrameCounts(
            Map<Long, Integer> countsByZoneId,
            int mappedAgentsCount,
            int unmappedAgentsCount
    ) {
    }

    public MappedFrameCounts mapFrameAgentsToZoneCounts(List<FunctionalZone> zones,
                                                        SimulationDtos.SimulationFrame frame) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        List<ParsedZone> parsedZones = new ArrayList<>();
        if (zones != null) {
            for (FunctionalZone zone : zones) {
                if (zone == null || zone.getId() == null) {
                    continue;
                }
                counts.put(zone.getId(), 0);
                if (zone.getPolygonWkt() != null && !zone.getPolygonWkt().isBlank()) {
                    try {
                        Geometry geom = geometryUtil.parsePolygonal(zone.getPolygonWkt());
                        parsedZones.add(new ParsedZone(zone, geom));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }

        int mapped = 0;
        int unmapped = 0;
        if (frame != null && frame.agents() != null) {
            for (double[] tuple : frame.agents()) {
                if (tuple == null || tuple.length < 4) {
                    continue;
                }
                int stateCode = (int) Math.round(tuple[3]);
                if (stateCode == 3) {
                    // Already exiting / outside interior gathering zones
                    continue;
                }
                double x = tuple[1];
                double y = tuple[2];
                if (!Double.isFinite(x) || !Double.isFinite(y)) {
                    unmapped++;
                    continue;
                }

                Point pt = geometryUtil.getGeometryFactory().createPoint(new Coordinate(x, y));
                Long matchedZoneId = findZoneIdForPoint(pt, parsedZones);
                if (matchedZoneId != null) {
                    counts.merge(matchedZoneId, 1, Integer::sum);
                    mapped++;
                } else {
                    unmapped++;
                }
            }
        }

        return new MappedFrameCounts(counts, mapped, unmapped);
    }

    /**
     * Deterministic fallback dry-run generator when an event has defined zones
     * but no Phase 9 SimulationRun recorded yet.
     */
    public Map<Long, Integer> generatePracticeDryRunCounts(List<FunctionalZone> zones,
                                                           Map<Long, Integer> reviewedCapacities,
                                                           int stepIndex) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        if (zones == null || zones.isEmpty()) {
            return counts;
        }
        double[] wavePcts = {0.32, 0.54, 0.74, 0.88, 0.99, 0.68, 0.41};
        int normalizedStep = Math.max(0, stepIndex);
        int idx = 0;
        for (FunctionalZone zone : zones) {
            if (zone == null || zone.getId() == null) {
                continue;
            }
            if (!zone.getZoneType().isWalkable()) {
                continue;
            }
            int cap = reviewedCapacities != null ? reviewedCapacities.getOrDefault(zone.getId(), 0) : 0;
            if (cap <= 0 && !zone.getZoneType().isCapacityBearing()) {
                continue;
            }
            int baseCap = cap > 0 ? cap : 60;
            double factor = wavePcts[(normalizedStep + idx) % wavePcts.length];
            counts.put(zone.getId(), Math.max(0, (int) Math.round(baseCap * factor)));
            idx++;
        }
        return counts;
    }

    private Long findZoneIdForPoint(Point pt, List<ParsedZone> parsedZones) {
        for (ParsedZone pz : parsedZones) {
            if (pz.geometry().covers(pt)) {
                return pz.zone().getId();
            }
        }
        Long nearestId = null;
        double bestDist = NEAREST_WALKABLE_SNAP_METERS;
        for (ParsedZone pz : parsedZones) {
            if (!pz.zone().getZoneType().isWalkable()) {
                continue;
            }
            double dist = pz.geometry().distance(pt);
            if (dist <= bestDist) {
                bestDist = dist;
                nearestId = pz.zone().getId();
            }
        }
        return nearestId;
    }

    private record ParsedZone(FunctionalZone zone, Geometry geometry) {
    }
}
