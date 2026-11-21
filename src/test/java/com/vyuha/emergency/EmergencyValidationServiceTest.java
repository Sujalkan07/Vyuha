package com.vyuha.emergency;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyuha.common.GeometryUtil;
import com.vyuha.emergency.EmergencyDtos.EmergencyValidationSummary;
import com.vyuha.emergency.EmergencyDtos.VehicleRouteStatusDto;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.NetworkLayer;
import com.vyuha.network.PathwayDirection;
import com.vyuha.network.PathwayEdge;
import com.vyuha.network.PathwayNode;
import com.vyuha.network.PathwayNodeType;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Polygon;

class EmergencyValidationServiceTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final EmergencyValidationService validationService = new EmergencyValidationService(geometryUtil);

    private Polygon defaultBoundary() {
        return geometryUtil.parsePolygon("POLYGON ((0 0, 120 0, 120 90, 0 90, 0 0))");
    }

    private EmergencyAsset asset(long id, EmergencyAssetType type, String label, String wkt, Double widthM) {
        EmergencyAsset a = new EmergencyAsset();
        a.setId(id);
        a.setAssetType(type);
        a.setLabel(label);
        a.setGeometryWkt(wkt);
        a.setMinWidthM(widthM);
        return a;
    }

    private InfrastructureElement infra(long id, InfrastructureType type, String label,
                                        double x, double y, double w, double h) {
        InfrastructureElement el = new InfrastructureElement();
        el.setId(id);
        el.setElementUid("el-" + id);
        el.setElementType(type);
        el.setLabel(label);
        el.setCenterX(x);
        el.setCenterY(y);
        el.setWidthM(w);
        el.setHeightM(h);
        el.setRotationDeg(0.0);
        el.setFootprintWkt(geometryUtil.toWkt(geometryUtil.buildRotatedRectangle(x, y, w, h, 0.0)));
        el.setActive(true);
        return el;
    }

    private EmergencyContact contact(long id) {
        EmergencyContact c = new EmergencyContact();
        c.setId(id);
        c.setAgencyName("City Fire & Rescue");
        c.setContactName("Duty Officer");
        c.setPhone("101");
        c.setRole("Fire Brigade");
        c.setPriorityOrder(1);
        return c;
    }

    @Test
    void validatesNarrowVsCompliantVehicleRoutesAndFlagsObstructions() {
        EmergencyAsset narrowAmb = asset(1L, EmergencyAssetType.AMBULANCE_ROUTE,
                "West Ambulance Path", "LINESTRING (10 10, 50 10)", 2.8);
        EmergencyAsset clearFire = asset(2L, EmergencyAssetType.FIRE_ROUTE,
                "North Fire Route", "LINESTRING (10 80, 90 80)", 4.0);
        EmergencyAsset obstructedAmb = asset(3L, EmergencyAssetType.AMBULANCE_ROUTE,
                "East Ambulance Route", "LINESTRING (80 10, 80 70)", 4.0);
        EmergencyAsset exit = asset(4L, EmergencyAssetType.EMERGENCY_EXIT,
                "East Emergency Exit", "POINT (120 45)", 4.0);
        EmergencyAsset muster = asset(5L, EmergencyAssetType.ASSEMBLY_POINT,
                "External Muster Lawn", "POLYGON ((125 20, 145 20, 145 50, 125 50, 125 20))", null);

        InfrastructureElement foodStall = infra(10L, InfrastructureType.FOOD_STALL,
                "Food Stall #3", 80.0, 40.0, 4.0, 3.0);

        ExclusionArea genRoom = new ExclusionArea();
        genRoom.setLabel("Generator Building");
        genRoom.setExclusionType(ExclusionType.BUILDING);
        genRoom.setPolygonWkt("POLYGON ((78 55, 84 55, 84 62, 78 62, 78 55))");

        EmergencyValidationSummary summary = validationService.validate(
                defaultBoundary(),
                List.of(genRoom),
                List.of(foodStall),
                List.of(),
                List.of(),
                List.of(narrowAmb, clearFire, obstructedAmb, exit, muster),
                List.of(contact(1L))
        );

        assertThat(summary.totalVehicleRoutes()).isEqualTo(3);
        assertThat(summary.compliantVehicleRoutes()).isEqualTo(1);
        assertThat(summary.narrowRouteCount()).isEqualTo(1);
        assertThat(summary.obstructedRouteCount()).isEqualTo(1);
        assertThat(summary.hasCriticalWarnings()).isTrue();

        VehicleRouteStatusDto eastStatus = summary.routeStatuses().stream()
                .filter(r -> r.label().equals("East Ambulance Route"))
                .findFirst().orElseThrow();
        assertThat(eastStatus.compliant()).isFalse();
        assertThat(eastStatus.obstacleCount()).isEqualTo(2);
        assertThat(eastStatus.obstacleLabels()).containsExactlyInAnyOrder("Food Stall #3", "Generator Building");

        assertThat(summary.findings()).anySatisfy(f -> {
            assertThat(f.code()).contains("EMERGENCY_ROUTE_NARROW");
            assertThat(f.severity()).isEqualTo(FindingSeverity.CRITICAL);
        });
        assertThat(summary.findings()).anySatisfy(f -> {
            assertThat(f.code()).contains("EMERGENCY_ROUTE_OBSTRUCTED");
            assertThat(f.severity()).isEqualTo(FindingSeverity.CRITICAL);
            assertThat(f.locateX()).isNotNull();
            assertThat(f.locateY()).isNotNull();
        });
    }

    @Test
    void validatesInfrastructureLanesAndEmergencyPathwayEdges() {
        InfrastructureElement narrowLane = infra(1L, InfrastructureType.AMBULANCE_LANE,
                "South Ambulance Lane", 60.0, 20.0, 20.0, 3.0);
        InfrastructureElement barricade = infra(2L, InfrastructureType.BARRICADE,
                "Blocking Barrier", 60.0, 20.0, 4.0, 1.0);
        InfrastructureElement fireLane = infra(3L, InfrastructureType.FIRE_ACCESS_LANE,
                "East Fire Lane", 100.0, 50.0, 4.5, 22.0);
        InfrastructureElement emExit = infra(4L, InfrastructureType.EMERGENCY_EXIT,
                "North Emergency Exit", 60.0, 0.0, 5.0, 2.0);

        PathwayNode n1 = new PathwayNode();
        n1.setId(1L);
        n1.setNodeUid("n1");
        n1.setNodeType(PathwayNodeType.ZONE_HUB);
        n1.setLabel("Hub 1");
        n1.setX(20.0);
        n1.setY(60.0);
        PathwayNode n2 = new PathwayNode();
        n2.setId(2L);
        n2.setNodeUid("n2");
        n2.setNodeType(PathwayNodeType.EMERGENCY_EXIT);
        n2.setLabel("North Em Exit");
        n2.setX(50.0);
        n2.setY(60.0);

        PathwayEdge narrowEdge = new PathwayEdge();
        narrowEdge.setId(10L);
        narrowEdge.setEdgeUid("e10");
        narrowEdge.setFromNode(n1);
        narrowEdge.setToNode(n2);
        narrowEdge.setLabel("Emergency Stem");
        narrowEdge.setNetworkLayer(NetworkLayer.EMERGENCY_ACCESS);
        narrowEdge.setWidthM(2.6);
        narrowEdge.setLengthM(30.0);
        narrowEdge.setDirection(PathwayDirection.BIDIRECTIONAL);
        narrowEdge.setPolylineWkt("LINESTRING (20 60, 50 60)");
        narrowEdge.setActive(true);

        InfrastructureElement edgeStall = infra(5L, InfrastructureType.FOOD_STALL,
                "Edge Stall", 35.0, 60.0, 4.0, 3.0);

        EmergencyAsset musterInside = asset(1L, EmergencyAssetType.ASSEMBLY_POINT,
                "Inner Lawn", "POLYGON ((10 10, 25 10, 25 25, 10 25, 10 10))", null);

        EmergencyValidationSummary summary = validationService.validate(
                defaultBoundary(),
                List.of(),
                List.of(narrowLane, barricade, fireLane, emExit, edgeStall),
                List.of(n1, n2),
                List.of(narrowEdge),
                List.of(musterInside),
                List.of(contact(1L))
        );

        assertThat(summary.totalVehicleRoutes()).isEqualTo(3);
        assertThat(summary.compliantVehicleRoutes()).isEqualTo(1);
        assertThat(summary.narrowRouteCount()).isEqualTo(2);
        assertThat(summary.obstructedRouteCount()).isEqualTo(2);
        assertThat(summary.findings()).extracting(EmergencyDtos.EmergencyValidationFinding::code)
                .anyMatch(c -> c.startsWith("EMERGENCY_LANE_NARROW"))
                .anyMatch(c -> c.startsWith("EMERGENCY_LANE_OBSTRUCTED"))
                .anyMatch(c -> c.startsWith("EMERGENCY_CORRIDOR_NARROW"))
                .anyMatch(c -> c.startsWith("EMERGENCY_CORRIDOR_OBSTRUCTED"))
                .anyMatch(c -> c.startsWith("ASSEMBLY_POINT_INSIDE_VENUE"));
    }

    @Test
    void flagsMissingExitsRoutesAssemblyPointsAndContacts() {
        EmergencyValidationSummary summary = validationService.validate(
                defaultBoundary(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertThat(summary.emergencyExitCount()).isZero();
        assertThat(summary.assemblyPointCount()).isZero();
        assertThat(summary.contactCount()).isZero();
        assertThat(summary.findings()).extracting(EmergencyDtos.EmergencyValidationFinding::code)
                .containsExactlyInAnyOrder(
                        "MISSING_AMBULANCE_ACCESS",
                        "MISSING_FIRE_ACCESS",
                        "NO_ASSEMBLY_POINT",
                        "NO_EMERGENCY_CONTACTS",
                        "NO_EMERGENCY_EXIT"
                );
    }
}
