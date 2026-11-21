package com.vyuha.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.vyuha.common.GeometryUtil.Point2D;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

class GeometryUtilTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();

    @Test
    void buildsAndExtractsConcavePolygonCleanly() {
        List<Point2D> lShape = List.of(
                new Point2D(0, 0),
                new Point2D(10, 0),
                new Point2D(10, 4),
                new Point2D(4, 4),
                new Point2D(4, 10),
                new Point2D(0, 10)
        );
        Polygon poly = geometryUtil.buildPolygon(lShape);

        assertThat(poly.getArea()).isCloseTo(64.0, within(1e-6));
        assertThat(geometryUtil.extractOuterRing(poly)).hasSize(6);

        String wkt = geometryUtil.toWkt(poly);
        Polygon reparsed = geometryUtil.parsePolygon(wkt);
        assertThat(reparsed.getArea()).isCloseTo(64.0, within(1e-6));
    }

    @Test
    void rejectsSelfIntersectingBowtieAndDegeneratePolygons() {
        List<Point2D> bowtie = List.of(
                new Point2D(0, 0),
                new Point2D(10, 10),
                new Point2D(10, 0),
                new Point2D(0, 10)
        );
        assertThatThrownBy(() -> geometryUtil.buildPolygon(bowtie))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");

        List<Point2D> collinear = List.of(
                new Point2D(0, 0),
                new Point2D(5, 0),
                new Point2D(10, 0)
        );
        assertThatThrownBy(() -> geometryUtil.buildPolygon(collinear))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> geometryUtil.buildPolygon(List.of(new Point2D(0, 0), new Point2D(1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidAndNonPolygonWkt() {
        assertThatThrownBy(() -> geometryUtil.parsePolygon(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> geometryUtil.parsePolygon("POINT (5 5)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> geometryUtil.parsePolygon("POLYGON ((0 0, 10 10, 10 0, 0 10, 0 0))"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void polygonalAreaIgnoresLineStringAndPointArtifacts() {
        Polygon a = geometryUtil.parsePolygon("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))");
        Polygon b = geometryUtil.parsePolygon("POLYGON ((10 0, 20 0, 20 10, 10 10, 10 0))");
        Geometry touchingBoundary = a.intersection(b); // LineString along x=10

        assertThat(geometryUtil.polygonalArea(touchingBoundary)).isZero();
    }

    @Test
    void buildsRotatedRectanglesWithExactAreaAndTransformedCorners() {
        double[] angles = {0.0, 37.0, 45.0, 90.0, -45.0};
        for (double angle : angles) {
            Polygon rect = geometryUtil.buildRotatedRectangle(50.0, 40.0, 8.0, 4.0, angle);
            assertThat(rect.isValid()).isTrue();
            assertThat(rect.getArea()).isCloseTo(32.0, within(1e-6));
            Point2D centroid = geometryUtil.centroid(rect);
            assertThat(centroid.x()).isCloseTo(50.0, within(1e-6));
            assertThat(centroid.y()).isCloseTo(40.0, within(1e-6));
        }

        // At 90 deg clockwise (Y-down), local +X (3, 0) maps to world +Y (10, 23)
        Point2D rotatedPoint = geometryUtil.localToWorld(10.0, 20.0, 90.0, 3.0, 0.0);
        assertThat(rotatedPoint.x()).isCloseTo(10.0, within(1e-6));
        assertThat(rotatedPoint.y()).isCloseTo(23.0, within(1e-6));

        assertThat(geometryUtil.normalizeRotationDeg(270.0)).isCloseTo(-90.0, within(1e-6));
        assertThat(geometryUtil.normalizeRotationDeg(-180.0)).isCloseTo(180.0, within(1e-6));
    }

    @Test
    void buildsParsesMidpointsAndMeasuresAnglesForLineStrings() {
        LineString line = geometryUtil.buildLineString(List.of(
                new Point2D(0, 0),
                new Point2D(10, 0),
                new Point2D(10, 10)
        ));
        assertThat(line.getLength()).isCloseTo(20.0, within(1e-6));
        assertThat(geometryUtil.extractLinePoints(line)).hasSize(3);

        Point2D mid = geometryUtil.lineMidpoint(line);
        assertThat(mid.x()).isCloseTo(10.0, within(1e-6));
        assertThat(mid.y()).isCloseTo(0.0, within(1e-6));

        LineString reparsed = geometryUtil.parseLineString(geometryUtil.toWkt(line));
        assertThat(reparsed.getLength()).isCloseTo(20.0, within(1e-6));

        // Horizontal queue zone [10..40, 10..20] crossed vertically at x=25 -> ~90 deg
        Polygon queuePoly = geometryUtil.parsePolygon("POLYGON ((10 10, 40 10, 40 20, 10 20, 10 10))");
        LineString verticalCross = geometryUtil.buildLineString(List.of(new Point2D(25, 0), new Point2D(25, 30)));
        LineString horizontalFeed = geometryUtil.buildLineString(List.of(new Point2D(0, 15), new Point2D(50, 15)));
        assertThat(geometryUtil.acuteAngleBetweenLinesDeg(verticalCross, queuePoly)).isCloseTo(90.0, within(0.5));
        assertThat(geometryUtil.acuteAngleBetweenLinesDeg(horizontalFeed, queuePoly)).isCloseTo(0.0, within(0.5));

        // Invalid / degenerate LineStrings
        assertThatThrownBy(() -> geometryUtil.buildLineString(List.of(new Point2D(5, 5))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> geometryUtil.buildLineString(List.of(new Point2D(5, 5), new Point2D(5, 5))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> geometryUtil.parseLineString("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsAndParsesPointsGenericGeometryAndFlatLineBuffer() {
        Point pt = geometryUtil.buildPoint(12.5, 34.25);
        assertThat(pt.getX()).isCloseTo(12.5, within(1e-6));
        assertThat(pt.getY()).isCloseTo(34.25, within(1e-6));

        Point reparsed = geometryUtil.parsePoint(geometryUtil.toWkt(pt));
        assertThat(reparsed.getX()).isCloseTo(12.5, within(1e-6));
        assertThat(reparsed.getY()).isCloseTo(34.25, within(1e-6));

        assertThat(geometryUtil.parseGeometry("POINT (5 8)")).isInstanceOf(Point.class);
        assertThat(geometryUtil.parseGeometry("LINESTRING (0 0, 10 0)")).isInstanceOf(LineString.class);
        assertThat(geometryUtil.parseGeometry("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))")).isInstanceOf(Polygon.class);

        LineString corridor = geometryUtil.buildLineString(List.of(new Point2D(0, 10), new Point2D(20, 10)));
        Geometry buffered = geometryUtil.bufferLineFlat(corridor, 2.0);
        assertThat(buffered.getArea()).isCloseTo(80.0, within(0.5));

        assertThatThrownBy(() -> geometryUtil.parsePoint("LINESTRING (0 0, 5 5)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> geometryUtil.buildPoint(Double.NaN, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> geometryUtil.bufferLineFlat(corridor, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
