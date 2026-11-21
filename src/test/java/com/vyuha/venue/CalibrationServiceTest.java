package com.vyuha.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.vyuha.common.GeometryUtil;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalibrationServiceTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final CalibrationService calibrationService = new CalibrationService(geometryUtil);

    @Test
    void computesAccurateScaleAndPassesVerificationWithinFivePercent() {
        // Primary: 400 px for 50.0 m => 8.0 px/m
        // Verify: 240 px for 30.0 m => predicted 30.0 m => 0.0% mismatch
        VenueDtos.CalibrationRequest request = new VenueDtos.CalibrationRequest(
                100.0, 200.0, 500.0, 200.0, 50.0,
                100.0, 100.0, 100.0, 340.0, 30.0,
                true, true
        );

        CalibrationData result = calibrationService.calibrate(request, 1200, 900);

        assertThat(result.pixelsPerMeter()).isCloseTo(8.0, within(1e-6));
        assertThat(result.metersPerPixel()).isCloseTo(0.125, within(1e-6));
        assertThat(result.mismatchPercent()).isCloseTo(0.0, within(1e-6));
        assertThat(result.verificationAngleDeg()).isCloseTo(90.0, within(1e-6));
        assertThat(result.scaleVerified()).isTrue();
        assertThat(result.scaleMismatchWarning()).isFalse();
        assertThat(result.distortionWarning()).isFalse();

        // Boundary check: exactly 5.0% mismatch (predicted 31.5 m vs 30.0 m => 252 px) passes
        VenueDtos.CalibrationRequest exactFivePct = new VenueDtos.CalibrationRequest(
                100.0, 200.0, 500.0, 200.0, 50.0,
                100.0, 100.0, 100.0, 352.0, 30.0,
                true, true
        );
        CalibrationData boundaryResult = calibrationService.calibrate(exactFivePct, 1200, 900);
        assertThat(boundaryResult.mismatchPercent()).isCloseTo(5.0, within(1e-6));
        assertThat(boundaryResult.scaleMismatchWarning()).isFalse();
        assertThat(boundaryResult.scaleVerified()).isTrue();
    }

    @Test
    void triggersDistortionWarningWhenVerificationExceedsFivePercent() {
        // Primary: 400 px = 50 m => 8 px/m.
        // Verify: 280 px = 35 m predicted, but real = 30.0 m => 16.67% mismatch (> 5%)
        VenueDtos.CalibrationRequest skewed = new VenueDtos.CalibrationRequest(
                100.0, 200.0, 500.0, 200.0, 50.0,
                100.0, 100.0, 100.0, 380.0, 30.0,
                false, true
        );

        CalibrationData result = calibrationService.calibrate(skewed, 1200, 900);

        assertThat(result.mismatchPercent()).isCloseTo(16.6667, within(0.01));
        assertThat(result.scaleMismatchWarning()).isTrue();
        assertThat(result.distortionWarning()).isTrue();
        assertThat(result.scaleVerified()).isFalse();
        assertThat(result.warningMessages()).anyMatch(m -> m.contains("oblique") && m.contains("16.67%"));
        assertThat(result.warningMessages()).anyMatch(m -> m.contains("orthographic"));
    }

    @Test
    void flagsExtremeAspectRatioAsSkewWarning() {
        VenueDtos.CalibrationRequest request = new VenueDtos.CalibrationRequest(
                0.0, 100.0, 400.0, 100.0, 40.0,
                null, null, null, null, null,
                true, true
        );

        CalibrationData result = calibrationService.calibrate(request, 4000, 800);

        assertThat(result.aspectSkewWarning()).isTrue();
        assertThat(result.distortionWarning()).isTrue();
        assertThat(result.scaleVerified()).isFalse();
        assertThat(result.warningMessages()).anyMatch(m -> m.contains("aspect ratio"));
    }

    @Test
    void rejectsInvalidCalibrationInputs() {
        VenueDtos.CalibrationRequest zeroSpan = new VenueDtos.CalibrationRequest(
                100.0, 100.0, 100.0, 100.0, 10.0,
                null, null, null, null, null,
                true, true
        );
        assertThatThrownBy(() -> calibrationService.calibrate(zeroSpan, 1000, 800))
                .isInstanceOf(IllegalArgumentException.class);

        VenueDtos.CalibrationRequest partialVerify = new VenueDtos.CalibrationRequest(
                0.0, 0.0, 100.0, 0.0, 10.0,
                0.0, 0.0, 0.0, null, 10.0,
                true, true
        );
        assertThatThrownBy(() -> calibrationService.calibrate(partialVerify, 1000, 800))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertsPixelCoordinatesAndRescalesExistingPolygonsAccurately() {
        List<GeometryUtil.Point2D> metric = calibrationService.pixelsToMeters(
                List.of(
                        new GeometryUtil.Point2D(0, 0),
                        new GeometryUtil.Point2D(500, 0),
                        new GeometryUtil.Point2D(500, 250),
                        new GeometryUtil.Point2D(0, 250)
                ),
                10.0
        );
        assertThat(metric.get(1)).isEqualTo(new GeometryUtil.Point2D(50.0, 0.0));
        assertThat(metric.get(2)).isEqualTo(new GeometryUtil.Point2D(50.0, 25.0));

        String rescaledWkt = calibrationService.rescalePolygonWkt(
                "POLYGON ((0 0, 50 0, 50 25, 0 25, 0 0))", 2.0);
        assertThat(geometryUtil.parsePolygon(rescaledWkt).getArea()).isCloseTo(5000.0, within(1e-6));
    }
}
