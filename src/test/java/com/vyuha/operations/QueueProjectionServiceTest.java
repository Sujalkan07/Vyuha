package com.vyuha.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.vyuha.capacity.AssumptionReviewStatus;
import com.vyuha.capacity.CapacityCalculatorService;
import com.vyuha.capacity.CapacityDtos;
import com.vyuha.capacity.CapacityReview;
import com.vyuha.capacity.ZoneDensityAssumption;
import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.ZoneType;
import com.vyuha.grid.ZoneValidationService;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.venue.AreaBreakdownService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class QueueProjectionServiceTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final AreaBreakdownService areaBreakdownService = new AreaBreakdownService(geometryUtil);
    private final ZoneValidationService zoneValidationService = new ZoneValidationService(geometryUtil, areaBreakdownService);
    private final CapacityCalculatorService capacityCalculatorService = new CapacityCalculatorService(
            geometryUtil, areaBreakdownService, zoneValidationService);
    private final QueueProjectionService queueProjectionService = new QueueProjectionService(geometryUtil);

    private InfrastructureElement entryGate(long id, String label, double ratePerMin, boolean active) {
        InfrastructureElement el = new InfrastructureElement();
        ReflectionTestUtils.setField(el, "id", id);
        el.setElementType(InfrastructureType.ENTRY_GATE);
        el.setLabel(label);
        el.setThroughputPersonsPerMin(ratePerMin);
        el.setActive(active);
        return el;
    }

    private ArrivalWaveInterval wave(long id, int startMin, int endMin, int arrivals, InfrastructureElement targetGate) {
        ArrivalWaveInterval w = new ArrivalWaveInterval();
        ReflectionTestUtils.setField(w, "id", id);
        w.setStartMinute(startMin);
        w.setEndMinute(endMin);
        w.setExpectedArrivals(arrivals);
        w.setTargetGate(targetGate);
        w.setLabel("Wave " + id);
        return w;
    }

    private CapacityDtos.CapacityCalculationResult buildCapacityWithQueueZone(Double queueDensity, int buffer) {
        String boundary = "POLYGON ((0 0, 100 0, 100 80, 0 80, 0 0))";
        FunctionalZone aud = new FunctionalZone();
        ReflectionTestUtils.setField(aud, "id", 10L);
        aud.setZoneCode("AUD-1");
        aud.setName("Main Lawn");
        aud.setZoneType(ZoneType.AUDIENCE);
        aud.setColorHex(ZoneType.AUDIENCE.getFillColor());
        aud.setPolygonWkt("POLYGON ((10 10, 60 10, 60 50, 10 50, 10 10))");

        FunctionalZone queue = new FunctionalZone();
        ReflectionTestUtils.setField(queue, "id", 11L);
        queue.setZoneCode("QUE-1");
        queue.setName("Entry Holding Pen");
        queue.setZoneType(ZoneType.QUEUE);
        queue.setColorHex(ZoneType.QUEUE.getFillColor());
        queue.setPolygonWkt("POLYGON ((0 0, 20 0, 20 10, 0 10, 0 0))"); // 200 m²

        CapacityReview review = new CapacityReview();
        review.setHoldingBufferCapacity(buffer);

        List<ZoneDensityAssumption> assumptions;
        if (queueDensity != null) {
            ZoneDensityAssumption qa = new ZoneDensityAssumption();
            qa.setFunctionalZone(queue);
            qa.setDensityPersonsPerSqm(queueDensity);
            qa.setSourceCitation("Queue Design Manual Sec 2");
            qa.setReviewStatus(AssumptionReviewStatus.REVIEWER_VERIFIED);
            assumptions = List.of(qa);
        } else {
            assumptions = List.of();
        }

        return capacityCalculatorService.calculate(
                2000, boundary, List.of(), List.of(aud, queue), review, assumptions);
    }

    @Test
    void computesQueueCarryoverWaitTimesAndHoldingSpaceOverflowDeficit() {
        // 200 m² QUE-1 at 2.0 p/m² = 400 holding cap (+ 50 buffer = 450 effective holding)
        CapacityDtos.CapacityCalculationResult capResult = buildCapacityWithQueueZone(2.0, 50);
        CapacityReview review = new CapacityReview();
        review.setHoldingBufferCapacity(50);

        // 2 active entry gates: 25 + 15 = 40 p/min -> 600 persons per 15-min bucket
        InfrastructureElement g1 = entryGate(1L, "North Gate", 25.0, true);
        InfrastructureElement g2 = entryGate(2L, "East Gate", 15.0, true);

        // Bucket 1 (0-15): 900 arrivals, cap 600 -> Q1 = 300 (within 450)
        // Bucket 2 (15-30): 900 arrivals, startQ 300, cap 600 -> Q2 = 600 (OVERFLOW +150 p -> 75.0 m² deficit!)
        // Bucket 3 (30-45): 200 arrivals, startQ 600, cap 600 -> Q3 = 200 (within 450)
        // Bucket 4 (45-60): 100 arrivals, startQ 200, cap 600 -> Q4 = 0
        List<ArrivalWaveInterval> waves = List.of(
                wave(1L, 0, 15, 900, null),
                wave(2L, 15, 30, 900, null),
                wave(3L, 30, 45, 200, null),
                wave(4L, 45, 60, 100, null)
        );

        OperationsDtos.QueueProjectionResult res = queueProjectionService.project(
                2100,
                LocalDateTime.of(2026, 12, 20, 16, 0),
                waves,
                List.of(g1, g2),
                review,
                capResult
        );

        assertThat(res.totalExpectedArrivals()).isEqualTo(2100);
        assertThat(res.totalActiveEntryRatePerMin()).isCloseTo(40.0, within(1e-6));
        assertThat(res.effectiveHoldingCapacity()).isEqualTo(450);
        assertThat(res.effectiveHoldingDensityPerSqm()).isCloseTo(2.0, within(1e-6));
        assertThat(res.peakQueueLength()).isEqualTo(600);
        assertThat(res.peakQueueWindowLabel()).isEqualTo("16:15–16:30");
        assertThat(res.peakWaitMinutes()).isCloseTo(15.0, within(1e-6)); // 600 / 40 = 15.0 min
        assertThat(res.overflowIntervalCount()).isEqualTo(1);
        assertThat(res.residualUnservedQueue()).isZero();
        assertThat(res.maxHoldingDeficitSqm()).isCloseTo(75.0, within(1e-6));

        OperationsDtos.QueueIntervalRowDto r2 = res.intervals().get(1);
        assertThat(r2.openingQueue()).isEqualTo(300);
        assertThat(r2.gateCapacity()).isEqualTo(600);
        assertThat(r2.endingQueue()).isEqualTo(600);
        assertThat(r2.overflow()).isTrue();
        assertThat(r2.overflowPersons()).isEqualTo(150);
        assertThat(r2.holdingDeficitSqm()).isCloseTo(75.0, within(1e-6));
        assertThat(res.warnings()).anyMatch(w -> "HOLDING_SPACE_OVERFLOW".equals(w.code()) && "CRITICAL".equals(w.severity()));
    }

    @Test
    void doesNotInventSilentDensityWhenHoldingDensityMissing() {
        CapacityDtos.CapacityCalculationResult capResult = buildCapacityWithQueueZone(null, 0);
        InfrastructureElement g1 = entryGate(1L, "North Gate", 20.0, true); // 300 / 15 min
        List<ArrivalWaveInterval> waves = List.of(wave(1L, 0, 15, 450, null)); // 150 queued

        OperationsDtos.QueueProjectionResult res = queueProjectionService.project(
                450, null, waves, List.of(g1), null, capResult);

        assertThat(res.effectiveHoldingCapacity()).isZero();
        assertThat(res.effectiveHoldingDensityPerSqm()).isNull();
        assertThat(res.overflowIntervalCount()).isEqualTo(1);
        assertThat(res.maxHoldingDeficitSqm()).isNull();
        assertThat(res.intervals().get(0).holdingDeficitSqm()).isNull();
        assertThat(res.warnings()).anyMatch(w -> "HOLDING_DENSITY_MISSING".equals(w.code()));
    }

    @Test
    void handlesParallelTargetedGatesInactiveGateAndCapacityReviewFallback() {
        InfrastructureElement g1 = entryGate(1L, "Gate A", 20.0, true);  // 300 cap per 15 min
        InfrastructureElement g2 = entryGate(2L, "Gate B", 40.0, true);  // 600 cap per 15 min
        InfrastructureElement gInactive = entryGate(3L, "Closed Gate", 30.0, false);

        // Parallel waves in 0-15: Gate A receives 500 -> 200 queue; Gate B receives 200 -> 0 queue
        // Gate A queue must NOT be absorbed by Gate B's unused capacity!
        List<ArrivalWaveInterval> parallel = List.of(
                wave(1L, 0, 15, 500, g1),
                wave(2L, 0, 15, 200, g2)
        );
        OperationsDtos.QueueProjectionResult parallelRes = queueProjectionService.project(
                700, null, parallel, List.of(g1, g2, gInactive), null, null);

        assertThat(parallelRes.intervals().get(0).endingQueue()).isEqualTo(200);
        assertThat(parallelRes.intervals().get(1).endingQueue()).isZero();
        assertThat(parallelRes.peakQueueLength()).isEqualTo(200);
        assertThat(parallelRes.residualUnservedQueue()).isEqualTo(200);

        // Targeting an inactive gate yields 0 capacity & unbounded wait warning
        OperationsDtos.QueueProjectionResult inactiveRes = queueProjectionService.project(
                100, null, List.of(wave(3L, 0, 15, 100, gInactive)),
                List.of(g1, gInactive), null, null);
        assertThat(inactiveRes.hasUnboundedWait()).isTrue();
        assertThat(inactiveRes.intervals().get(0).unboundedWait()).isTrue();
        assertThat(inactiveRes.warnings()).anyMatch(w -> "INACTIVE_TARGET_GATE".equals(w.code()));

        // Fallback to CapacityReview entryThroughputPerMin when zero ENTRY_GATE elements exist
        CapacityReview review = new CapacityReview();
        review.setEntryThroughputPerMin(30.0);
        OperationsDtos.QueueProjectionResult fallbackRes = queueProjectionService.project(
                450, null, List.of(wave(4L, 0, 15, 450, null)),
                List.of(), review, null);
        assertThat(fallbackRes.totalActiveEntryRatePerMin()).isCloseTo(30.0, within(1e-6));
        assertThat(fallbackRes.intervals().get(0).gateCapacity()).isEqualTo(450);
        assertThat(fallbackRes.residualUnservedQueue()).isZero();
    }
}
