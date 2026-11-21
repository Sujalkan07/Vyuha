package com.vyuha.venue;

import java.util.List;

/**
 * Persisted and computed scale calibration audit data for Blueprint & Photo workspaces.
 */
public record CalibrationData(
        double primaryX1Px,
        double primaryY1Px,
        double primaryX2Px,
        double primaryY2Px,
        double primaryPixelLength,
        double primaryRealDistanceMeters,
        double pixelsPerMeter,
        double metersPerPixel,
        Integer imgWidthPx,
        Integer imgHeightPx,
        Double verifyX1Px,
        Double verifyY1Px,
        Double verifyX2Px,
        Double verifyY2Px,
        Double verifyPixelLength,
        Double verifyRealDistanceMeters,
        Double predictedVerifyDistanceMeters,
        Double mismatchPercent,
        Double verificationAngleDeg,
        boolean scaleVerified,
        boolean scaleMismatchWarning,
        boolean aspectSkewWarning,
        boolean distortionWarning,
        boolean orthographicConfirmed,
        List<String> warningMessages
) {
}
