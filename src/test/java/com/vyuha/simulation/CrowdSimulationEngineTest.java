package com.vyuha.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.NetworkLayer;
import com.vyuha.network.PathwayDirection;
import com.vyuha.network.PathwayNodeType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrowdSimulationEngineTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final CrowdSimulationEngine engine = new CrowdSimulationEngine(geometryUtil);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void identicalSeedsProduceBitForBitIdenticalMetricsAndFrames() throws Exception {
        SimulationDtos.SerializedLayout layout = buildCorridorLayout(1.4, false, false);
        SimulationConfig config = SimulationConfig.normalized(
                SimulationScenarioType.ARRIVAL_RUSH,
                98765L, 200, 240, 4.0, 2.0, 1.35,
                null, null, null, null, 0);

        CrowdSimulationEngine.SimulationExecutionResult runA = engine.simulate(layout, config);
        CrowdSimulationEngine.SimulationExecutionResult runB = engine.simulate(layout, config);

        String metricsJsonA = objectMapper.writeValueAsString(runA.metrics());
        String metricsJsonB = objectMapper.writeValueAsString(runB.metrics());
        assertThat(metricsJsonA).isEqualTo(metricsJsonB);

        String framesJsonA = objectMapper.writeValueAsString(runA.frameStream());
        String framesJsonB = objectMapper.writeValueAsString(runB.frameStream());
        assertThat(framesJsonA).isEqualTo(framesJsonB);
        assertThat(runA.frameStream().frames()).isNotEmpty();
    }

    @Test
    void wideningBottleneckCorridorReducesPeakDensityQueueCongestionAndClearanceTime() {
        SimulationDtos.SerializedLayout narrowV1 = buildCorridorLayout(1.2, false, false);
        SimulationDtos.SerializedLayout wideV2 = buildCorridorLayout(4.5, false, false);

        SimulationConfig sharedConfig = SimulationConfig.normalized(
                SimulationScenarioType.ARRIVAL_RUSH,
                42L, 260, 320, 4.0, 2.0, 1.35,
                null, null, null, null, 0);

        SimulationDtos.SimulationMetricsSummary m1 = engine.simulate(narrowV1, sharedConfig).metrics();
        SimulationDtos.SimulationMetricsSummary m2 = engine.simulate(wideV2, sharedConfig).metrics();

        assertThat(m2.peakLocalDensityPsm()).isLessThan(m1.peakLocalDensityPsm());
        assertThat(m2.bottleneckCongestionDurationSec()).isLessThan(m1.bottleneckCongestionDurationSec());
        assertThat(m2.peakQueueLength()).isLessThan(m1.peakQueueLength());
        assertThat(m2.p95TravelClearanceTimeSec()).isLessThan(m1.p95TravelClearanceTimeSec());
        assertThat(m2.avgTravelClearanceTimeSec()).isLessThan(m1.avgTravelClearanceTimeSec());
    }

    @Test
    void relocatingObstructingFoodStallRestoresCorridorWidthAndReducesDensity() {
        SimulationDtos.SerializedLayout obstructed = buildCorridorLayout(4.0, true, false);
        SimulationDtos.SerializedLayout cleared = buildCorridorLayout(4.0, false, false);

        SimulationConfig config = SimulationConfig.normalized(
                SimulationScenarioType.ARRIVAL_RUSH,
                777L, 220, 300, 4.0, 2.0, 1.35,
                null, null, null, null, 0);

        SimulationDtos.SimulationMetricsSummary mObstructed = engine.simulate(obstructed, config).metrics();
        SimulationDtos.SimulationMetricsSummary mCleared = engine.simulate(cleared, config).metrics();

        assertThat(mObstructed.hotspots()).isNotEmpty();
        assertThat(mObstructed.hotspots().stream()
                .anyMatch(h -> h.effectiveWidthM() < h.nominalWidthM() - 0.5)).isTrue();
        assertThat(mCleared.peakLocalDensityPsm()).isLessThan(mObstructed.peakLocalDensityPsm());
        assertThat(mCleared.p95TravelClearanceTimeSec()).isLessThan(mObstructed.p95TravelClearanceTimeSec());
    }

    @Test
    void exercisesAllFiveScenariosDeterministically() {
        SimulationDtos.SerializedLayout multiPathLayout = buildCorridorLayout(3.6, false, true);

        SimulationDtos.SimulationMetricsSummary normal = engine.simulate(multiPathLayout,
                SimulationConfig.normalized(SimulationScenarioType.NORMAL_OPERATIONS, 101L, 220, 300,
                        4.0, 2.0, 1.35, null, null, null, null, 0)).metrics();

        SimulationDtos.SimulationMetricsSummary rush = engine.simulate(multiPathLayout,
                SimulationConfig.normalized(SimulationScenarioType.ARRIVAL_RUSH, 101L, 220, 300,
                        4.0, 2.0, 1.35, null, null, null, null, 0)).metrics();

        assertThat(rush.peakQueueLength()).isGreaterThan(normal.peakQueueLength());
        assertThat(rush.maxQueueWaitTimeSec()).isGreaterThanOrEqualTo(normal.maxQueueWaitTimeSec());

        // STAGE_CROWDING concentrates 85% onto primary stage-adjacent AUD-1 hub
        SimulationDtos.SimulationMetricsSummary stageCrowd = engine.simulate(multiPathLayout,
                SimulationConfig.normalized(SimulationScenarioType.STAGE_CROWDING, 101L, 220, 300,
                        4.0, 2.0, 1.35, null, null, null, null, 0)).metrics();
        assertThat(stageCrowd.totalAgentsExited()).isPositive();
        assertThat(stageCrowd.disruptionAppliedNote()).contains("85%");

        // BLOCKED_CORRIDOR disables primary exit edge -> agents reroute via emergency corridor
        SimulationDtos.SimulationMetricsSummary blocked = engine.simulate(multiPathLayout,
                SimulationConfig.normalized(SimulationScenarioType.BLOCKED_CORRIDOR, 101L, 180, 300,
                        4.0, 2.0, 1.35, "EDGE-EXIT-PRIMARY", null, null, null, 0)).metrics();
        assertThat(blocked.disruptionAppliedNote()).contains("blocked");
        assertThat(blocked.totalAgentsExited()).isPositive();

        // CLOSED_EXIT closes main EXIT_GATE -> agents divert to EMERGENCY_EXIT
        SimulationDtos.SimulationMetricsSummary closedExit = engine.simulate(multiPathLayout,
                SimulationConfig.normalized(SimulationScenarioType.CLOSED_EXIT, 101L, 180, 300,
                        4.0, 2.0, 1.35, null, null, "NODE-EXIT-1", null, 0)).metrics();
        assertThat(closedExit.disruptionAppliedNote()).contains("closed");
        assertThat(closedExit.totalAgentsExited()).isPositive();

        // Closing the ONLY exit on a single-exit layout traps agents (blockedNoRouteCount > 0)
        SimulationDtos.SerializedLayout singleExit = buildCorridorLayout(3.6, false, false);
        SimulationDtos.SimulationMetricsSummary trapped = engine.simulate(singleExit,
                SimulationConfig.normalized(SimulationScenarioType.CLOSED_EXIT, 101L, 60, 180,
                        4.0, 2.0, 1.35, null, null, "NODE-EXIT-1", null, 0)).metrics();
        assertThat(trapped.blockedNoRouteCount()).isPositive();
        assertThat(trapped.unservedBlockedAgentCount()).isEqualTo(trapped.totalAgentsScheduled());
    }

    @Test
    void rejectsLayoutMissingExitsOrPathways() {
        SimulationDtos.SerializedLayout emptyLayout = new SimulationDtos.SerializedLayout(
                "Empty", 500, "INTERACTIVE_MAP", "campus-quadrangle", false, null, null, null, null,
                "POLYGON ((0 0, 100 0, 100 80, 0 80, 0 0))",
                List.of(), new GeometryUtil.Bounds(0, 0, 100, 80),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertThatThrownBy(() -> engine.simulate(emptyLayout, SimulationConfig.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pathway");
    }

    private SimulationDtos.SerializedLayout buildCorridorLayout(double centralCorridorWidthM,
                                                                boolean placeObstructingStall,
                                                                boolean includeSecondaryExitRoute) {
        String boundaryWkt = "POLYGON ((0 0, 120 0, 120 90, 0 90, 0 0))";
        List<GeometryUtil.Point2D> boundaryPts = List.of(
                new GeometryUtil.Point2D(0, 0),
                new GeometryUtil.Point2D(120, 0),
                new GeometryUtil.Point2D(120, 90),
                new GeometryUtil.Point2D(0, 90));

        List<SimulationDtos.SnapZone> zones = List.of(
                new SimulationDtos.SnapZone(10L, "AUD-1", "Main Stage Lawn", ZoneType.AUDIENCE,
                        true, false, ZoneType.AUDIENCE.getFillColor(), ZoneType.AUDIENCE.getStrokeColor(),
                        "POLYGON ((45 25, 85 25, 85 65, 45 65, 45 25))",
                        List.of(), new GeometryUtil.Point2D(65, 45))
        );

        List<SimulationDtos.SnapElement> elements = new ArrayList<>();
        elements.add(new SimulationDtos.SnapElement(
                100L, "EL-ENT-1", InfrastructureType.ENTRY_GATE, "North Entry Gate",
                20, 10, 8.0, 2.0, 0.0, 180.0, true,
                InfrastructureType.ENTRY_GATE.getFillColor(), InfrastructureType.ENTRY_GATE.getStrokeColor(),
                geometryUtil.toWkt(geometryUtil.buildRotatedRectangle(20, 10, 8.0, 2.0, 0.0)), List.of()));
        elements.add(new SimulationDtos.SnapElement(
                101L, "EL-EXT-1", InfrastructureType.EXIT_GATE, "East Exit Gate",
                105, 45, 6.0, 2.0, 90.0, 120.0, true,
                InfrastructureType.EXIT_GATE.getFillColor(), InfrastructureType.EXIT_GATE.getStrokeColor(),
                geometryUtil.toWkt(geometryUtil.buildRotatedRectangle(105, 45, 6.0, 2.0, 90.0)), List.of()));
        elements.add(new SimulationDtos.SnapElement(
                102L, "EL-STG-1", InfrastructureType.STAGE, "Main Stage",
                65, 72, 16.0, 8.0, 0.0, null, true,
                InfrastructureType.STAGE.getFillColor(), InfrastructureType.STAGE.getStrokeColor(),
                geometryUtil.toWkt(geometryUtil.buildRotatedRectangle(65, 72, 16.0, 8.0, 0.0)), List.of()));

        if (placeObstructingStall) {
            elements.add(new SimulationDtos.SnapElement(
                    103L, "EL-STALL-1", InfrastructureType.FOOD_STALL, "Obstructing Food Stall",
                    40, 28, 4.0, 3.0, 0.0, null, true,
                    InfrastructureType.FOOD_STALL.getFillColor(), InfrastructureType.FOOD_STALL.getStrokeColor(),
                    geometryUtil.toWkt(geometryUtil.buildRotatedRectangle(40, 28, 4.0, 3.0, 0.0)), List.of()));
        }

        List<SimulationDtos.SnapNode> nodes = new ArrayList<>();
        nodes.add(new SimulationDtos.SnapNode(1L, "NODE-ENT-1", PathwayNodeType.ENTRY_GATE, "North Entry",
                20, 10, null, 100L, "#10B981", "#047857"));
        nodes.add(new SimulationDtos.SnapNode(2L, "NODE-INT-1", PathwayNodeType.INTERSECTION, "Pinch Junction",
                40, 28, null, null, "#475569", "#1E293B"));
        nodes.add(new SimulationDtos.SnapNode(3L, "NODE-HUB-1", PathwayNodeType.ZONE_HUB, "Main Stage Lawn Hub",
                65, 45, 10L, null, "#3B82F6", "#1D4ED8"));
        nodes.add(new SimulationDtos.SnapNode(4L, "NODE-EXIT-1", PathwayNodeType.EXIT_GATE, "East Exit",
                105, 45, null, 101L, "#059669", "#065F46"));

        List<SimulationDtos.SnapEdge> edges = new ArrayList<>();
        edges.add(makeEdge(11L, "EDGE-ENTRY-1", "NODE-ENT-1", "NODE-INT-1", "Entry Approach",
                NetworkLayer.NORMAL_ENTRY, 5.0, 20, 10, 40, 28));
        edges.add(makeEdge(12L, "EDGE-PINCH-1", "NODE-INT-1", "NODE-HUB-1", "Central Promenade",
                NetworkLayer.INTERNAL_CIRCULATION, centralCorridorWidthM, 40, 28, 65, 45));
        edges.add(makeEdge(13L, "EDGE-EXIT-PRIMARY", "NODE-HUB-1", "NODE-EXIT-1", "East Exit Corridor",
                NetworkLayer.NORMAL_EXIT, 4.5, 65, 45, 105, 45));

        if (includeSecondaryExitRoute) {
            elements.add(new SimulationDtos.SnapElement(
                    104L, "EL-EEX-2", InfrastructureType.EMERGENCY_EXIT, "South Emergency Exit",
                    65, 86, 6.0, 2.0, 0.0, 140.0, true,
                    InfrastructureType.EMERGENCY_EXIT.getFillColor(), InfrastructureType.EMERGENCY_EXIT.getStrokeColor(),
                    geometryUtil.toWkt(geometryUtil.buildRotatedRectangle(65, 86, 6.0, 2.0, 0.0)), List.of()));
            nodes.add(new SimulationDtos.SnapNode(5L, "NODE-EEX-2", PathwayNodeType.EMERGENCY_EXIT, "South Emergency Exit",
                    65, 86, null, 104L, "#DC2626", "#991B1B"));
            edges.add(makeEdge(14L, "EDGE-EMG-SOUTH", "NODE-HUB-1", "NODE-EEX-2", "South Emergency Corridor",
                    NetworkLayer.EMERGENCY_ACCESS, 4.0, 65, 45, 65, 86));
            edges.add(makeEdge(15L, "EDGE-BYPASS-INT", "NODE-INT-1", "NODE-EEX-2", "West Bypass Walk",
                    NetworkLayer.INTERNAL_CIRCULATION, 4.0, 40, 28, 65, 86));
        }

        return new SimulationDtos.SerializedLayout(
                "Simulation Test Venue", 1500, "INTERACTIVE_MAP", "campus-quadrangle",
                false, null, null, null, null,
                boundaryWkt, boundaryPts, new GeometryUtil.Bounds(0, 0, 120, 90),
                List.of(), zones, elements, nodes, edges, List.of(), List.of());
    }

    private SimulationDtos.SnapEdge makeEdge(Long id, String uid, String fromUid, String toUid,
                                             String label, NetworkLayer layer, double widthM,
                                             double x1, double y1, double x2, double y2) {
        double len = Math.hypot(x2 - x1, y2 - y1);
        List<GeometryUtil.Point2D> pts = List.of(new GeometryUtil.Point2D(x1, y1), new GeometryUtil.Point2D(x2, y2));
        String wkt = geometryUtil.toWkt(geometryUtil.buildLineString(pts));
        return new SimulationDtos.SnapEdge(
                id, uid, fromUid, toUid, label, layer, widthM, len,
                PathwayDirection.BIDIRECTIONAL, true, layer.getColorHex(), layer.isDashed(), wkt, pts);
    }
}
