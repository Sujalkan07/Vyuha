package com.vyuha.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.vyuha.common.GeometryUtil;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

class AreaBreakdownServiceTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final AreaBreakdownService areaBreakdownService = new AreaBreakdownService(geometryUtil);

    private ExclusionArea exclusion(String label, ExclusionType type, String wkt) {
        ExclusionArea ex = new ExclusionArea();
        ex.setLabel(label);
        ex.setExclusionType(type);
        ex.setPolygonWkt(wkt);
        return ex;
    }

    @Test
    void returnsEmptyBreakdownWhenBoundaryIsMissing() {
        AreaBreakdownResult result = areaBreakdownService.calculate(null, List.of());
        assertThat(result.hasBoundary()).isFalse();
        assertThat(result.grossAreaSqm()).isZero();
        assertThat(result.usableAreaSqm()).isZero();
    }

    @Test
    void neverDoubleCountsOverlappingExclusions() {
        // 100m x 80m = 8000 m²
        String boundary = "POLYGON ((0 0, 100 0, 100 80, 0 80, 0 0))";
        // Stage (400 m²) and Building (400 m²) overlap by 10m x 20m = 200 m²
        ExclusionArea stage = exclusion("Main Stage", ExclusionType.STAGE,
                "POLYGON ((10 10, 30 10, 30 30, 10 30, 10 10))");
        ExclusionArea building = exclusion("AV Compound", ExclusionType.BUILDING,
                "POLYGON ((20 10, 40 10, 40 30, 20 30, 20 10))");

        AreaBreakdownResult result = areaBreakdownService.calculate(boundary, List.of(stage, building));

        assertThat(result.hasBoundary()).isTrue();
        assertThat(result.grossAreaSqm()).isCloseTo(8000.0, within(1e-6));
        assertThat(result.restrictedAreaSqm()).isCloseTo(600.0, within(1e-6));
        assertThat(result.corridorsAreaSqm()).isCloseTo(0.0, within(1e-6));
        assertThat(result.excludedTotalAreaSqm()).isCloseTo(600.0, within(1e-6));
        assertThat(result.usableAreaSqm()).isCloseTo(7400.0, within(1e-6));
        assertThat(result.overlapDeduplicatedSqm()).isCloseTo(200.0, within(1e-6));
    }

    @Test
    void deduplicatesCorridorOverlapAgainstRestrictedStructures() {
        String boundary = "POLYGON ((0 0, 100 0, 100 80, 0 80, 0 0))";
        // Building 400 m², Corridor 400 m², overlap 200 m²
        ExclusionArea building = exclusion("North Hall", ExclusionType.BUILDING,
                "POLYGON ((10 10, 30 10, 30 30, 10 30, 10 10))");
        ExclusionArea corridor = exclusion("East Corridor", ExclusionType.RESERVED_WALKWAY,
                "POLYGON ((20 10, 40 10, 40 30, 20 30, 20 10))");

        AreaBreakdownResult result = areaBreakdownService.calculate(boundary, List.of(building, corridor));

        assertThat(result.restrictedAreaSqm()).isCloseTo(400.0, within(1e-6));
        assertThat(result.corridorsAreaSqm()).isCloseTo(200.0, within(1e-6));
        assertThat(result.excludedTotalAreaSqm()).isCloseTo(600.0, within(1e-6));
        assertThat(result.usableAreaSqm()).isCloseTo(7400.0, within(1e-6));
        assertThat(result.restrictedAreaSqm() + result.corridorsAreaSqm())
                .isCloseTo(result.excludedTotalAreaSqm(), within(1e-6));
        assertThat(result.usableAreaSqm() + result.excludedTotalAreaSqm())
                .isCloseTo(result.grossAreaSqm(), within(1e-6));
    }

    @Test
    void clipsExclusionsExtendingOutsideBoundaryAndHandlesConcaveShapes() {
        // Concave L-shape: (0,0)-(100,0)-(100,40)-(40,40)-(40,80)-(0,80) -> area = 4000 + 1600 = 5600 m²
        String concaveBoundary = "POLYGON ((0 0, 100 0, 100 40, 40 40, 40 80, 0 80, 0 0))";
        // Exclusion overlaps outer notch and outside X>100: (80,10)-(120,10)-(120,30)-(80,30)
        // Raw area = 800 m², inside boundary = (80..100, 10..30) = 400 m², outside = 400 m²
        ExclusionArea overhang = exclusion("Exterior Compound", ExclusionType.RESTRICTED,
                "POLYGON ((80 10, 120 10, 120 30, 80 30, 80 10))");

        AreaBreakdownResult result = areaBreakdownService.calculate(concaveBoundary, List.of(overhang));

        assertThat(result.grossAreaSqm()).isCloseTo(5600.0, within(1e-6));
        assertThat(result.excludedTotalAreaSqm()).isCloseTo(400.0, within(1e-6));
        assertThat(result.totalOutsideBoundarySqm()).isCloseTo(400.0, within(1e-6));
        assertThat(result.usableAreaSqm()).isCloseTo(5200.0, within(1e-6));

        Polygon parsedBoundary = geometryUtil.parsePolygon(concaveBoundary);
        Geometry usableGeom = areaBreakdownService.computeUsableGeometry(parsedBoundary, List.of(overhang));
        assertThat(geometryUtil.polygonalArea(usableGeom)).isCloseTo(5200.0, within(1e-6));
    }

    @Test
    void handlesFullyCoveredBoundaryYieldingZeroUsableSpace() {
        String boundary = "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))";
        ExclusionArea fullCover = exclusion("Total Closure", ExclusionType.RESTRICTED,
                "POLYGON ((-5 -5, 25 -5, 25 25, -5 25, -5 -5))");

        AreaBreakdownResult result = areaBreakdownService.calculate(boundary, List.of(fullCover));

        assertThat(result.grossAreaSqm()).isCloseTo(400.0, within(1e-6));
        assertThat(result.excludedTotalAreaSqm()).isCloseTo(400.0, within(1e-6));
        assertThat(result.usableAreaSqm()).isCloseTo(0.0, within(1e-6));
    }
}
