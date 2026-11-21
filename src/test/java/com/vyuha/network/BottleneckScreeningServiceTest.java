package com.vyuha.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.BottleneckScreeningService.DetectedBottleneck;
import com.vyuha.network.BottleneckScreeningService.ScreeningResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.LineString;

class BottleneckScreeningServiceTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final MovementGraphService graphService = new MovementGraphService();
    private final BottleneckScreeningService screeningService = new BottleneckScreeningService(geometryUtil, graphService);

    private PathwayNode node(long id, String uid, PathwayNodeType type, String label, double x, double y) {
        PathwayNode n = new PathwayNode();
        n.setId(id);
        n.setNodeUid(uid);
        n.setNodeType(type);
        n.setLabel(label);
        n.setX(x);
        n.setY(y);
        return n;
    }

    private PathwayEdge edge(long id,
                             String uid,
                             PathwayNode from,
                             PathwayNode to,
                             NetworkLayer layer,
                             double widthM,
                             PathwayDirection direction) {
        LineString ls = geometryUtil.buildLineString(List.of(
                new GeometryUtil.Point2D(from.getX(), from.getY()),
                new GeometryUtil.Point2D(to.getX(), to.getY())
        ));
        PathwayEdge e = new PathwayEdge();
        e.setId(id);
        e.setEdgeUid(uid);
        e.setFromNode(from);
        e.setToNode(to);
        e.setLabel(uid);
        e.setNetworkLayer(layer);
        e.setWidthM(widthM);
        e.setLengthM(geometryUtil.round2(ls.getLength()));
        e.setDirection(direction);
        e.setPolylineWkt(geometryUtil.toWkt(ls));
        e.setActive(true);
        return e;
    }

    private InfrastructureElement infra(long id, String uid, InfrastructureType type, String label,
                                        double x, double y, Double throughput) {
        InfrastructureElement el = new InfrastructureElement();
        el.setId(id);
        el.setElementUid(uid);
        el.setElementType(type);
        el.setLabel(label);
        el.setCenterX(x);
        el.setCenterY(y);
        el.setWidthM(type.getDefaultWidthM());
        el.setHeightM(type.getDefaultHeightM());
        el.setFootprintWkt(geometryUtil.toWkt(geometryUtil.buildRotatedRectangle(
                x, y, type.getDefaultWidthM(), type.getDefaultHeightM(), 0)));
        el.setThroughputPersonsPerMin(throughput);
        el.setActive(true);
        return el;
    }

    private FunctionalZone zone(long id, String code, ZoneType type, String wkt) {
        FunctionalZone z = new FunctionalZone();
        z.setZoneCode(code);
        z.setName(code);
        z.setZoneType(type);
        z.setColorHex(type.getFillColor());
        z.setPolygonWkt(wkt);
        return z;
    }

    @Test
    void cleanSeparatedNetworkProducesZeroBottlenecks() {
        InfrastructureElement gateIn = infra(1L, "g-in", InfrastructureType.ENTRY_GATE, "North Entry", 10, 10, 60.0);
        InfrastructureElement gateOut = infra(2L, "g-out", InfrastructureType.EXIT_GATE, "South Exit", 50, 70, 80.0);
        InfrastructureElement emExit = infra(3L, "g-em", InfrastructureType.EMERGENCY_EXIT, "East Em Exit", 90, 40, 100.0);
        FunctionalZone audZone = zone(1L, "AUD-1", ZoneType.AUDIENCE, "POLYGON ((20 20, 70 20, 70 50, 20 50, 20 20))");

        PathwayNode nIn = node(1L, "n-in", PathwayNodeType.ENTRY_GATE, "North Entry", 10, 10);
        nIn.setLinkedInfra(gateIn);
        PathwayNode nAud = node(2L, "n-aud", PathwayNodeType.ZONE_HUB, "Audience Hub", 45, 35);
        nAud.setLinkedZone(audZone);
        PathwayNode nOut = node(3L, "n-out", PathwayNodeType.EXIT_GATE, "South Exit", 50, 70);
        nOut.setLinkedInfra(gateOut);
        PathwayNode nEm = node(4L, "n-em", PathwayNodeType.EMERGENCY_EXIT, "East Em Exit", 90, 40);
        nEm.setLinkedInfra(emExit);

        PathwayEdge e1 = edge(1L, "e-in", nIn, nAud, NetworkLayer.NORMAL_ENTRY, 4.5, PathwayDirection.ONE_WAY);
        PathwayEdge e2 = edge(2L, "e-out", nAud, nOut, NetworkLayer.NORMAL_EXIT, 4.5, PathwayDirection.ONE_WAY);
        PathwayEdge e3 = edge(3L, "e-em", nAud, nEm, NetworkLayer.EMERGENCY_ACCESS, 4.0, PathwayDirection.BIDIRECTIONAL);

        ScreeningResult res = screeningService.screen(
                List.of(audZone),
                List.of(gateIn, gateOut, emExit),
                List.of(nIn, nAud, nOut, nEm),
                List.of(e1, e2, e3),
                2400, 60, null);

        assertThat(res.findings()).isEmpty();
        assertThat(res.connectedComponents()).isEqualTo(1);
        assertThat(res.connectedEntryCapacityPerMin()).isEqualTo(60.0);
        assertThat(res.estimatedArrivalDemandPerMin()).isEqualTo(40.0);
    }

    @Test
    void detectsDisconnectedZoneAndIsolatedOrReversedExits() {
        PathwayNode nIn = node(1L, "n-in", PathwayNodeType.ENTRY_GATE, "Entry", 10, 10);
        PathwayNode nAud = node(2L, "n-aud", PathwayNodeType.ZONE_HUB, "Main Audience", 30, 30);
        PathwayNode nExit = node(3L, "n-exit", PathwayNodeType.EXIT_GATE, "West Exit", 10, 50);
        PathwayNode nEm = node(4L, "n-em", PathwayNodeType.EMERGENCY_EXIT, "Isolated Em", 80, 80);

        PathwayEdge eIn = edge(1L, "e-in", nIn, nAud, NetworkLayer.NORMAL_ENTRY, 4.0, PathwayDirection.ONE_WAY);
        // Reversed one-way exit edge: points FROM exit TO audience!
        PathwayEdge eRevExit = edge(2L, "e-rev", nExit, nAud, NetworkLayer.NORMAL_EXIT, 4.0, PathwayDirection.ONE_WAY);

        ScreeningResult res = screeningService.screen(
                List.of(), List.of(),
                List.of(nIn, nAud, nExit, nEm),
                List.of(eIn, eRevExit),
                null, null, null);

        assertThat(res.findings())
                .anySatisfy(f -> {
                    assertThat(f.findingType()).isEqualTo(BottleneckFindingType.DISCONNECTED_ZONE);
                    assertThat(f.severity()).isEqualTo(FindingSeverity.CRITICAL);
                    assertThat(f.measuredValue()).contains("Normal Exit").contains("Emergency Exit");
                })
                .anySatisfy(f -> {
                    assertThat(f.findingType()).isEqualTo(BottleneckFindingType.ISOLATED_EXIT);
                    assertThat(f.findingKey()).isEqualTo("ISOLATED_EXIT:n-exit");
                })
                .anySatisfy(f -> {
                    assertThat(f.findingType()).isEqualTo(BottleneckFindingType.ISOLATED_EXIT);
                    assertThat(f.findingKey()).isEqualTo("ISOLATED_EXIT:n-em");
                });
    }

    @Test
    void detectsDeadEndStemsExceeding10mAndOneWayTraps() {
        PathwayNode junc = node(1L, "junc", PathwayNodeType.INTERSECTION, "Junction", 10, 10);
        PathwayNode nIn = node(2L, "in", PathwayNodeType.ENTRY_GATE, "In", 0, 10);
        PathwayNode nOut = node(3L, "out", PathwayNodeType.EXIT_GATE, "Out", 10, 25);
        PathwayNode mid = node(4L, "mid", PathwayNodeType.INTERSECTION, "Mid", 18, 10);
        PathwayNode longTip = node(5L, "long-tip", PathwayNodeType.INTERSECTION, "Long Tip", 24, 10); // 8+6 = 14 m
        PathwayNode shortTip = node(6L, "short-tip", PathwayNodeType.INTERSECTION, "Short Tip", 10, 2); // 8 m <= 10 m
        PathwayNode trapNode = node(7L, "trap", PathwayNodeType.INTERSECTION, "Trap Node", 40, 40);

        List<PathwayEdge> edges = List.of(
                edge(1L, "e1", nIn, junc, NetworkLayer.NORMAL_ENTRY, 4.0, PathwayDirection.ONE_WAY),
                edge(2L, "e2", junc, nOut, NetworkLayer.NORMAL_EXIT, 4.0, PathwayDirection.ONE_WAY),
                edge(3L, "e3", junc, mid, NetworkLayer.INTERNAL_CIRCULATION, 3.0, PathwayDirection.BIDIRECTIONAL),
                edge(4L, "e4", mid, longTip, NetworkLayer.INTERNAL_CIRCULATION, 3.0, PathwayDirection.BIDIRECTIONAL),
                edge(5L, "e5", junc, shortTip, NetworkLayer.INTERNAL_CIRCULATION, 3.0, PathwayDirection.BIDIRECTIONAL),
                edge(6L, "e6", nIn, trapNode, NetworkLayer.INTERNAL_CIRCULATION, 3.0, PathwayDirection.ONE_WAY),
                edge(7L, "e7", nOut, trapNode, NetworkLayer.INTERNAL_CIRCULATION, 3.0, PathwayDirection.ONE_WAY)
        );

        ScreeningResult res = screeningService.screen(
                List.of(), List.of(),
                List.of(junc, nIn, nOut, mid, longTip, shortTip, trapNode),
                edges, null, null, null);

        List<DetectedBottleneck> deadEnds = res.findings().stream()
                .filter(f -> f.findingType() == BottleneckFindingType.DEAD_END_CORRIDOR)
                .toList();
        assertThat(deadEnds).extracting(DetectedBottleneck::findingKey)
                .containsExactlyInAnyOrder("DEAD_END_CORRIDOR:long-tip", "DEAD_END_CORRIDOR:trap");
    }

    @Test
    void detectsNarrowWalkwaysAcrossStandardAndEmergencyThresholds() {
        PathwayNode a = node(1L, "a", PathwayNodeType.ENTRY_GATE, "A", 0, 0);
        PathwayNode b = node(2L, "b", PathwayNodeType.ZONE_HUB, "B", 20, 0);
        PathwayNode c = node(3L, "c", PathwayNodeType.EXIT_GATE, "C", 40, 0);
        PathwayNode d = node(4L, "d", PathwayNodeType.EMERGENCY_EXIT, "D", 20, 20);
        PathwayNode e = node(5L, "e", PathwayNodeType.EMERGENCY_EXIT, "E", 20, -20);

        PathwayEdge critPinch = edge(1L, "e-crit", a, b, NetworkLayer.NORMAL_ENTRY, 1.2, PathwayDirection.ONE_WAY);
        PathwayEdge highNarrow = edge(2L, "e-high", b, c, NetworkLayer.NORMAL_EXIT, 1.8, PathwayDirection.ONE_WAY);
        PathwayEdge emNarrow = edge(3L, "e-em-bad", b, d, NetworkLayer.EMERGENCY_ACCESS, 3.0, PathwayDirection.BIDIRECTIONAL);
        PathwayEdge emWide = edge(4L, "e-em-ok", b, e, NetworkLayer.EMERGENCY_ACCESS, 4.0, PathwayDirection.BIDIRECTIONAL);

        ScreeningResult res = screeningService.screen(
                List.of(), List.of(),
                List.of(a, b, c, d, e),
                List.of(critPinch, highNarrow, emNarrow, emWide),
                null, null, null);

        List<DetectedBottleneck> narrow = res.findings().stream()
                .filter(f -> f.findingType() == BottleneckFindingType.NARROW_WALKWAY)
                .toList();

        assertThat(narrow).hasSize(3);
        assertThat(narrow).anySatisfy(f -> {
            assertThat(f.findingKey()).isEqualTo("NARROW_WALKWAY:e-crit");
            assertThat(f.severity()).isEqualTo(FindingSeverity.CRITICAL);
        });
        assertThat(narrow).anySatisfy(f -> {
            assertThat(f.findingKey()).isEqualTo("NARROW_WALKWAY:e-high");
            assertThat(f.severity()).isEqualTo(FindingSeverity.HIGH);
        });
        assertThat(narrow).anySatisfy(f -> {
            assertThat(f.findingKey()).isEqualTo("NARROW_WALKWAY:e-em-bad");
            assertThat(f.severity()).isEqualTo(FindingSeverity.CRITICAL);
        });
    }

    @Test
    void detectsHighConvergenceMergesAndIgnoresBalancedFourWayCrossroads() {
        PathwayNode z1 = node(1L, "z1", PathwayNodeType.ZONE_HUB, "Z1", 0, 0);
        PathwayNode z2 = node(2L, "z2", PathwayNodeType.ZONE_HUB, "Z2", 0, 10);
        PathwayNode z3 = node(3L, "z3", PathwayNodeType.ZONE_HUB, "Z3", 0, 20);
        PathwayNode merge = node(4L, "merge", PathwayNodeType.INTERSECTION, "Merge Junc", 20, 10);
        PathwayNode exit = node(5L, "exit", PathwayNodeType.EXIT_GATE, "Main Exit", 40, 10);

        PathwayEdge in1 = edge(1L, "in1", z1, merge, NetworkLayer.NORMAL_EXIT, 2.5, PathwayDirection.ONE_WAY);
        PathwayEdge in2 = edge(2L, "in2", z2, merge, NetworkLayer.NORMAL_EXIT, 2.5, PathwayDirection.ONE_WAY);
        PathwayEdge in3 = edge(3L, "in3", z3, merge, NetworkLayer.NORMAL_EXIT, 2.5, PathwayDirection.ONE_WAY); // total = 7.5 m
        PathwayEdge narrowOut = edge(4L, "out", merge, exit, NetworkLayer.NORMAL_EXIT, 2.5, PathwayDirection.ONE_WAY); // 2.5 < 5.625

        ScreeningResult constricted = screeningService.screen(
                List.of(), List.of(),
                List.of(z1, z2, z3, merge, exit),
                List.of(in1, in2, in3, narrowOut),
                null, null, null);
        assertThat(constricted.findings())
                .anySatisfy(f -> {
                    assertThat(f.findingType()).isEqualTo(BottleneckFindingType.HIGH_CONVERGENCE_MERGE);
                    assertThat(f.severity()).isEqualTo(FindingSeverity.HIGH);
                });

        // Widening outgoing edge to 6.0 m (>= 75% of 7.5 = 5.625 m) clears merge constriction
        PathwayEdge wideOut = edge(4L, "out", merge, exit, NetworkLayer.NORMAL_EXIT, 6.0, PathwayDirection.ONE_WAY);
        ScreeningResult cleared = screeningService.screen(
                List.of(), List.of(),
                List.of(z1, z2, z3, merge, exit),
                List.of(in1, in2, in3, wideOut),
                null, null, null);
        assertThat(cleared.findings())
                .noneMatch(f -> f.findingType() == BottleneckFindingType.HIGH_CONVERGENCE_MERGE);

        // Symmetrical 4-way 4.0m bidirectional intersection does not falsely trigger
        PathwayNode cross = node(10L, "cross", PathwayNodeType.INTERSECTION, "Cross", 50, 50);
        PathwayNode n = node(11L, "n", PathwayNodeType.ENTRY_GATE, "N", 50, 30);
        PathwayNode s = node(12L, "s", PathwayNodeType.ZONE_HUB, "S", 50, 70);
        PathwayNode w = node(13L, "w", PathwayNodeType.ZONE_HUB, "W", 30, 50);
        PathwayNode e = node(14L, "e", PathwayNodeType.ZONE_HUB, "E", 70, 50);
        ScreeningResult balanced = screeningService.screen(
                List.of(), List.of(),
                List.of(cross, n, s, w, e),
                List.of(
                        edge(10L, "cn", n, cross, NetworkLayer.INTERNAL_CIRCULATION, 4.0, PathwayDirection.BIDIRECTIONAL),
                        edge(11L, "cs", s, cross, NetworkLayer.INTERNAL_CIRCULATION, 4.0, PathwayDirection.BIDIRECTIONAL),
                        edge(12L, "cw", w, cross, NetworkLayer.INTERNAL_CIRCULATION, 4.0, PathwayDirection.BIDIRECTIONAL),
                        edge(13L, "ce", e, cross, NetworkLayer.INTERNAL_CIRCULATION, 4.0, PathwayDirection.BIDIRECTIONAL)
                ),
                null, null, null);
        assertThat(balanced.findings())
                .noneMatch(f -> f.findingType() == BottleneckFindingType.HIGH_CONVERGENCE_MERGE);
    }

    @Test
    void detectsContraFlowCrossingNarrowBidirectionalAndQueueCrossings() {
        PathwayNode inStart = node(1L, "in-s", PathwayNodeType.ENTRY_GATE, "In Gate", 10, 10);
        PathwayNode aud = node(2L, "aud", PathwayNodeType.ZONE_HUB, "Audience", 50, 50);
        PathwayNode outEnd = node(3L, "out-e", PathwayNodeType.EXIT_GATE, "Out Gate", 50, 10);
        PathwayNode xStart = node(4L, "xs", PathwayNodeType.ZONE_HUB, "West Hub", 10, 50);

        // Diagonal entry (10,10)->(50,50) crosses exit (10,50)->(50,10) at (30,30)
        // Plus both are narrow bidirectional (<4.0m) -> CONTRA_FLOW_CROSSING (CRITICAL) + CONTRA_FLOW_NARROW_BI (HIGH)
        PathwayEdge entryDiag = edge(1L, "e-entry", inStart, aud, NetworkLayer.NORMAL_ENTRY, 3.0, PathwayDirection.BIDIRECTIONAL);
        PathwayEdge exitDiag = edge(2L, "e-exit", xStart, outEnd, NetworkLayer.NORMAL_EXIT, 3.2, PathwayDirection.BIDIRECTIONAL);

        // Queue zone [20..40, 20..40] crossed by exitDiag
        FunctionalZone queueZone = zone(1L, "QUE-1", ZoneType.QUEUE, "POLYGON ((20 20, 40 20, 40 40, 20 40, 20 20))");

        ScreeningResult res = screeningService.screen(
                List.of(queueZone), List.of(),
                List.of(inStart, aud, outEnd, xStart),
                List.of(entryDiag, exitDiag),
                null, null, null);

        assertThat(res.findings())
                .anySatisfy(f -> {
                    assertThat(f.findingType()).isEqualTo(BottleneckFindingType.CONTRA_FLOW_CONFLICT);
                    assertThat(f.severity()).isEqualTo(FindingSeverity.CRITICAL);
                    assertThat(f.findingKey()).startsWith("CONTRA_FLOW_CROSSING:");
                    assertThat(f.locationX()).isCloseTo(30.0, org.assertj.core.data.Offset.offset(0.1));
                    assertThat(f.locationY()).isCloseTo(30.0, org.assertj.core.data.Offset.offset(0.1));
                })
                .anySatisfy(f -> {
                    assertThat(f.findingKey()).isEqualTo("CONTRA_FLOW_NARROW_BI:e-entry");
                    assertThat(f.severity()).isEqualTo(FindingSeverity.HIGH);
                })
                .anySatisfy(f -> {
                    assertThat(f.findingType()).isEqualTo(BottleneckFindingType.CROSSING_QUEUE_CONFLICT);
                    assertThat(f.severity()).isEqualTo(FindingSeverity.HIGH);
                });
    }

    @Test
    void detectsEntryGateOverloadProportionallyAndClearsWhenGateCapacitySuffices() {
        InfrastructureElement gate1 = infra(1L, "g1", InfrastructureType.ENTRY_GATE, "Gate 1", 10, 10, 40.0);
        PathwayNode nIn = node(1L, "n-in", PathwayNodeType.ENTRY_GATE, "Gate 1", 10, 10);
        nIn.setLinkedInfra(gate1);
        PathwayNode nAud = node(2L, "n-aud", PathwayNodeType.ZONE_HUB, "Audience", 40, 10);
        PathwayNode nOut = node(3L, "n-out", PathwayNodeType.EXIT_GATE, "Exit", 60, 10);
        PathwayNode nEm = node(4L, "n-em", PathwayNodeType.EMERGENCY_EXIT, "Em Exit", 40, 30);

        PathwayEdge eIn = edge(1L, "e1", nIn, nAud, NetworkLayer.NORMAL_ENTRY, 4.0, PathwayDirection.ONE_WAY);
        PathwayEdge eOut = edge(2L, "e2", nAud, nOut, NetworkLayer.NORMAL_EXIT, 4.0, PathwayDirection.ONE_WAY);
        PathwayEdge eEm = edge(3L, "e3", nAud, nEm, NetworkLayer.EMERGENCY_ACCESS, 4.0, PathwayDirection.BIDIRECTIONAL);

        // 2700 attendees over 60 min = 45 p/m vs 40 p/m (112.5%) -> HIGH
        ScreeningResult highOverload = screeningService.screen(
                List.of(), List.of(gate1),
                List.of(nIn, nAud, nOut, nEm), List.of(eIn, eOut, eEm),
                2700, 60, null);
        assertThat(highOverload.findings())
                .anySatisfy(f -> {
                    assertThat(f.findingType()).isEqualTo(BottleneckFindingType.ENTRY_GATE_OVERLOAD);
                    assertThat(f.severity()).isEqualTo(FindingSeverity.HIGH);
                });

        // 3600 attendees over 60 min = 60 p/m vs 40 p/m (150% > 125%) -> CRITICAL
        ScreeningResult critOverload = screeningService.screen(
                List.of(), List.of(gate1),
                List.of(nIn, nAud, nOut, nEm), List.of(eIn, eOut, eEm),
                3600, 60, null);
        assertThat(critOverload.findings())
                .anySatisfy(f -> {
                    assertThat(f.findingType()).isEqualTo(BottleneckFindingType.ENTRY_GATE_OVERLOAD);
                    assertThat(f.severity()).isEqualTo(FindingSeverity.CRITICAL);
                });

        // Increase gate rate to 75 p/m -> clears overload
        gate1.setThroughputPersonsPerMin(75.0);
        ScreeningResult ok = screeningService.screen(
                List.of(), List.of(gate1),
                List.of(nIn, nAud, nOut, nEm), List.of(eIn, eOut, eEm),
                3600, 60, null);
        assertThat(ok.findings())
                .noneMatch(f -> f.findingType() == BottleneckFindingType.ENTRY_GATE_OVERLOAD);
    }
}
