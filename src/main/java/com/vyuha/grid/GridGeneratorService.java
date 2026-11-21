package com.vyuha.grid;

import com.vyuha.common.GeometryUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.springframework.stereotype.Service;

/**
 * Pure-Java JTS square-grid generator.
 * Clips adjustable metric grid cells (2 m - 20 m) against irregular outer venue boundaries,
 * supports origin offset & rotation, and computes exact cell union geometries for zone painting.
 */
@Service
public class GridGeneratorService {

    public static final double MIN_CELL_SIZE_METERS = 2.0;
    public static final double MAX_CELL_SIZE_METERS = 20.0;
    public static final int MAX_GENERATED_CELLS = 5000;
    private static final int MAX_CANDIDATE_ENVELOPE_CELLS = 25000;
    private static final double PARTIAL_CELL_EPSILON = 0.01;
    private static final Pattern CELL_REF_PATTERN = Pattern.compile("^C(-?\\d+)R(-?\\d+)$");

    private final GeometryUtil geometryUtil;

    public GridGeneratorService(GeometryUtil geometryUtil) {
        this.geometryUtil = geometryUtil;
    }

    public void validateConfig(double cellSizeMeters, double originX, double originY, double rotationDeg) {
        if (!Double.isFinite(cellSizeMeters)
                || cellSizeMeters < MIN_CELL_SIZE_METERS
                || cellSizeMeters > MAX_CELL_SIZE_METERS) {
            throw new IllegalArgumentException(
                    "Grid cell size must be between " + MIN_CELL_SIZE_METERS + " m and " + MAX_CELL_SIZE_METERS + " m.");
        }
        if (!Double.isFinite(originX) || !Double.isFinite(originY)) {
            throw new IllegalArgumentException("Grid origin coordinates must be finite numbers.");
        }
        if (!Double.isFinite(rotationDeg) || rotationDeg < -180.0 || rotationDeg > 180.0) {
            throw new IllegalArgumentException("Grid rotation must be between -180 and 180 degrees.");
        }
    }

    public GridDtos.GeneratedGridResult generateClippedGrid(Polygon boundary,
                                                            Geometry usableGeom,
                                                            double cellSizeMeters,
                                                            double originX,
                                                            double originY,
                                                            double rotationDeg) {
        validateConfig(cellSizeMeters, originX, originY, rotationDeg);
        if (boundary == null || boundary.isEmpty()) {
            return new GridDtos.GeneratedGridResult(List.of(), 0, 0, 0.0, 0.0);
        }

        AffineTransformation toWorld = buildForwardTransform(originX, originY, rotationDeg);
        AffineTransformation toLocal = buildInverseTransform(originX, originY, rotationDeg);

        Geometry localBoundary = Math.abs(rotationDeg) > 1e-9
                ? toLocal.transform(boundary)
                : boundary;
        Envelope localEnv = localBoundary.getEnvelopeInternal();
        if (localEnv == null || localEnv.isNull()) {
            return new GridDtos.GeneratedGridResult(List.of(), 0, 0, 0.0, 0.0);
        }

        double minLocalX = Math.abs(rotationDeg) > 1e-9 ? localEnv.getMinX() : localEnv.getMinX() - originX;
        double maxLocalX = Math.abs(rotationDeg) > 1e-9 ? localEnv.getMaxX() : localEnv.getMaxX() - originX;
        double minLocalY = Math.abs(rotationDeg) > 1e-9 ? localEnv.getMinY() : localEnv.getMinY() - originY;
        double maxLocalY = Math.abs(rotationDeg) > 1e-9 ? localEnv.getMaxY() : localEnv.getMaxY() - originY;

        int colStart = (int) Math.floor(minLocalX / cellSizeMeters);
        int colEnd = (int) Math.floor((maxLocalX - 1e-9) / cellSizeMeters);
        int rowStart = (int) Math.floor(minLocalY / cellSizeMeters);
        int rowEnd = (int) Math.floor((maxLocalY - 1e-9) / cellSizeMeters);

        long candidateCount = Math.max(0L, (long) colEnd - colStart + 1L) * Math.max(0L, (long) rowEnd - rowStart + 1L);
        if (candidateCount > MAX_CANDIDATE_ENVELOPE_CELLS) {
            throw new IllegalArgumentException(
                    "Grid cell size " + cellSizeMeters + " m creates too many cells for this site extent. Increase cell size.");
        }

        double nominalCellArea = cellSizeMeters * cellSizeMeters;
        List<GridDtos.GridCellDto> cells = new ArrayList<>();
        int partialCount = 0;
        double totalClippedArea = 0.0;
        double totalUsableArea = 0.0;

        for (int row = rowStart; row <= rowEnd; row++) {
            for (int col = colStart; col <= colEnd; col++) {
                Polygon cellPoly = buildWorldCellPolygon(col, row, cellSizeMeters, originX, originY, rotationDeg, toWorld);
                if (!cellPoly.getEnvelopeInternal().intersects(boundary.getEnvelopeInternal())) {
                    continue;
                }
                Geometry clipped = cellPoly.intersection(boundary);
                double clippedArea = geometryUtil.polygonalArea(clipped);
                if (clippedArea < GeometryUtil.MIN_POLYGON_AREA_SQM) {
                    continue;
                }

                if (cells.size() >= MAX_GENERATED_CELLS) {
                    throw new IllegalArgumentException(
                            "Generated grid exceeds the " + MAX_GENERATED_CELLS
                                    + "-cell limit. Increase the grid cell size.");
                }

                double cellUsable = 0.0;
                if (usableGeom != null && !usableGeom.isEmpty()
                        && clipped.getEnvelopeInternal().intersects(usableGeom.getEnvelopeInternal())) {
                    cellUsable = Math.min(clippedArea, geometryUtil.polygonalArea(clipped.intersection(usableGeom)));
                }

                boolean partial = clippedArea < nominalCellArea - PARTIAL_CELL_EPSILON;
                if (partial) {
                    partialCount++;
                }
                totalClippedArea += clippedArea;
                totalUsableArea += cellUsable;

                List<GeometryUtil.PolygonShape> shapes = geometryUtil.extractPolygonShapes(clipped);
                if (shapes.isEmpty()) {
                    continue;
                }
                GeometryUtil.Point2D center = geometryUtil.interiorPoint(clipped);
                cells.add(new GridDtos.GridCellDto(
                        formatCellRef(col, row),
                        col,
                        row,
                        clippedArea,
                        cellUsable,
                        partial,
                        center,
                        shapes
                ));
            }
        }

        return new GridDtos.GeneratedGridResult(
                List.copyOf(cells),
                cells.size(),
                partialCount,
                totalClippedArea,
                totalUsableArea
        );
    }

    public CellUnionResult unionSelectedCells(Polygon boundary,
                                                double cellSizeMeters,
                                                double originX,
                                                double originY,
                                                double rotationDeg,
                                                List<String> rawCellRefs) {
        validateConfig(cellSizeMeters, originX, originY, rotationDeg);
        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalArgumentException("Draw an outer venue boundary before painting grid zones.");
        }
        if (rawCellRefs == null || rawCellRefs.isEmpty()) {
            throw new IllegalArgumentException("Select at least one grid cell.");
        }

        AffineTransformation toWorld = buildForwardTransform(originX, originY, rotationDeg);
        Set<CellIndex> uniqueIndices = new LinkedHashSet<>();
        for (String ref : rawCellRefs) {
            CellIndex idx = parseCellRef(ref);
            if (idx != null) {
                uniqueIndices.add(idx);
            }
        }
        if (uniqueIndices.isEmpty()) {
            throw new IllegalArgumentException("No valid grid cell references were provided.");
        }

        List<CellIndex> sorted = uniqueIndices.stream()
                .sorted(Comparator.comparingInt(CellIndex::row).thenComparingInt(CellIndex::col))
                .toList();

        List<Geometry> clippedCells = new ArrayList<>();
        List<String> validRefs = new ArrayList<>();
        for (CellIndex idx : sorted) {
            Polygon cellPoly = buildWorldCellPolygon(
                    idx.col(), idx.row(), cellSizeMeters, originX, originY, rotationDeg, toWorld);
            Geometry clipped = cellPoly.intersection(boundary);
            if (geometryUtil.polygonalArea(clipped) >= GeometryUtil.MIN_POLYGON_AREA_SQM) {
                clippedCells.add(clipped);
                validRefs.add(formatCellRef(idx.col(), idx.row()));
            }
        }

        if (clippedCells.isEmpty()) {
            throw new IllegalArgumentException("Selected grid cells lie outside the venue boundary.");
        }

        Geometry unioned = geometryUtil.unionPolygons(clippedCells);
        geometryUtil.validatePolygonalGeometry(unioned);
        return new CellUnionResult(unioned, List.copyOf(validRefs));
    }

    public static String formatCellRef(int col, int row) {
        return "C" + col + "R" + row;
    }

    public static CellIndex parseCellRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        Matcher m = CELL_REF_PATTERN.matcher(ref.trim().toUpperCase(java.util.Locale.ROOT));
        if (!m.matches()) {
            return null;
        }
        try {
            return new CellIndex(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Polygon buildWorldCellPolygon(int col,
                                          int row,
                                          double cellSizeMeters,
                                          double originX,
                                          double originY,
                                          double rotationDeg,
                                          AffineTransformation toWorld) {
        double lx0 = col * cellSizeMeters;
        double ly0 = row * cellSizeMeters;
        double lx1 = lx0 + cellSizeMeters;
        double ly1 = ly0 + cellSizeMeters;

        if (Math.abs(rotationDeg) <= 1e-9) {
            double wx0 = originX + lx0;
            double wy0 = originY + ly0;
            double wx1 = originX + lx1;
            double wy1 = originY + ly1;
            Coordinate[] coords = new Coordinate[]{
                    new Coordinate(wx0, wy0),
                    new Coordinate(wx1, wy0),
                    new Coordinate(wx1, wy1),
                    new Coordinate(wx0, wy1),
                    new Coordinate(wx0, wy0)
            };
            return geometryUtil.getGeometryFactory().createPolygon(coords);
        }

        Coordinate[] localCoords = new Coordinate[]{
                new Coordinate(lx0, ly0),
                new Coordinate(lx1, ly0),
                new Coordinate(lx1, ly1),
                new Coordinate(lx0, ly1),
                new Coordinate(lx0, ly0)
        };
        Polygon localPoly = geometryUtil.getGeometryFactory().createPolygon(localCoords);
        return (Polygon) toWorld.transform(localPoly);
    }

    private AffineTransformation buildForwardTransform(double originX, double originY, double rotationDeg) {
        double rad = Math.toRadians(rotationDeg);
        return AffineTransformation.rotationInstance(rad).translate(originX, originY);
    }

    private AffineTransformation buildInverseTransform(double originX, double originY, double rotationDeg) {
        double rad = -Math.toRadians(rotationDeg);
        return AffineTransformation.translationInstance(-originX, -originY).rotate(rad);
    }

    public record CellIndex(int col, int row) {
    }

    public record CellUnionResult(Geometry geometry, List<String> validCellRefs) {
    }
}
