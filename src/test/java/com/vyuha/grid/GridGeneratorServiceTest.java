package com.vyuha.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.vyuha.common.GeometryUtil;
import com.vyuha.venue.AreaBreakdownService;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

class GridGeneratorServiceTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final AreaBreakdownService areaBreakdownService = new AreaBreakdownService(geometryUtil);
    private final GridGeneratorService gridGeneratorService = new GridGeneratorService(geometryUtil);

    @Test
    void generatesExactGridForAlignedRectangle() {
        Polygon boundary = geometryUtil.parsePolygon("POLYGON ((0 0, 20 0, 20 15, 0 15, 0 0))");
        GridDtos.GeneratedGridResult result = gridGeneratorService.generateClippedGrid(
                boundary, boundary, 5.0, 0.0, 0.0, 0.0);

        assertThat(result.totalCells()).isEqualTo(12); // 4 cols * 3 rows
        assertThat(result.partialCells()).isZero();
        assertThat(result.totalClippedAreaSqm()).isCloseTo(300.0, within(1e-6));
        assertThat(result.totalUsableAreaSqm()).isCloseTo(300.0, within(1e-6));
        assertThat(result.cells()).extracting(GridDtos.GridCellDto::cellRef)
                .contains("C0R0", "C3R0", "C0R2", "C3R2");
    }

    @Test
    void clipsIrregularConcaveBoundaryRetainingPartialCellsWithExactAreaConservation() {
        // Concave L-shape: (0..22, 0..10) + (0..12, 10..17) = 220 + 84 = 304 m²
        Polygon lShape = geometryUtil.parsePolygon(
                "POLYGON ((0 0, 22 0, 22 10, 12 10, 12 17, 0 17, 0 0))");
        GridDtos.GeneratedGridResult result = gridGeneratorService.generateClippedGrid(
                lShape, lShape, 5.0, 0.0, 0.0, 0.0);

        assertThat(result.totalClippedAreaSqm()).isCloseTo(304.0, within(1e-6));
        assertThat(result.partialCells()).isGreaterThan(0);
        // Rightmost strip x=[20..22], y=[0..5] -> area 10 m² (partial)
        GridDtos.GridCellDto c4r0 = result.cells().stream()
                .filter(c -> "C4R0".equals(c.cellRef()))
                .findFirst()
                .orElseThrow();
        assertThat(c4r0.partial()).isTrue();
        assertThat(c4r0.clippedAreaSqm()).isCloseTo(10.0, within(1e-6));
    }

    @Test
    void clipsRotatedAndOffsetGridAndReducesUsableAreaByExclusions() {
        Polygon boundary = geometryUtil.parsePolygon("POLYGON ((0 0, 40 0, 40 30, 0 30, 0 0))");
        ExclusionArea stage = new ExclusionArea();
        stage.setLabel("Stage");
        stage.setExclusionType(ExclusionType.STAGE);
        stage.setPolygonWkt("POLYGON ((10 10, 20 10, 20 20, 10 20, 10 10))");

        Geometry usable = areaBreakdownService.computeUsableGeometry(boundary, List.of(stage));
        GridDtos.GeneratedGridResult result = gridGeneratorService.generateClippedGrid(
                boundary, usable, 5.0, 2.5, 1.5, 30.0);

        assertThat(result.totalClippedAreaSqm()).isCloseTo(1200.0, within(0.05));
        assertThat(result.totalUsableAreaSqm()).isCloseTo(1100.0, within(0.05));
        assertThat(result.partialCells()).isGreaterThan(0);
    }

    @Test
    void unionsSelectedContiguousAndDisconnectedCellsCleanly() {
        Polygon boundary = geometryUtil.parsePolygon("POLYGON ((0 0, 30 0, 30 20, 0 20, 0 0))");

        // Contiguous 2x1 cells -> 10m x 5m = 50 m²
        GridGeneratorService.CellUnionResult contiguous = gridGeneratorService.unionSelectedCells(
                boundary, 5.0, 0.0, 0.0, 0.0, List.of("C0R0", "C1R0", "C0R0"));
        assertThat(contiguous.validCellRefs()).containsExactly("C0R0", "C1R0");
        assertThat(geometryUtil.polygonalArea(contiguous.geometry())).isCloseTo(50.0, within(1e-6));

        // Disconnected cells -> MultiPolygon with total area 50 m²
        GridGeneratorService.CellUnionResult disconnected = gridGeneratorService.unionSelectedCells(
                boundary, 5.0, 0.0, 0.0, 0.0, List.of("C0R0", "C4R3"));
        assertThat(disconnected.validCellRefs()).containsExactly("C0R0", "C4R3");
        assertThat(geometryUtil.polygonalArea(disconnected.geometry())).isCloseTo(50.0, within(1e-6));
    }

    @Test
    void rejectsInvalidGridParametersAndExcessiveCellCounts() {
        Polygon small = geometryUtil.parsePolygon("POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))");
        assertThatThrownBy(() -> gridGeneratorService.generateClippedGrid(small, small, 1.0, 0.0, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gridGeneratorService.generateClippedGrid(small, small, 25.0, 0.0, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);

        Polygon huge = geometryUtil.parsePolygon("POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))");
        assertThatThrownBy(() -> gridGeneratorService.generateClippedGrid(huge, huge, 2.0, 0.0, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
