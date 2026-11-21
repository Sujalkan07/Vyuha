package com.vyuha.emergency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.vyuha.common.GeometryUtil;
import com.vyuha.emergency.EmergencyDtos.ExitOutageStatusDto;
import com.vyuha.emergency.EmergencyDtos.OutageAnalysisResult;
import com.vyuha.emergency.EmergencyDtos.ZoneOutageImpactDto;
import com.vyuha.emergency.EmergencyDtos.ZoneOutageStatus;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.MovementGraphService;
import com.vyuha.network.NetworkLayer;
import com.vyuha.network.PathwayDirection;
import com.vyuha.network.PathwayEdge;
import com.vyuha.network.PathwayNode;
import com.vyuha.network.PathwayNodeType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.LineString;

class OutageAnalysisServiceTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final MovementGraphService graphService = new MovementGraphService();
    private final OutageAnalysisService outageService = new OutageAnalysisService(graphService, geometryUtil);

    private FunctionalZone zone(long id, String code, String name, String wkt) {
        FunctionalZone z = new FunctionalZone();
        z.setZoneCode(code);
        z.setName(name);
        z.setZoneType(ZoneType.AUDIENCE);
        z.setColorHex(ZoneType.AUDIENCE.getFillColor());
        z.setPolygonWkt(wkt);
        return z;
    }

    private InfrastructureElement exitInfra(long id, InfrastructureType type, String label,
                                            double x, double y, double widthM, double throughput) {
        InfrastructureElement el = new InfrastructureElement();
        el.setId(id);
        el.setElementUid("ex-" + id);
        el.setElementType(type);
        el.setLabel(label);
        el.setCenterX(x);
        el.setCenterY(y);
        el.setWidthM(widthM);
        el.setHeightM(2.0);
        el.setFootprintWkt(geometryUtil.toWkt(geometryUtil.buildRotatedRectangle(x, y, widthM, 2.0, 0.0)));
        el.setThroughputPersonsPerMin(throughput);
        el.setActive(true);
        return el;
    }

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

    private PathwayEdge edge(long id, String uid, PathwayNode from, PathwayNode to,
                             NetworkLayer layer, double widthM, PathwayDirection dir) {
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
        e.setDirection(dir);
        e.setPolylineWkt(geometryUtil.toWkt(ls));
        e.setActive(true);
        return e;
    }

    @Test
    void closingNearestExitReroutesZoneToBackupWithExactDetourAndEgressLoss() {
        FunctionalZone aud = zone(1L, "AUD-1", "Main Audience", "POLYGON ((20 20, 40 20, 40 40, 20 40, 20 20))");
        InfrastructureElement exitA = exitInfra(101L, InfrastructureType.EXIT_GATE, "West Exit A", 10, 30, 5.0, 80.0);
        InfrastructureElement exitB = exitInfra(102L, InfrastructureType.EMERGENCY_EXIT, "East Em Exit B", 75, 30, 5.0, 80.0);

        PathwayNode hub = node(1L, "hub", PathwayNodeType.ZONE_HUB, "Main Hub", 30, 30);
        hub.setLinkedZone(aud);
        PathwayNode nA = node(2L, "nA", PathwayNodeType.EXIT_GATE, "West Exit A", 10, 30);
        nA.setLinkedInfra(exitA);
        PathwayNode nB = node(3L, "nB", PathwayNodeType.EMERGENCY_EXIT, "East Em Exit B", 75, 30);
        nB.setLinkedInfra(exitB);

        // Shortest baseline path is 20m to West Exit A; backup path is 45m (width 2.2m) to East Em Exit B
        PathwayEdge toA = edge(10L, "to-A", hub, nA, NetworkLayer.NORMAL_EXIT, 4.5, PathwayDirection.ONE_WAY);
        PathwayEdge toB = edge(11L, "to-B", hub, nB, NetworkLayer.EMERGENCY_ACCESS, 2.2, PathwayDirection.BIDIRECTIONAL);

        OutageAnalysisResult result = outageService.analyzeOutage(
                List.of(aud),
                List.of(exitA, exitB),
                List.of(hub, nA, nB),
                List.of(toA, toB),
                List.of(),
                Set.of(101L),
                Set.of(),
                Set.of(),
                Set.of(),
                false
        );

        assertThat(result.newlyIsolatedZoneCount()).isZero();
        assertThat(result.reroutedZoneCount()).isEqualTo(1);
        assertThat(result.egressWidthLossPct()).isCloseTo(50.0, within(0.1));
        assertThat(result.remainingEgressCapacityPct()).isCloseTo(50.0, within(0.1));
        assertThat(result.throughputLossPct()).isCloseTo(50.0, within(0.1));

        ZoneOutageImpactDto impact = result.zoneImpacts().get(0);
        assertThat(impact.status()).isEqualTo(ZoneOutageStatus.REROUTED);
        assertThat(impact.baselineExitLabel()).isEqualTo("West Exit A");
        assertThat(impact.baselineDistanceM()).isCloseTo(20.0, within(0.1));
        assertThat(impact.backupExitLabel()).isEqualTo("East Em Exit B");
        assertThat(impact.backupDistanceM()).isCloseTo(45.0, within(0.1));
        assertThat(impact.detourDistanceM()).isCloseTo(25.0, within(0.1));
        assertThat(impact.backupMinWidthM()).isCloseTo(2.2, within(0.01));
        assertThat(impact.backupNarrowWarning()).isTrue();
        assertThat(impact.backupPathPoints()).hasSize(2);
    }

    @Test
    void blockingBridgeCorridorIsolatesZoneAndCutsOffDownstreamExits() {
        FunctionalZone aud = zone(1L, "AUD-1", "Stage Pit", "POLYGON ((10 10, 30 10, 30 30, 10 30, 10 10))");
        InfrastructureElement exitA = exitInfra(101L, InfrastructureType.EXIT_GATE, "East Exit A", 90, 10, 4.0, 60.0);
        InfrastructureElement exitB = exitInfra(102L, InfrastructureType.EMERGENCY_EXIT, "East Em Exit B", 90, 30, 4.0, 100.0);

        PathwayNode hub = node(1L, "hub", PathwayNodeType.ZONE_HUB, "Stage Pit Hub", 20, 20);
        hub.setLinkedZone(aud);
        PathwayNode bridgeEnd = node(2L, "junc", PathwayNodeType.INTERSECTION, "Bridge Junction", 60, 20);
        PathwayNode nA = node(3L, "nA", PathwayNodeType.EXIT_GATE, "East Exit A", 90, 10);
        nA.setLinkedInfra(exitA);
        PathwayNode nB = node(4L, "nB", PathwayNodeType.EMERGENCY_EXIT, "East Em Exit B", 90, 30);
        nB.setLinkedInfra(exitB);

        PathwayEdge bridge = edge(10L, "bridge", hub, bridgeEnd, NetworkLayer.INTERNAL_CIRCULATION, 4.0, PathwayDirection.BIDIRECTIONAL);
        PathwayEdge branchA = edge(11L, "branch-A", bridgeEnd, nA, NetworkLayer.NORMAL_EXIT, 4.0, PathwayDirection.ONE_WAY);
        PathwayEdge branchB = edge(12L, "branch-B", bridgeEnd, nB, NetworkLayer.EMERGENCY_ACCESS, 4.0, PathwayDirection.BIDIRECTIONAL);

        OutageAnalysisResult result = outageService.analyzeOutage(
                List.of(aud),
                List.of(exitA, exitB),
                List.of(hub, bridgeEnd, nA, nB),
                List.of(bridge, branchA, branchB),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(10L),
                Set.of(),
                false
        );

        assertThat(result.newlyIsolatedZoneCount()).isEqualTo(1);
        assertThat(result.cutOffExitCount()).isEqualTo(2);
        assertThat(result.operationalExitCount()).isZero();
        assertThat(result.remainingEgressCapacityPct()).isCloseTo(0.0, within(0.01));
        assertThat(result.egressWidthLossPct()).isCloseTo(100.0, within(0.01));

        ZoneOutageImpactDto impact = result.zoneImpacts().get(0);
        assertThat(impact.status()).isEqualTo(ZoneOutageStatus.NEWLY_ISOLATED);
        assertThat(impact.lostDedicatedEmergencyExit()).isTrue();
        assertThat(impact.backupExitLabel()).isNull();

        assertThat(result.exitStatuses()).allSatisfy(es -> {
            assertThat(es.directlyDisabled()).isFalse();
            assertThat(es.cutOffByCorridorOutage()).isTrue();
            assertThat(es.operational()).isFalse();
        });
    }

    @Test
    void multiZoneAndEmergencyOnlyAndOneWayDirectionalityWorkDeterministically() {
        FunctionalZone zoneA = zone(1L, "AUD-A", "Zone A", "POLYGON ((10 10, 20 10, 20 20, 10 20, 10 10))");
        FunctionalZone zoneB = zone(2L, "AUD-B", "Zone B", "POLYGON ((50 10, 60 10, 60 20, 50 20, 50 10))");
        InfrastructureElement normalExit = exitInfra(201L, InfrastructureType.EXIT_GATE, "Normal Exit", 15, 50, 4.0, 80.0);
        InfrastructureElement emExit = exitInfra(202L, InfrastructureType.EMERGENCY_EXIT, "South Em Exit", 55, 50, 4.0, 100.0);

        PathwayNode hubA = node(1L, "hubA", PathwayNodeType.ZONE_HUB, "Hub A", 15, 15);
        hubA.setLinkedZone(zoneA);
        PathwayNode hubB = node(2L, "hubB", PathwayNodeType.ZONE_HUB, "Hub B", 55, 15);
        hubB.setLinkedZone(zoneB);
        PathwayNode nNorm = node(3L, "nNorm", PathwayNodeType.EXIT_GATE, "Normal Exit", 15, 50);
        nNorm.setLinkedInfra(normalExit);
        PathwayNode nEm = node(4L, "nEm", PathwayNodeType.EMERGENCY_EXIT, "South Em Exit", 55, 50);
        nEm.setLinkedInfra(emExit);

        // hubA -> nNorm (35m); hubB -> nEm (35m); ONE_WAY edge from nNorm -> hubA cannot be traversed backward!
        PathwayEdge eA = edge(1L, "eA", hubA, nNorm, NetworkLayer.NORMAL_EXIT, 4.0, PathwayDirection.ONE_WAY);
        PathwayEdge eB = edge(2L, "eB", hubB, nEm, NetworkLayer.EMERGENCY_ACCESS, 4.0, PathwayDirection.ONE_WAY);
        PathwayEdge wrongWay = edge(3L, "eWrong", nEm, hubB, NetworkLayer.INTERNAL_CIRCULATION, 4.0, PathwayDirection.ONE_WAY);

        // Close South Em Exit -> Zone A is unaffected, Zone B becomes NEWLY_ISOLATED
        OutageAnalysisResult closeEm = outageService.analyzeOutage(
                List.of(zoneA, zoneB),
                List.of(normalExit, emExit),
                List.of(hubA, hubB, nNorm, nEm),
                List.of(eA, eB, wrongWay),
                List.of(),
                Set.of(202L),
                Set.of(),
                Set.of(),
                Set.of(),
                false
        );

        assertThat(closeEm.unaffectedZoneCount()).isEqualTo(1);
        assertThat(closeEm.newlyIsolatedZoneCount()).isEqualTo(1);

        // Emergency-exits-only filter ignores Normal Exit even when nothing is disabled
        OutageAnalysisResult emergencyOnly = outageService.analyzeOutage(
                List.of(zoneA, zoneB),
                List.of(normalExit, emExit),
                List.of(hubA, hubB, nNorm, nEm),
                List.of(eA, eB, wrongWay),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                true
        );

        assertThat(emergencyOnly.activeExitCount()).isEqualTo(1);
        assertThat(emergencyOnly.alreadyIsolatedZoneCount()).isEqualTo(1);
        assertThat(emergencyOnly.unaffectedZoneCount()).isEqualTo(1);
    }

    @Test
    void infrastructureOnlyVenueWithoutPathwayGraphStillComputesEgressCapacityLoss() {
        InfrastructureElement exit1 = exitInfra(1L, InfrastructureType.EXIT_GATE, "Gate 1", 10, 0, 6.0, 90.0);
        InfrastructureElement exit2 = exitInfra(2L, InfrastructureType.EMERGENCY_EXIT, "Em Exit 2", 90, 0, 4.0, 60.0);

        OutageAnalysisResult res = outageService.analyzeOutage(
                List.of(),
                List.of(exit1, exit2),
                List.of(),
                List.of(),
                List.of(),
                Set.of(2L),
                Set.of(),
                Set.of(),
                Set.of(),
                false
        );

        assertThat(res.activeExitCount()).isEqualTo(2);
        assertThat(res.operationalExitCount()).isEqualTo(1);
        assertThat(res.baselineTotalEgressWidthM()).isCloseTo(10.0, within(0.01));
        assertThat(res.remainingEgressWidthM()).isCloseTo(6.0, within(0.01));
        assertThat(res.egressWidthLossPct()).isCloseTo(40.0, within(0.1));
        assertThat(res.remainingThroughputPerMin()).isCloseTo(90.0, within(0.1));

        ExitOutageStatusDto disabledDto = res.exitStatuses().stream()
                .filter(e -> e.infraId().equals(2L))
                .findFirst().orElseThrow();
        assertThat(disabledDto.directlyDisabled()).isTrue();
        assertThat(disabledDto.operational()).isFalse();
    }
}
