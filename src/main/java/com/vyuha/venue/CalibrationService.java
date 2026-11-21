package com.vyuha.venue;

import com.vyuha.common.GeometryUtil;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;

/**
 * Pure Java scale calibration engine for blueprint and aerial photo workspaces.
 * Computes pixels-per-meter ratio, optional cross-axis verification mismatch percentage,
 * perspective/oblique distortion warnings above 5%, and pixel-to-meter coordinate transforms.
 */
@Service
public class CalibrationService {

    public static final double MAX_ALLOWED_MISMATCH_PCT = 5.0;
    public static final double MAX_NORMAL_ASPECT_RATIO = 4.0;
    private static final double MIN_LINE_PIXELS = 1.0;

    private final GeometryUtil geometryUtil;

    public CalibrationService(GeometryUtil geometryUtil) {
        this.geometryUtil = geometryUtil;
    }

    public CalibrationData calibrate(VenueDtos.CalibrationRequest request,
                                     Integer imgWidthPx,
                                     Integer imgHeightPx) {
        if (request == null) {
            throw new IllegalArgumentException("Calibration request is required.");
        }

        double px1 = requireFinite(request.primaryX1Px(), "Primary point 1 X");
        double py1 = requireFinite(request.primaryY1Px(), "Primary point 1 Y");
        double px2 = requireFinite(request.primaryX2Px(), "Primary point 2 X");
        double py2 = requireFinite(request.primaryY2Px(), "Primary point 2 Y");
        double primaryReal = requirePositive(request.primaryRealDistanceMeters(), "Known primary distance (m)");

        double dx1 = px2 - px1;
        double dy1 = py2 - py1;
        double primaryPixelLength = Math.hypot(dx1, dy1);
        if (primaryPixelLength < MIN_LINE_PIXELS) {
            throw new IllegalArgumentException("Primary calibration segment must be at least 1 pixel long.");
        }

        double pixelsPerMeter = primaryPixelLength / primaryReal;
        double metersPerPixel = 1.0 / pixelsPerMeter;

        boolean anyVerifyProvided = request.verifyX1Px() != null
                || request.verifyY1Px() != null
                || request.verifyX2Px() != null
                || request.verifyY2Px() != null
                || request.verifyRealDistanceMeters() != null;

        Double vx1 = null;
        Double vy1 = null;
        Double vx2 = null;
        Double vy2 = null;
        Double verifyPixelLength = null;
        Double verifyRealDistance = null;
        Double predictedVerifyDistance = null;
        Double mismatchPercent = null;
        Double verificationAngleDeg = null;
        boolean scaleMismatchWarning = false;
        boolean scaleVerified = false;

        List<String> warnings = new ArrayList<>();

        if (anyVerifyProvided) {
            vx1 = requireFinite(request.verifyX1Px(), "Verification point 1 X");
            vy1 = requireFinite(request.verifyY1Px(), "Verification point 1 Y");
            vx2 = requireFinite(request.verifyX2Px(), "Verification point 2 X");
            vy2 = requireFinite(request.verifyY2Px(), "Verification point 2 Y");
            verifyRealDistance = requirePositive(request.verifyRealDistanceMeters(), "Known verification distance (m)");

            double dx2 = vx2 - vx1;
            double dy2 = vy2 - vy1;
            verifyPixelLength = Math.hypot(dx2, dy2);
            if (verifyPixelLength < MIN_LINE_PIXELS) {
                throw new IllegalArgumentException("Verification segment must be at least 1 pixel long.");
            }

            predictedVerifyDistance = verifyPixelLength / pixelsPerMeter;
            mismatchPercent = Math.abs(predictedVerifyDistance - verifyRealDistance) / verifyRealDistance * 100.0;

            double cosTheta = Math.abs((dx1 * dx2 + dy1 * dy2) / (primaryPixelLength * verifyPixelLength));
            cosTheta = Math.max(0.0, Math.min(1.0, cosTheta));
            verificationAngleDeg = Math.toDegrees(Math.acos(cosTheta));

            if (mismatchPercent > MAX_ALLOWED_MISMATCH_PCT) {
                scaleMismatchWarning = true;
                scaleVerified = false;
                warnings.add(String.format(
                        java.util.Locale.US,
                        "Scale mismatch is %.2f%% (exceeds %.1f%% threshold). The image may be oblique/angled or inaccurately calibrated; re-calibrate or upload a top-down orthographic site plan.",
                        mismatchPercent, MAX_ALLOWED_MISMATCH_PCT));
            } else {
                scaleVerified = true;
                if (verificationAngleDeg < 25.0) {
                    warnings.add(String.format(
                            java.util.Locale.US,
                            "Verification line angle is %.1f°. Draw the verification segment closer to 90° from the primary line for stronger perspective detection.",
                            verificationAngleDeg));
                }
            }
        } else {
            warnings.add("Scale is unverified: draw a second perpendicular verification line to check for perspective distortion.");
        }

        boolean aspectSkewWarning = false;
        if (imgWidthPx != null && imgHeightPx != null && imgWidthPx > 0 && imgHeightPx > 0) {
            double aspectRatio = Math.max(imgWidthPx, imgHeightPx) / (double) Math.min(imgWidthPx, imgHeightPx);
            if (aspectRatio > MAX_NORMAL_ASPECT_RATIO) {
                aspectSkewWarning = true;
                warnings.add(String.format(
                        java.util.Locale.US,
                        "Uploaded image has an extreme aspect ratio (%.1f:1). Verify that the image is not stretched or obliquely cropped.",
                        aspectRatio));
            }
        }

        if (!request.orthographicConfirmed()) {
            warnings.add("Top-down orthographic orientation has not been confirmed by the planner.");
        }

        boolean distortionWarning = scaleMismatchWarning || aspectSkewWarning;

        return new CalibrationData(
                px1, py1, px2, py2,
                primaryPixelLength,
                primaryReal,
                pixelsPerMeter,
                metersPerPixel,
                imgWidthPx,
                imgHeightPx,
                vx1, vy1, vx2, vy2,
                verifyPixelLength,
                verifyRealDistance,
                predictedVerifyDistance,
                mismatchPercent,
                verificationAngleDeg,
                scaleVerified,
                scaleMismatchWarning,
                aspectSkewWarning,
                distortionWarning,
                request.orthographicConfirmed(),
                List.copyOf(warnings)
        );
    }

    public List<GeometryUtil.Point2D> pixelsToMeters(List<GeometryUtil.Point2D> pixelPoints, double pixelsPerMeter) {
        if (!Double.isFinite(pixelsPerMeter) || pixelsPerMeter <= 0.0) {
            throw new IllegalArgumentException("Blueprint scale must be calibrated before converting pixel coordinates.");
        }
        if (pixelPoints == null || pixelPoints.isEmpty()) {
            throw new IllegalArgumentException("Point list must not be empty.");
        }
        List<GeometryUtil.Point2D> metric = new ArrayList<>(pixelPoints.size());
        for (GeometryUtil.Point2D p : pixelPoints) {
            if (p == null || !Double.isFinite(p.x()) || !Double.isFinite(p.y())) {
                throw new IllegalArgumentException("All pixel coordinates must be finite numbers.");
            }
            metric.add(new GeometryUtil.Point2D(p.x() / pixelsPerMeter, p.y() / pixelsPerMeter));
        }
        return metric;
    }

    public String rescalePolygonWkt(String wkt, double scaleFactor) {
        if (wkt == null || wkt.isBlank()) {
            return wkt;
        }
        if (!Double.isFinite(scaleFactor) || scaleFactor <= 0.0) {
            throw new IllegalArgumentException("Scale factor must be positive.");
        }
        Polygon existing = geometryUtil.parsePolygon(wkt);
        LinearRing scaledShell = scaleRing(existing.getExteriorRing(), scaleFactor);
        LinearRing[] scaledHoles = new LinearRing[existing.getNumInteriorRing()];
        for (int i = 0; i < existing.getNumInteriorRing(); i++) {
            scaledHoles[i] = scaleRing(existing.getInteriorRingN(i), scaleFactor);
        }
        Polygon rescaled = geometryUtil.getGeometryFactory().createPolygon(scaledShell, scaledHoles);
        geometryUtil.validatePolygon(rescaled);
        return geometryUtil.toWkt(rescaled);
    }

    private LinearRing scaleRing(org.locationtech.jts.geom.LineString ring, double scaleFactor) {
        Coordinate[] coords = ring.getCoordinates();
        Coordinate[] scaled = new Coordinate[coords.length];
        for (int i = 0; i < coords.length; i++) {
            scaled[i] = new Coordinate(coords[i].x * scaleFactor, coords[i].y * scaleFactor);
        }
        return geometryUtil.getGeometryFactory().createLinearRing(scaled);
    }

    private double requireFinite(Double value, String fieldLabel) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException(fieldLabel + " must be a finite number.");
        }
        return value;
    }

    private double requirePositive(Double value, String fieldLabel) {
        double v = requireFinite(value, fieldLabel);
        if (v <= 0.0) {
            throw new IllegalArgumentException(fieldLabel + " must be greater than zero.");
        }
        return v;
    }
}
