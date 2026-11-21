package com.vyuha.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.vyuha.common.GeometryUtil;
import com.vyuha.venue.AreaBreakdownService;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Polygon;

class ZoneValidationServiceTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final AreaBreakdownService areaBreakdownService = new AreaBreakdownService(geometryUtil);
    private final ZoneValidationService zoneValidationService = new ZoneValidationService(geometryUtil, areaBreakdownService);

    private FunctionalZone zone(String code, ZoneType type, String wkt) {
        FunctionalZone z = new FunctionalZone();
        z.setZoneCode(code);
        z.setName(code + " Name");
        z.setZoneType(type);
        z.setColorHex(type.getFillColor());
        z.setPolygonWkt(wkt);
        return z;
    }

    @Test
    void validatesCleanConnectedLayoutAndUnassignedSpace() {
        Polygon boundary = geometryUtil.parsePolygon("POLYGON ((0 0, 100 0, 100 50, 0 50, 0 0))");
        FunctionalZone entry = zone("ENT-1", ZoneType.ENTRY, "POLYGON ((0 0, 20 0, 20 50, 0 50, 0 0))");
        FunctionalZone audience = zone("AUD-1", ZoneType.AUDIENCE, "POLYGON ((20 0, 80 0, 80 50, 20 50, 20 0))");

        GridDtos.ZoneValidationResult result = zoneValidationService.validate(boundary, List.of(), List.of(entry, audience));

        assertThat(result.coverage().hasBoundary()).isTrue();
        assertThat(result.coverage().totalUsableAreaSqm()).isCloseTo(5000.0, within(1e-6));
        assertThat(result.coverage().assignedUsableAreaSqm()).isCloseTo(4000.0, within(1e-6));
        assertThat(result.coverage().unassignedUsableAreaSqm()).isCloseTo(1000.0, within(1e-6));
        assertThat(result.coverage().overlapAreaSqm()).isCloseTo(0.0, within(1e-6));
        assertThat(result.coverage().isolatedZoneCount()).isZero();
        assertThat(result.findings()).extracting(GridDtos.ZoneFindingDto::code)
                .containsExactly("UNASSIGNED_SPACE");
    }

    @Test
    void detectsZoneOverlapsAboveThresholdAndIgnoresMicroSlivers() {
        Polygon boundary = geometryUtil.parsePolygon("POLYGON ((0 0, 100 0, 100 80, 0 80, 0 0))");
        // Overlap = 5m x 10m = 50 m² (> 0.1 m²)
        FunctionalZone aud = zone("AUD-1", ZoneType.AUDIENCE, "POLYGON ((10 10, 40 10, 40 40, 10 40, 10 10))");
        FunctionalZone food = zone("FOD-1", ZoneType.FOOD_STALLS, "POLYGON ((35 10, 60 10, 60 20, 35 20, 35 10))");

        GridDtos.ZoneValidationResult result = zoneValidationService.validate(boundary, List.of(), List.of(aud, food));

        assertThat(result.coverage().overlapAreaSqm()).isCloseTo(50.0, within(1e-6));
        assertThat(result.findings()).anyMatch(f -> "ZONE_OVERLAP".equals(f.code()) && "HIGH".equals(f.severity()));

        // Tiny 0.05 m² overlap (<= 0.1 m² threshold) does not raise overlap finding
        FunctionalZone touch = zone("WLK-1", ZoneType.WALKWAY, "POLYGON ((39.99 10, 50 10, 50 15, 39.99 15, 39.99 10))");
        GridDtos.ZoneValidationResult cleanResult = zoneValidationService.validate(boundary, List.of(), List.of(aud, touch));
        assertThat(cleanResult.findings()).noneMatch(f -> "ZONE_OVERLAP".equals(f.code()));
    }

    @Test
    void flagsPublicZoneEncircledByRestrictedZoneOrBuildingExclusionAsIsolated() {
        Polygon boundary = geometryUtil.parsePolygon("POLYGON ((0 0, 100 0, 100 80, 0 80, 0 0))");
        FunctionalZone entry = zone("ENT-1", ZoneType.ENTRY, "POLYGON ((0 0, 15 0, 15 80, 0 80, 0 0))");
        // Closed ring of RESTRICTED zone around (40..60, 30..50)
        FunctionalZone restrictedRing = zone("RES-1", ZoneType.RESTRICTED,
                "POLYGON ((35 25, 65 25, 65 55, 35 55, 35 25), (40 30, 60 30, 60 50, 40 50, 40 30))");
        FunctionalZone trappedAudience = zone("AUD-1", ZoneType.AUDIENCE,
                "POLYGON ((40 30, 60 30, 60 50, 40 50, 40 30))");

        GridDtos.ZoneValidationResult trappedRes = zoneValidationService.validate(
                boundary, List.of(), List.of(entry, restrictedRing, trappedAudience));

        assertThat(trappedRes.coverage().isolatedZoneCount()).isEqualTo(1);
        assertThat(trappedRes.findings())
                .anyMatch(f -> "ISOLATED_ZONE".equals(f.code()) && "CRITICAL".equals(f.severity()));

        // Also test encirclement by a solid BUILDING exclusion (0 m doorway vs 2 m doorway)
        ExclusionArea buildingWithNarrowGap = new ExclusionArea();
        buildingWithNarrowGap.setLabel("Enclosure Wall");
        buildingWithNarrowGap.setExclusionType(ExclusionType.BUILDING);
        // C-shaped building with 0.8 m gap at y=[39.6..40.4] on x=[35..40] (<= 1.0 m threshold)
        buildingWithNarrowGap.setPolygonWkt(
                "POLYGON ((35 25, 65 25, 65 55, 35 55, 35 40.4, 40 40.4, 40 50, 60 50, 60 30, 40 30, 40 39.6, 35 39.6, 35 25))");

        GridDtos.ZoneValidationResult narrowDoorRes = zoneValidationService.validate(
                boundary, List.of(buildingWithNarrowGap), List.of(entry, trappedAudience));
        assertThat(narrowDoorRes.coverage().isolatedZoneCount()).isEqualTo(1);

        // Widening the doorway gap to 2.0 m (y=[39..41]) connects walkable access cleanly
        ExclusionArea buildingWithWideDoor = new ExclusionArea();
        buildingWithWideDoor.setLabel("Enclosure Wall Wide Door");
        buildingWithWideDoor.setExclusionType(ExclusionType.BUILDING);
        buildingWithWideDoor.setPolygonWkt(
                "POLYGON ((35 25, 65 25, 65 55, 35 55, 35 41, 40 41, 40 50, 60 50, 60 30, 40 30, 40 39, 35 39, 35 25))");

        GridDtos.ZoneValidationResult wideDoorRes = zoneValidationService.validate(
                boundary, List.of(buildingWithWideDoor), List.of(entry, trappedAudience));
        assertThat(wideDoorRes.coverage().isolatedZoneCount()).isZero();
        assertThat(wideDoorRes.findings()).noneMatch(f -> "ISOLATED_ZONE".equals(f.code()));
    }

    @Test
    void flagsZoneExtendingOutsideVenueBoundary() {
        Polygon boundary = geometryUtil.parsePolygon("POLYGON ((0 0, 50 0, 50 50, 0 50, 0 0))");
        FunctionalZone overhang = zone("AUD-1", ZoneType.AUDIENCE, "POLYGON ((20 10, 60 10, 60 40, 20 40, 20 10))");

        GridDtos.ZoneValidationResult result = zoneValidationService.validate(boundary, List.of(), List.of(overhang));

        assertThat(result.perZoneClippedAreasSqm().get(0)).isCloseTo(900.0, within(1e-6));
        assertThat(result.findings()).anyMatch(f -> "ZONE_OUTSIDE_BOUNDARY".equals(f.code()));
    }
}
