package com.vyuha.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.ZoneType;
import com.vyuha.grid.ZoneValidationService;
import com.vyuha.venue.AreaBreakdownService;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Polygon;

class ConflictDetectionServiceTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AreaBreakdownService areaBreakdownService = new AreaBreakdownService(geometryUtil);
    private final ZoneValidationService zoneValidationService = new ZoneValidationService(geometryUtil, areaBreakdownService);
    private final ConflictDetectionService detector = new ConflictDetectionService(
            geometryUtil, zoneValidationService, objectMapper);

    private Polygon boundary100x80() {
        return geometryUtil.parsePolygon("POLYGON ((0 0, 100 0, 100 80, 0 80, 0 0))");
    }

    private InfrastructureElement element(long id,
                                          String uid,
                                          InfrastructureType type,
                                          String label,
                                          double cx,
                                          double cy,
                                          double w,
                                          double h,
                                          double rotDeg) {
        return element(id, uid, type, label, cx, cy, w, h, rotDeg, true, null);
    }

    private InfrastructureElement element(long id,
                                          String uid,
                                          InfrastructureType type,
                                          String label,
                                          double cx,
                                          double cy,
                                          double w,
                                          double h,
                                          double rotDeg,
                                          boolean active,
                                          String metadataJson) {
        InfrastructureElement el = new InfrastructureElement();
        el.setId(id);
        el.setElementUid(uid);
        el.setElementType(type);
        el.setLabel(label);
        el.setCenterX(cx);
        el.setCenterY(cy);
        el.setWidthM(w);
        el.setHeightM(h);
        el.setRotationDeg(rotDeg);
        el.setFootprintWkt(geometryUtil.toWkt(geometryUtil.buildRotatedRectangle(cx, cy, w, h, rotDeg)));
        el.setActive(active);
        el.setMetadataJson(metadataJson);
        return el;
    }

    private FunctionalZone zone(String code, ZoneType type, String wkt) {
        FunctionalZone z = new FunctionalZone();
        z.setZoneCode(code);
        z.setName(code);
        z.setZoneType(type);
        z.setColorHex(type.getFillColor());
        z.setPolygonWkt(wkt);
        return z;
    }

    private ExclusionArea exclusion(String label, ExclusionType type, String wkt) {
        ExclusionArea ex = new ExclusionArea();
        ex.setLabel(label);
        ex.setExclusionType(type);
        ex.setPolygonWkt(wkt);
        return ex;
    }

    @Test
    void detectsRotatedFootprintIntersectionAndIgnoresTouchingEdges() {
        Polygon boundary = boundary100x80();
        // Stall A at (30, 30) 6x2 m; Stall B at (34, 32.5) 6x2 m
        // At 0 deg: y ranges are [29..31] and [31.5..33.5] -> 0.5 m clear gap
        InfrastructureElement a0 = element(1L, "u1", InfrastructureType.FOOD_STALL, "Stall A", 30, 30, 6, 2, 0);
        InfrastructureElement b0 = element(2L, "u2", InfrastructureType.FOOD_STALL, "Stall B", 34, 32.5, 6, 2, 0);
        assertThat(detector.detect(boundary, List.of(), List.of(), List.of(a0, b0)))
                .noneMatch(f -> f.conflictType() == ConflictType.OVERLAPPING_RIGID_STRUCTURES);

        // Rotate Stall B by 45 deg -> its diagonal corner sweeps across y=31 and overlaps Stall A
        InfrastructureElement b45 = element(2L, "u2", InfrastructureType.FOOD_STALL, "Stall B", 34, 32.5, 6, 2, 45);
        List<ConflictDetectionService.DetectedConflict> rotatedConflicts =
                detector.detect(boundary, List.of(), List.of(), List.of(a0, b45));
        assertThat(rotatedConflicts)
                .anySatisfy(c -> {
                    assertThat(c.conflictType()).isEqualTo(ConflictType.OVERLAPPING_RIGID_STRUCTURES);
                    assertThat(c.severity()).isEqualTo(FindingSeverity.HIGH);
                    assertThat(c.measuredValue()).contains("m² overlap");
                });

        // Touching edges along x=23 (zero area intersection) -> clean
        InfrastructureElement t1 = element(3L, "u3", InfrastructureType.FOOD_STALL, "Touch 1", 20, 50, 6, 4, 0);
        InfrastructureElement t2 = element(4L, "u4", InfrastructureType.FOOD_STALL, "Touch 2", 26, 50, 6, 4, 0);
        assertThat(detector.detect(boundary, List.of(), List.of(), List.of(t1, t2)))
                .noneMatch(f -> f.conflictType() == ConflictType.OVERLAPPING_RIGID_STRUCTURES);
    }

    @Test
    void detectsExitClearanceApronObstructionWithDefaultAndCustomBuffers() {
        Polygon boundary = boundary100x80();
        // Exit Gate centered at (50, 75), 6x2 -> spans x:[47..53], y:[74..76]
        InfrastructureElement exit = element(1L, "ex1", InfrastructureType.EXIT_GATE, "South Exit", 50, 75, 6, 2, 0);
        // Barricade at (50, 71.5), 6x1 -> y:[71..72], distance to exit = 2.0 m < 3.0 m default apron
        InfrastructureElement barricadeClose = element(
                2L, "bar1", InfrastructureType.BARRICADE, "North Barricade", 50, 71.5, 6, 1, 0);

        List<ConflictDetectionService.DetectedConflict> closeFindings =
                detector.detect(boundary, List.of(), List.of(), List.of(exit, barricadeClose));
        assertThat(closeFindings)
                .anySatisfy(c -> {
                    assertThat(c.conflictType()).isEqualTo(ConflictType.EXIT_OBSTRUCTION);
                    assertThat(c.severity()).isEqualTo(FindingSeverity.CRITICAL);
                    assertThat(c.measuredValue()).contains("2.00 m clearance");
                });

        // Move barricade to y=70.3 -> edge y=70.8 -> distance = 3.2 m > 3.0 m -> clear
        InfrastructureElement barricadeClear = element(
                2L, "bar1", InfrastructureType.BARRICADE, "North Barricade", 50, 70.3, 6, 1, 0);
        assertThat(detector.detect(boundary, List.of(), List.of(), List.of(exit, barricadeClear)))
                .noneMatch(f -> f.conflictType() == ConflictType.EXIT_OBSTRUCTION);

        // Increase exit apron to 5.0 m -> 3.2 m is now obstructed
        InfrastructureElement exitCustom5m = element(
                1L, "ex1", InfrastructureType.EXIT_GATE, "South Exit", 50, 75, 6, 2, 0,
                true, "{\"clearanceBufferM\":5.0}");
        assertThat(detector.detect(boundary, List.of(), List.of(), List.of(exitCustom5m, barricadeClear)))
                .anySatisfy(c -> {
                    assertThat(c.conflictType()).isEqualTo(ConflictType.EXIT_OBSTRUCTION);
                    assertThat(c.measuredValue()).contains("min 5.00 m");
                });
    }

    @Test
    void detectsEmergencyCorridorZoneAndVehicleLaneIntrusions() {
        Polygon boundary = boundary100x80();
        FunctionalZone emgCorridor = zone("EMG-1", ZoneType.EMERGENCY_CORRIDOR,
                "POLYGON ((10 10, 16 10, 16 70, 10 70, 10 10))");
        InfrastructureElement ambulanceLane = element(
                1L, "amb1", InfrastructureType.AMBULANCE_LANE, "East Ambulance Lane", 85, 40, 4, 40, 0);
        InfrastructureElement stallInZone = element(
                2L, "st1", InfrastructureType.FOOD_STALL, "Stall West", 14, 30, 4, 3, 0);
        InfrastructureElement stallInLane = element(
                3L, "st2", InfrastructureType.FOOD_STALL, "Stall East", 84, 35, 4, 3, 0);
        // Emergency exit touching/overlapping emergency corridor is allowed
        InfrastructureElement emgExit = element(
                4L, "eex1", InfrastructureType.EMERGENCY_EXIT, "Emergency Exit W", 13, 70, 6, 2, 0);

        List<ConflictDetectionService.DetectedConflict> findings = detector.detect(
                boundary, List.of(), List.of(emgCorridor),
                List.of(ambulanceLane, stallInZone, stallInLane, emgExit));

        assertThat(findings)
                .filteredOn(f -> f.conflictType() == ConflictType.EMERGENCY_LANE_INTRUSION)
                .hasSize(2)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(FindingSeverity.CRITICAL));
    }

    @Test
    void linearAlignedBarricadeSkipsRigidOverlapButStillFlagsExitAndEmergencyIntrusions() {
        Polygon boundary = boundary100x80();
        InfrastructureElement stage = element(1L, "stg1", InfrastructureType.STAGE, "Main Stage", 50, 20, 12, 6, 0);
        InfrastructureElement linearBarricade = element(
                2L, "bar1", InfrastructureType.BARRICADE, "Front Crush Barrier", 50, 22.8, 12, 0.6, 0,
                true, "{\"linearAligned\":true}");
        InfrastructureElement exit = element(3L, "ex1", InfrastructureType.EXIT_GATE, "Gate A", 50, 25, 4, 2, 0);

        List<ConflictDetectionService.DetectedConflict> findings =
                detector.detect(boundary, List.of(), List.of(), List.of(stage, linearBarricade, exit));

        // Linear barricade skips OVERLAPPING_RIGID_STRUCTURES with Stage, but still triggers EXIT_OBSTRUCTION
        assertThat(findings)
                .noneMatch(f -> f.conflictType() == ConflictType.OVERLAPPING_RIGID_STRUCTURES
                        && f.title().contains("Front Crush Barrier"));
        assertThat(findings)
                .anyMatch(f -> f.conflictType() == ConflictType.EXIT_OBSTRUCTION
                        && f.title().contains("Front Crush Barrier"));
    }

    @Test
    void detectsOutOfBoundsAndUnreachableServicePostsAndInactiveElementsIgnored() {
        Polygon boundary = boundary100x80();
        FunctionalZone audience = zone("AUD-1", ZoneType.AUDIENCE,
                "POLYGON ((10 10, 40 10, 40 40, 10 40, 10 10))");

        // Out-of-bounds stall protruding beyond x=100
        InfrastructureElement oobStall = element(
                1L, "oob1", InfrastructureType.FOOD_STALL, "Outer Stall", 99, 40, 6, 4, 0);
        // Medical post isolated 15 m away from AUD-1
        InfrastructureElement isolatedMed = element(
                2L, "med1", InfrastructureType.MEDICAL_POST, "North Medical", 60, 20, 6, 4, 0);
        // Inactive control room also isolated -> ignored when inactive=false
        InfrastructureElement inactiveCtl = element(
                3L, "ctl1", InfrastructureType.CONTROL_ROOM, "Spare Control", 80, 60, 6, 4, 0,
                false, null);

        List<ConflictDetectionService.DetectedConflict> initialFindings = detector.detect(
                boundary, List.of(), List.of(audience), List.of(oobStall, isolatedMed, inactiveCtl));

        assertThat(initialFindings)
                .anySatisfy(f -> {
                    assertThat(f.conflictType()).isEqualTo(ConflictType.OUT_OF_BOUNDS);
                    assertThat(f.severity()).isEqualTo(FindingSeverity.HIGH);
                })
                .anySatisfy(f -> {
                    assertThat(f.conflictType()).isEqualTo(ConflictType.UNREACHABLE_SERVICE_POST);
                    assertThat(f.severity()).isEqualTo(FindingSeverity.HIGH);
                })
                .noneMatch(f -> f.title().contains("Spare Control"));

        // Placing an Ambulance Lane adjacent (within 1 m) to isolatedMed resolves unreachability
        InfrastructureElement lane = element(
                4L, "amb1", InfrastructureType.AMBULANCE_LANE, "Medical Access Lane", 60, 25, 16, 4, 0);
        assertThat(detector.detect(boundary, List.of(), List.of(audience), List.of(isolatedMed, lane)))
                .noneMatch(f -> f.conflictType() == ConflictType.UNREACHABLE_SERVICE_POST);
    }

    @Test
    void detectsWaterPointNarrowWalkwayAndStageRearUtilityHazards() {
        Polygon boundary = boundary100x80();
        // 2.2 m wide narrow walkway (< 3.0 m)
        FunctionalZone narrowWalkway = zone("WLK-1", ZoneType.WALKWAY,
                "POLYGON ((20 50, 60 50, 60 52.2, 20 52.2, 20 50))");
        InfrastructureElement stage = element(1L, "stg1", InfrastructureType.STAGE, "Main Stage", 50, 20, 12, 8, 0);
        // Water point in narrow walkway
        InfrastructureElement waterWalkway = element(
                2L, "w1", InfrastructureType.WATER_POINT, "Water West", 30, 51.1, 2.0, 1.5, 0);
        // Water point directly behind stage (stage rear edge y = 20 - 4 = 16; water at y = 14.5)
        InfrastructureElement waterStageRear = element(
                3L, "w2", InfrastructureType.WATER_POINT, "Water Backstage", 50, 14.5, 3.0, 2.0, 0);
        // Water point next to EQUIPMENT exclusion
        ExclusionArea genEx = exclusion("Generator Pad", ExclusionType.EQUIPMENT,
                "POLYGON ((70 60, 76 60, 76 66, 70 66, 70 60))");
        InfrastructureElement waterGen = element(
                4L, "w3", InfrastructureType.WATER_POINT, "Water East", 78, 63, 2.0, 2.0, 0);

        List<ConflictDetectionService.DetectedConflict> findings = detector.detect(
                boundary, List.of(genEx), List.of(narrowWalkway),
                List.of(stage, waterWalkway, waterStageRear, waterGen));

        assertThat(findings)
                .filteredOn(f -> f.conflictType() == ConflictType.WATER_UTILITY_HAZARD)
                .hasSize(3)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(FindingSeverity.LOW));
    }
}
