package com.vyuha.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.ZoneType;
import com.vyuha.grid.ZoneValidationService;
import com.vyuha.venue.AreaBreakdownService;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CapacityCalculatorServiceTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final AreaBreakdownService areaBreakdownService = new AreaBreakdownService(geometryUtil);
    private final ZoneValidationService zoneValidationService = new ZoneValidationService(geometryUtil, areaBreakdownService);
    private final CapacityCalculatorService calculator = new CapacityCalculatorService(
            geometryUtil, areaBreakdownService, zoneValidationService);

    private FunctionalZone zone(long id, String code, ZoneType type, String wkt) {
        FunctionalZone z = new FunctionalZone();
        ReflectionTestUtils.setField(z, "id", id);
        z.setZoneCode(code);
        z.setName(code);
        z.setZoneType(type);
        z.setColorHex(type.getFillColor());
        z.setPolygonWkt(wkt);
        return z;
    }

    private ZoneDensityAssumption assumption(FunctionalZone zone, Double density, String citation, AssumptionReviewStatus status) {
        ZoneDensityAssumption a = new ZoneDensityAssumption();
        a.setFunctionalZone(zone);
        a.setDensityPersonsPerSqm(density);
        a.setSourceCitation(citation);
        a.setReviewStatus(status);
        return a;
    }

    @Test
    void calculatesMultiConstraintCapacityAndIdentifiesTightestConstraint() {
        String boundary = "POLYGON ((0 0, 100 0, 100 80, 0 80, 0 0))"; // 8000 m²
        // Stage overlaps AUD-1 by 200 m² -> usable AUD-1 = 2000 - 200 = 1800 m²
        ExclusionArea stage = new ExclusionArea();
        stage.setLabel("Stage");
        stage.setExclusionType(ExclusionType.STAGE);
        stage.setPolygonWkt("POLYGON ((0 0, 20 0, 20 10, 0 10, 0 0))");

        FunctionalZone aud = zone(1L, "AUD-1", ZoneType.AUDIENCE,
                "POLYGON ((0 0, 50 0, 50 40, 0 40, 0 0))"); // gross 2000 m², usable 1800 m²
        FunctionalZone queue = zone(2L, "QUE-1", ZoneType.QUEUE,
                "POLYGON ((50 0, 70 0, 70 20, 50 20, 50 0))"); // usable 400 m²

        ZoneDensityAssumption audA = assumption(aud, 2.0, "Local Fire Dept Guide", AssumptionReviewStatus.REVIEWER_VERIFIED);
        // C_space = floor(1800 * 2.0) = 3600
        ZoneDensityAssumption queueA = assumption(queue, 1.5, "Event Queue Manual", AssumptionReviewStatus.REVIEWER_VERIFIED);
        // C_holding = floor(400 * 1.5) + 100 = 700

        CapacityReview review = new CapacityReview();
        review.setOfficialVenueLimit(4000);
        review.setOfficialLimitSource("Permit #101");
        review.setEntryThroughputPerMin(50.0);
        review.setIngressWindowMinutes(60); // 3000
        review.setExitThroughputPerMin(80.0);
        review.setEgressWindowMinutes(45); // 3600 -> C_flow = min(3000, 3600) = 3000
        review.setHoldingBufferCapacity(100);
        review.setReviewStatus(CapacityReviewStatus.REVIEWER_VERIFIED);

        CapacityDtos.CapacityCalculationResult res = calculator.calculate(
                1000, boundary, List.of(stage), List.of(aud, queue), review, List.of(audA, queueA));

        assertThat(res.usableSpaceCapacity()).isEqualTo(3600);
        assertThat(res.entryFlowCapacity()).isEqualTo(3000);
        assertThat(res.exitFlowCapacity()).isEqualTo(3600);
        assertThat(res.flowCapacity()).isEqualTo(3000);
        assertThat(res.holdingSpaceCapacity()).isEqualTo(700);
        assertThat(res.officialVenueLimit()).isEqualTo(4000);

        // Tightest is C_holding = 700, which is exceeded by expectedAttendance = 1000
        assertThat(res.preliminaryCapacity()).isEqualTo(700);
        assertThat(res.governingConstraint()).isEqualTo(GoverningConstraint.HOLDING_SPACE);
        assertThat(res.exceedsPreliminaryCeiling()).isTrue();
        assertThat(res.attendanceHeadroom()).isEqualTo(-300);
        assertThat(res.effectiveReviewStatus()).isEqualTo(CapacityReviewStatus.REVIEWER_VERIFIED);
        assertThat(res.warnings()).anyMatch(w -> "EXPECTED_ATTENDANCE_EXCEEDS_CEILING".equals(w.code())
                && "CRITICAL".equals(w.severity()));

        // Check AUD-1 exact usable area in row
        assertThat(res.zoneRows().get(0).usableZoneAreaSqm()).isCloseTo(1800.0, within(1e-6));
        assertThat(res.zoneRows().get(0).computedCapacity()).isEqualTo(3600);
    }

    @Test
    void enforcesMissingDensityAndCitationWarningsAndReviewLifecycle() {
        String boundary = "POLYGON ((0 0, 60 0, 60 40, 0 40, 0 0))";
        FunctionalZone aud = zone(10L, "AUD-1", ZoneType.AUDIENCE,
                "POLYGON ((0 0, 40 0, 40 40, 0 40, 0 0))"); // 1600 m²

        // No density assumption yet -> PENDING_REVIEW, INCOMPLETE
        CapacityDtos.CapacityCalculationResult missingRes = calculator.calculate(
                2000, boundary, List.of(), List.of(aud), null, List.of());

        assertThat(missingRes.preliminaryCapacity()).isNull();
        assertThat(missingRes.governingConstraint()).isEqualTo(GoverningConstraint.INCOMPLETE);
        assertThat(missingRes.effectiveReviewStatus()).isEqualTo(CapacityReviewStatus.PENDING_REVIEW);
        assertThat(missingRes.warnings()).anyMatch(w -> "MISSING_ZONE_DENSITY".equals(w.code()));
        assertThat(missingRes.warnings()).anyMatch(w -> "OFFICIAL_LIMIT_MISSING".equals(w.code()));

        // Documented with citation but not yet verified -> DOCUMENTED
        ZoneDensityAssumption documented = assumption(aud, 1.5, "Festival SOP 2026", AssumptionReviewStatus.DOCUMENTED);
        CapacityReview rev = new CapacityReview();
        rev.setOfficialVenueLimit(2200); // tighter than C_space = 2400
        CapacityDtos.CapacityCalculationResult docRes = calculator.calculate(
                2000, boundary, List.of(), List.of(aud), rev, List.of(documented));

        assertThat(docRes.usableSpaceCapacity()).isEqualTo(2400);
        assertThat(docRes.preliminaryCapacity()).isEqualTo(2200);
        assertThat(docRes.governingConstraint()).isEqualTo(GoverningConstraint.OFFICIAL_LIMIT);
        assertThat(docRes.effectiveReviewStatus()).isEqualTo(CapacityReviewStatus.DOCUMENTED);
        assertThat(docRes.exceedsPreliminaryCeiling()).isFalse();

        // Flagged assumption overrides review status to FLAGGED
        ZoneDensityAssumption flagged = assumption(aud, 1.5, "Festival SOP 2026", AssumptionReviewStatus.FLAGGED);
        CapacityDtos.CapacityCalculationResult flagRes = calculator.calculate(
                2000, boundary, List.of(), List.of(aud), rev, List.of(flagged));
        assertThat(flagRes.effectiveReviewStatus()).isEqualTo(CapacityReviewStatus.FLAGGED);
    }
}
