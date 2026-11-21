package com.vyuha.common;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;
import org.springframework.stereotype.Component;

/**
 * Shared JTS 2D spatial geometry helper used across Venue, Grid, Infrastructure,
 * Network, Emergency, and Simulation modules (Venue Local Metric Coordinate System in meters).
 */
@Component
public class GeometryUtil {

    public static final double MIN_POLYGON_AREA_SQM = 0.01;
    public static final double MIN_LINE_LENGTH_M = 0.05;
    private static final double POINT_EPSILON = 1e-6;

    private final GeometryFactory geometryFactory = new GeometryFactory();

    public record Point2D(double x, double y) {
    }

    public record Bounds(double minX, double minY, double maxX, double maxY) {
    }

    public record PolygonShape(List<Point2D> exterior, List<List<Point2D>> holes) {
    }

    public GeometryFactory getGeometryFactory() {
        return geometryFactory;
    }

    public Polygon parsePolygon(String wkt) {
        Geometry geometry = readWkt(wkt);
        if (!(geometry instanceof Polygon polygon)) {
            throw new IllegalArgumentException("Geometry must be a single valid 2D Polygon.");
        }
        validatePolygon(polygon);
        return polygon;
    }

    /**
     * Parses a 2D Polygon or MultiPolygon (used for grid-painted zones that may have holes or disconnected islands).
     */
    public Geometry parsePolygonal(String wkt) {
        Geometry geometry = readWkt(wkt);
        if (!(geometry instanceof Polygon) && !(geometry instanceof MultiPolygon)) {
            throw new IllegalArgumentException("Geometry must be a valid 2D Polygon or MultiPolygon.");
        }
        validatePolygonalGeometry(geometry);
        return geometry;
    }

    private Geometry readWkt(String wkt) {
        if (wkt == null || wkt.isBlank()) {
            throw new IllegalArgumentException("Polygon WKT must not be blank.");
        }
        try {
            return new WKTReader(geometryFactory).read(wkt.trim());
        } catch (ParseException ex) {
            throw new IllegalArgumentException("Invalid polygon WKT: " + ex.getMessage(), ex);
        }
    }

    public Polygon buildPolygon(List<Point2D> rawPoints) {
        if (rawPoints == null || rawPoints.size() < 3) {
            throw new IllegalArgumentException("A polygon requires at least 3 vertices.");
        }
        List<Coordinate> cleaned = new ArrayList<>();
        for (Point2D pt : rawPoints) {
            if (pt == null || !Double.isFinite(pt.x()) || !Double.isFinite(pt.y())) {
                throw new IllegalArgumentException("All polygon vertices must have finite numeric coordinates.");
            }
            Coordinate coord = new Coordinate(pt.x(), pt.y());
            if (cleaned.isEmpty() || cleaned.get(cleaned.size() - 1).distance(coord) > POINT_EPSILON) {
                cleaned.add(coord);
            }
        }
        if (cleaned.size() >= 2 && cleaned.get(cleaned.size() - 1).distance(cleaned.get(0)) <= POINT_EPSILON) {
            cleaned.remove(cleaned.size() - 1);
        }
        if (cleaned.size() < 3) {
            throw new IllegalArgumentException("A polygon requires at least 3 distinct vertices.");
        }

        Coordinate[] ringCoords = new Coordinate[cleaned.size() + 1];
        for (int i = 0; i < cleaned.size(); i++) {
            ringCoords[i] = cleaned.get(i);
        }
        ringCoords[cleaned.size()] = new Coordinate(cleaned.get(0));

        LinearRing shell;
        try {
            shell = geometryFactory.createLinearRing(ringCoords);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid polygon ring: " + ex.getMessage(), ex);
        }
        Polygon polygon = geometryFactory.createPolygon(shell);
        validatePolygon(polygon);
        return polygon;
    }

    public void validatePolygon(Polygon polygon) {
        validatePolygonalGeometry(polygon);
    }

    public void validatePolygonalGeometry(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            throw new IllegalArgumentException("Polygon cannot be empty.");
        }
        for (Coordinate coord : geometry.getCoordinates()) {
            if (!Double.isFinite(coord.x) || !Double.isFinite(coord.y)) {
                throw new IllegalArgumentException("Polygon contains non-finite coordinates.");
            }
        }
        IsValidOp validOp = new IsValidOp(geometry);
        if (!validOp.isValid()) {
            TopologyValidationError err = validOp.getValidationError();
            String reason = err != null ? err.getMessage() : "invalid topology";
            throw new IllegalArgumentException("Polygon geometry is invalid (" + reason + ").");
        }
        if (polygonalArea(geometry) < MIN_POLYGON_AREA_SQM) {
            throw new IllegalArgumentException("Polygon area is too small (minimum 0.01 m²).");
        }
    }

    public String toWkt(Geometry geometry) {
        if (geometry == null) {
            return null;
        }
        return new WKTWriter(2).write(geometry);
    }

    public List<Point2D> extractOuterRing(Polygon polygon) {
        if (polygon == null || polygon.isEmpty()) {
            return List.of();
        }
        return extractRingPoints(polygon.getExteriorRing());
    }

    public List<Polygon> extractPolygons(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return List.of();
        }
        List<Polygon> result = new ArrayList<>();
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry component = geometry.getGeometryN(i);
            if (component instanceof Polygon poly && poly.getArea() > 1e-9) {
                result.add(poly);
            }
        }
        return result;
    }

    public List<PolygonShape> extractPolygonShapes(Geometry geometry) {
        List<Polygon> polys = extractPolygons(geometry);
        List<PolygonShape> shapes = new ArrayList<>(polys.size());
        for (Polygon poly : polys) {
            List<Point2D> exterior = extractRingPoints(poly.getExteriorRing());
            List<List<Point2D>> holes = new ArrayList<>(poly.getNumInteriorRing());
            for (int h = 0; h < poly.getNumInteriorRing(); h++) {
                List<Point2D> holePts = extractRingPoints(poly.getInteriorRingN(h));
                if (holePts.size() >= 3) {
                    holes.add(holePts);
                }
            }
            if (exterior.size() >= 3) {
                shapes.add(new PolygonShape(exterior, List.copyOf(holes)));
            }
        }
        return List.copyOf(shapes);
    }

    private List<Point2D> extractRingPoints(org.locationtech.jts.geom.LineString ring) {
        if (ring == null || ring.isEmpty()) {
            return List.of();
        }
        Coordinate[] coords = ring.getCoordinates();
        if (coords.length < 2) {
            return List.of();
        }
        List<Point2D> points = new ArrayList<>(coords.length - 1);
        for (int i = 0; i < coords.length - 1; i++) {
            points.add(new Point2D(coords[i].x, coords[i].y));
        }
        return List.copyOf(points);
    }

    public double polygonalArea(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry component = geometry.getGeometryN(i);
            if (component instanceof Polygon poly) {
                total += poly.getArea();
            }
        }
        return total;
    }

    public Geometry unionPolygons(List<? extends Geometry> geometries) {
        if (geometries == null || geometries.isEmpty()) {
            return geometryFactory.createGeometryCollection();
        }
        List<Geometry> polygonal = new ArrayList<>();
        for (Geometry g : geometries) {
            if (g == null || g.isEmpty()) {
                continue;
            }
            for (int i = 0; i < g.getNumGeometries(); i++) {
                Geometry component = g.getGeometryN(i);
                if (component instanceof Polygon poly && poly.getArea() > 1e-9) {
                    polygonal.add(poly);
                }
            }
        }
        if (polygonal.isEmpty()) {
            return geometryFactory.createGeometryCollection();
        }
        if (polygonal.size() == 1) {
            return polygonal.get(0);
        }
        return UnaryUnionOp.union(polygonal);
    }

    public Point2D centroid(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }
        Point centroid = geometry.getCentroid();
        if (centroid == null || centroid.isEmpty()
                || !Double.isFinite(centroid.getX()) || !Double.isFinite(centroid.getY())) {
            return null;
        }
        return new Point2D(centroid.getX(), centroid.getY());
    }

    public Point2D interiorPoint(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }
        Geometry target = geometry;
        List<Polygon> polys = extractPolygons(geometry);
        if (!polys.isEmpty()) {
            Polygon largest = polys.get(0);
            for (int i = 1; i < polys.size(); i++) {
                if (polys.get(i).getArea() > largest.getArea()) {
                    largest = polys.get(i);
                }
            }
            target = largest;
        }
        Point interior = target.getInteriorPoint();
        if (interior != null && !interior.isEmpty()
                && Double.isFinite(interior.getX()) && Double.isFinite(interior.getY())) {
            return new Point2D(interior.getX(), interior.getY());
        }
        return centroid(target);
    }

    public double sharedBoundaryLength(Geometry a, Geometry b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        double len = a.getBoundary().intersection(b.getBoundary()).getLength();
        if (len > 0.0) {
            return len;
        }
        if (a.distance(b) < 1e-3) {
            return a.buffer(1e-3).intersection(b.getBoundary()).getLength();
        }
        return 0.0;
    }

    public Bounds bounds(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }
        Envelope env = geometry.getEnvelopeInternal();
        if (env == null || env.isNull()) {
            return null;
        }
        return new Bounds(env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY());
    }

    /**
     * Transforms local box coordinates (origin at center, Y-down canvas orientation)
     * into world metric coordinates rotated clockwise by rotationDeg.
     */
    public Point2D localToWorld(double centerX, double centerY, double rotationDeg, double localX, double localY) {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY)
                || !Double.isFinite(rotationDeg) || !Double.isFinite(localX) || !Double.isFinite(localY)) {
            throw new IllegalArgumentException("Coordinates and rotation must be finite numbers.");
        }
        double rad = Math.toRadians(rotationDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double wx = centerX + (localX * cos - localY * sin);
        double wy = centerY + (localX * sin + localY * cos);
        return new Point2D(wx, wy);
    }

    /**
     * Builds a valid JTS Polygon for an oriented rectangle centered at (centerX, centerY).
     */
    public Polygon buildRotatedRectangle(double centerX,
                                         double centerY,
                                         double widthM,
                                         double heightM,
                                         double rotationDeg) {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY)
                || !Double.isFinite(widthM) || !Double.isFinite(heightM) || !Double.isFinite(rotationDeg)) {
            throw new IllegalArgumentException("Rectangle center, dimensions, and rotation must be finite numbers.");
        }
        if (widthM <= 0.0 || heightM <= 0.0) {
            throw new IllegalArgumentException("Rectangle width and height must be greater than zero.");
        }
        double hw = widthM / 2.0;
        double hh = heightM / 2.0;
        List<Point2D> corners = List.of(
                localToWorld(centerX, centerY, rotationDeg, -hw, -hh),
                localToWorld(centerX, centerY, rotationDeg, hw, -hh),
                localToWorld(centerX, centerY, rotationDeg, hw, hh),
                localToWorld(centerX, centerY, rotationDeg, -hw, hh)
        );
        return buildPolygon(corners);
    }

    /**
     * Normalizes an angle in degrees to (-180.0, 180.0].
     */
    public double normalizeRotationDeg(double rotationDeg) {
        if (!Double.isFinite(rotationDeg)) {
            throw new IllegalArgumentException("Rotation must be a finite number.");
        }
        double normalized = ((rotationDeg % 360.0) + 360.0) % 360.0;
        if (normalized > 180.0) {
            normalized -= 360.0;
        }
        if (Math.abs(normalized + 180.0) < 1e-9) {
            normalized = 180.0;
        }
        return round2(normalized);
    }

    public double round2(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Parses and validates a 2D LineString WKT with at least 2 finite points and minimum length.
     */
    public LineString parseLineString(String wkt) {
        Geometry geometry = readWkt(wkt);
        if (!(geometry instanceof LineString line)) {
            throw new IllegalArgumentException("Geometry must be a valid 2D LineString.");
        }
        validateLineString(line);
        return line;
    }

    /**
     * Builds and validates a 2D LineString from ordered metric points, collapsing duplicate consecutive points.
     */
    public LineString buildLineString(List<Point2D> rawPoints) {
        if (rawPoints == null || rawPoints.size() < 2) {
            throw new IllegalArgumentException("A pathway line requires at least 2 points.");
        }
        List<Coordinate> cleaned = new ArrayList<>();
        for (Point2D pt : rawPoints) {
            if (pt == null || !Double.isFinite(pt.x()) || !Double.isFinite(pt.y())) {
                throw new IllegalArgumentException("All pathway line vertices must have finite numeric coordinates.");
            }
            Coordinate coord = new Coordinate(pt.x(), pt.y());
            if (cleaned.isEmpty() || cleaned.get(cleaned.size() - 1).distance(coord) > POINT_EPSILON) {
                cleaned.add(coord);
            }
        }
        if (cleaned.size() < 2) {
            throw new IllegalArgumentException("A pathway line requires at least 2 distinct points.");
        }
        LineString line = geometryFactory.createLineString(cleaned.toArray(new Coordinate[0]));
        validateLineString(line);
        return line;
    }

    private void validateLineString(LineString line) {
        if (line == null || line.isEmpty() || line.getNumPoints() < 2) {
            throw new IllegalArgumentException("Pathway line must contain at least 2 vertices.");
        }
        for (Coordinate coord : line.getCoordinates()) {
            if (!Double.isFinite(coord.x) || !Double.isFinite(coord.y)) {
                throw new IllegalArgumentException("Pathway line contains non-finite coordinates.");
            }
        }
        if (line.getLength() < MIN_LINE_LENGTH_M) {
            throw new IllegalArgumentException("Pathway line is too short (minimum 0.05 m).");
        }
    }

    public List<Point2D> extractLinePoints(LineString line) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        Coordinate[] coords = line.getCoordinates();
        List<Point2D> points = new ArrayList<>(coords.length);
        for (Coordinate c : coords) {
            points.add(new Point2D(c.x, c.y));
        }
        return List.copyOf(points);
    }

    /**
     * Computes the exact arc-length midpoint of a LineString using JTS LengthIndexedLine.
     */
    public Point2D lineMidpoint(LineString line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        Coordinate mid = indexed.extractPoint(line.getLength() / 2.0);
        if (mid == null || !Double.isFinite(mid.x) || !Double.isFinite(mid.y)) {
            return centroid(line);
        }
        return new Point2D(mid.x, mid.y);
    }

    /**
     * Computes the acute angle in degrees [0.0, 90.0] between a LineString's dominant traversal direction
     * inside the polygon and the polygon's major orientation axis (from its Minimum Bounding Rectangle).
     */
    public double acuteAngleBetweenLinesDeg(LineString line, Geometry polygon) {
        if (line == null || line.isEmpty() || polygon == null || polygon.isEmpty()) {
            return 0.0;
        }
        Geometry clipped = line.intersection(polygon);
        LineString activeLine = extractLongestLineString(clipped);
        if (activeLine == null) {
            activeLine = line;
        }
        Coordinate[] lineCoords = activeLine.getCoordinates();
        if (lineCoords.length < 2) {
            return 0.0;
        }
        double ldx = lineCoords[lineCoords.length - 1].x - lineCoords[0].x;
        double ldy = lineCoords[lineCoords.length - 1].y - lineCoords[0].y;
        double lLen = Math.hypot(ldx, ldy);
        if (lLen < POINT_EPSILON) {
            // Fall back to longest individual segment of activeLine
            for (int i = 1; i < lineCoords.length; i++) {
                double dx = lineCoords[i].x - lineCoords[i - 1].x;
                double dy = lineCoords[i].y - lineCoords[i - 1].y;
                double len = Math.hypot(dx, dy);
                if (len > lLen) {
                    lLen = len;
                    ldx = dx;
                    ldy = dy;
                }
            }
        }
        if (lLen < POINT_EPSILON) {
            return 0.0;
        }

        Geometry minRect = new MinimumDiameter(polygon).getMinimumRectangle();
        double pdx = 1.0;
        double pdy = 0.0;
        if (minRect instanceof Polygon rectPoly) {
            Coordinate[] rc = rectPoly.getExteriorRing().getCoordinates();
            if (rc.length >= 4) {
                double edge1Len = rc[0].distance(rc[1]);
                double edge2Len = rc[1].distance(rc[2]);
                if (edge1Len >= edge2Len) {
                    pdx = rc[1].x - rc[0].x;
                    pdy = rc[1].y - rc[0].y;
                } else {
                    pdx = rc[2].x - rc[1].x;
                    pdy = rc[2].y - rc[1].y;
                }
            }
        } else {
            Envelope env = polygon.getEnvelopeInternal();
            if (env.getWidth() >= env.getHeight()) {
                pdx = 1.0;
                pdy = 0.0;
            } else {
                pdx = 0.0;
                pdy = 1.0;
            }
        }

        double pLen = Math.hypot(pdx, pdy);
        if (pLen < POINT_EPSILON) {
            return 0.0;
        }
        double dot = Math.abs((ldx * pdx + ldy * pdy) / (lLen * pLen));
        dot = Math.max(0.0, Math.min(1.0, dot));
        return round2(Math.toDegrees(Math.acos(dot)));
    }

    private LineString extractLongestLineString(Geometry geom) {
        if (geom == null || geom.isEmpty()) {
            return null;
        }
        LineString best = null;
        double bestLen = 0.0;
        for (int i = 0; i < geom.getNumGeometries(); i++) {
            Geometry part = geom.getGeometryN(i);
            if (part instanceof LineString ls && ls.getLength() > bestLen) {
                bestLen = ls.getLength();
                best = ls;
            }
        }
        return best;
    }

    /**
     * Builds a validated 2D Point in venue local meters.
     */
    public Point buildPoint(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Point coordinates must be finite numbers.");
        }
        return geometryFactory.createPoint(new Coordinate(x, y));
    }

    /**
     * Parses and validates a 2D Point WKT.
     */
    public Point parsePoint(String wkt) {
        Geometry geometry = readWkt(wkt);
        if (!(geometry instanceof Point point) || point.isEmpty()) {
            throw new IllegalArgumentException("Geometry must be a valid 2D Point.");
        }
        Coordinate coord = point.getCoordinate();
        if (coord == null || !Double.isFinite(coord.x) || !Double.isFinite(coord.y)) {
            throw new IllegalArgumentException("Point contains non-finite coordinates.");
        }
        return point;
    }

    /**
     * Parses and validates a 2D Point, LineString, Polygon, or MultiPolygon WKT.
     */
    public Geometry parseGeometry(String wkt) {
        Geometry geometry = readWkt(wkt);
        if (geometry instanceof Point point) {
            if (point.isEmpty() || point.getCoordinate() == null
                    || !Double.isFinite(point.getCoordinate().x)
                    || !Double.isFinite(point.getCoordinate().y)) {
                throw new IllegalArgumentException("Point contains non-finite coordinates.");
            }
            return point;
        }
        if (geometry instanceof LineString line) {
            validateLineString(line);
            return line;
        }
        if (geometry instanceof Polygon || geometry instanceof MultiPolygon) {
            validatePolygonalGeometry(geometry);
            return geometry;
        }
        throw new IllegalArgumentException("Geometry must be a valid 2D Point, LineString, Polygon, or MultiPolygon.");
    }

    /**
     * Buffers a LineString with flat end caps to model an emergency vehicle corridor footprint.
     */
    public Geometry bufferLineFlat(LineString line, double halfWidthM) {
        if (line == null || line.isEmpty()) {
            throw new IllegalArgumentException("LineString must not be empty.");
        }
        if (!Double.isFinite(halfWidthM) || halfWidthM <= 0.0) {
            throw new IllegalArgumentException("Buffer half-width must be greater than zero.");
        }
        BufferParameters params = new BufferParameters(8, BufferParameters.CAP_FLAT);
        return BufferOp.bufferOp(line, halfWidthM, params);
    }
}
