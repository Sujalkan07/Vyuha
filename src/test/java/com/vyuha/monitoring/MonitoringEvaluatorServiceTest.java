package com.vyuha.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyuha.capacity.AssumptionReviewStatus;
import com.vyuha.capacity.CapacityDtos;
import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.FindingSeverity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MonitoringEvaluatorServiceTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final MonitoringEvaluatorService evaluator = new MonitoringEvaluatorService(geometryUtil);

    private FunctionalZone makeZone(long id, String code, String name, double w, double h) {
        FunctionalZone z = new FunctionalZone();
        ReflectionTestUtils.setField(z, "id", id);
        z.setZoneCode(code);
        z.setName(name);
        z.setZoneType(ZoneType.AUDIENCE);
        z.setColorHex(ZoneType.AUDIENCE.getFillColor());
        z.setPolygonWkt(String.format("POLYGON ((0 0, %.1f 0, %.1f %.1f, 0 %.1f, 0 0))", w, w, h, h));
        return z;
    }

    private CapacityDtos.ZoneCapacityRow makeCapRow(FunctionalZone z, Integer cap, double areaSqm) {
        return new CapacityDtos.ZoneCapacityRow(
                z.getId(),
                z.getZoneCode(),
                z.getName(),
                z.getZoneType(),
                z.getZoneType().getDisplayName(),
                z.getZoneType().getCapacityBucket(),
                true,
                z.getColorHex(),
                areaSqm,
                areaSqm,
                false,
                2.0,
                cap,
                "Test Advisory",
                "Reviewer",
                Instant.now(),
                "Reviewer",
                AssumptionReviewStatus.REVIEWER_VERIFIED,
                null
        );
    }

    private OccupancyReading makeReading(long id,
                                         FunctionalZone zone,
                                         OccupancyDataSource source,
                                         int count,
                                         Instant recordedAt) {
        OccupancyReading r = new OccupancyReading();
        ReflectionTestUtils.setField(r, "id", id);
        r.setZone(zone);
        r.setDataSource(source);
        r.setCountValue(count);
        r.setRecordedAt(recordedAt);
        r.setRecordedBy("Operator");
        return r;
    }

    @Test
    void enforcesStrictZeroBlendingAcrossSources() {
        Instant now = Instant.parse("2026-11-20T12:00:00Z");
        FunctionalZone z1 = makeZone(10L, "AUD-1", "Main Audience", 10.0, 10.0);
        CapacityDtos.ZoneCapacityRow cap1 = makeCapRow(z1, 200, 100.0);

        List<OccupancyReading> readings = List.of(
                makeReading(1L, z1, OccupancyDataSource.MANUAL_STAFF, 120, now.minus(Duration.ofMinutes(2))),
                makeReading(2L, z1, OccupancyDataSource.PASS_SCAN, 85, now.minus(Duration.ofMinutes(1))),
                makeReading(3L, z1, OccupancyDataSource.PRACTICE_SIMULATION, 190, now)
        );

        var manual = evaluator.evaluateChannel(
                OccupancyDataSource.MANUAL_STAFF, List.of(z1), List.of(cap1), readings, List.of(), now);
        var pass = evaluator.evaluateChannel(
                OccupancyDataSource.PASS_SCAN, List.of(z1), List.of(cap1), readings, List.of(), now);
        var practice = evaluator.evaluateChannel(
                OccupancyDataSource.PRACTICE_SIMULATION, List.of(z1), List.of(cap1), readings, List.of(), now);

        assertThat(manual.summary().totalCount()).isEqualTo(120);
        assertThat(manual.zones().get(0).currentCount()).isEqualTo(120);
        assertThat(manual.zones().get(0).occupancyPct()).isEqualTo(60.0);
        assertThat(manual.zones().get(0).band()).isEqualTo(ZoneCongestionBand.MODERATE_LOAD);

        assertThat(pass.summary().totalCount()).isEqualTo(85);
        assertThat(pass.zones().get(0).currentCount()).isEqualTo(85);
        assertThat(pass.zones().get(0).occupancyPct()).isEqualTo(42.5);
        assertThat(pass.zones().get(0).band()).isEqualTo(ZoneCongestionBand.LOW_LOAD);

        assertThat(practice.summary().totalCount()).isEqualTo(190);
        assertThat(practice.zones().get(0).currentCount()).isEqualTo(190);
        assertThat(practice.zones().get(0).occupancyPct()).isEqualTo(95.0);
        assertThat(practice.zones().get(0).band()).isEqualTo(ZoneCongestionBand.CRITICAL_LOAD);
    }

    @Test
    void detectsThresholdAlertsAndAutoResolvesWhenLoadDrops() {
        Instant now = Instant.parse("2026-11-20T12:00:00Z");
        FunctionalZone z1 = makeZone(11L, "AUD-1", "Main Audience", 10.0, 10.0);
        CapacityDtos.ZoneCapacityRow cap1 = makeCapRow(z1, 100, 100.0);

        // 85 / 100 -> CAPACITY_WARNING (HIGH)
        List<OccupancyReading> warnReadings = List.of(
                makeReading(10L, z1, OccupancyDataSource.MANUAL_STAFF, 85, now.minus(Duration.ofMinutes(1)))
        );
        var warnEval = evaluator.evaluateChannel(
                OccupancyDataSource.MANUAL_STAFF, List.of(z1), List.of(cap1), warnReadings, List.of(), now);
        var warnPlan = evaluator.evaluateAlertMutations(
                OccupancyDataSource.MANUAL_STAFF, warnEval.zones(), List.of(), now);

        assertThat(warnPlan.toCreate()).hasSize(1);
        assertThat(warnPlan.toCreate().get(0).alertType()).isEqualTo(MonitoringAlertType.CAPACITY_WARNING);
        assertThat(warnPlan.toCreate().get(0).severity()).isEqualTo(FindingSeverity.HIGH);

        // Escalate to 98 / 100 -> fires CAPACITY_CRITICAL & resolves existing CAPACITY_WARNING
        MonitoringAlertEvent openWarn = new MonitoringAlertEvent();
        ReflectionTestUtils.setField(openWarn, "id", 501L);
        openWarn.setZone(z1);
        openWarn.setDataSource(OccupancyDataSource.MANUAL_STAFF);
        openWarn.setAlertType(MonitoringAlertType.CAPACITY_WARNING);
        openWarn.setSeverity(FindingSeverity.HIGH);

        List<OccupancyReading> critReadings = List.of(
                makeReading(11L, z1, OccupancyDataSource.MANUAL_STAFF, 98, now)
        );
        var critEval = evaluator.evaluateChannel(
                OccupancyDataSource.MANUAL_STAFF, List.of(z1), List.of(cap1), critReadings, List.of(), now);
        var critPlan = evaluator.evaluateAlertMutations(
                OccupancyDataSource.MANUAL_STAFF, critEval.zones(), List.of(openWarn), now);

        assertThat(critPlan.alertIdsToResolve()).containsExactly(501L);
        assertThat(critPlan.toCreate()).hasSize(1);
        assertThat(critPlan.toCreate().get(0).alertType()).isEqualTo(MonitoringAlertType.CAPACITY_CRITICAL);
        assertThat(critPlan.toCreate().get(0).severity()).isEqualTo(FindingSeverity.CRITICAL);

        // Drop back to 45 / 100 -> resolves active CAPACITY_CRITICAL without creating new alerts
        MonitoringAlertEvent openCrit = new MonitoringAlertEvent();
        ReflectionTestUtils.setField(openCrit, "id", 502L);
        openCrit.setZone(z1);
        openCrit.setDataSource(OccupancyDataSource.MANUAL_STAFF);
        openCrit.setAlertType(MonitoringAlertType.CAPACITY_CRITICAL);
        openCrit.setSeverity(FindingSeverity.CRITICAL);

        List<OccupancyReading> coolReadings = List.of(
                makeReading(12L, z1, OccupancyDataSource.MANUAL_STAFF, 45, now.plusSeconds(30))
        );
        var coolEval = evaluator.evaluateChannel(
                OccupancyDataSource.MANUAL_STAFF, List.of(z1), List.of(cap1), coolReadings, List.of(), now.plusSeconds(30));
        var coolPlan = evaluator.evaluateAlertMutations(
                OccupancyDataSource.MANUAL_STAFF, coolEval.zones(), List.of(openCrit), now.plusSeconds(30));

        assertThat(coolPlan.toCreate()).isEmpty();
        assertThat(coolPlan.alertIdsToResolve()).containsExactly(502L);
    }

    @Test
    void detectsStaleReadingsAndAutoResolvesOnFreshUpdate() {
        Instant now = Instant.parse("2026-11-20T12:30:00Z");
        FunctionalZone z1 = makeZone(12L, "QUE-1", "North Queue", 10.0, 10.0);
        CapacityDtos.ZoneCapacityRow cap1 = makeCapRow(z1, 100, 100.0);

        MonitoringAlertRule customRule = new MonitoringAlertRule();
        customRule.setZone(z1);
        customRule.setWarningThresholdPct(75.0);
        customRule.setCriticalThresholdPct(90.0);
        customRule.setStaleAfterMinutes(10);

        // 15 minutes old >= 10 min threshold -> stale + STALE_DATA alert
        List<OccupancyReading> staleReadings = List.of(
                makeReading(20L, z1, OccupancyDataSource.MANUAL_STAFF, 40, now.minus(Duration.ofMinutes(15)))
        );
        var staleEval = evaluator.evaluateChannel(
                OccupancyDataSource.MANUAL_STAFF, List.of(z1), List.of(cap1), staleReadings, List.of(customRule), now);

        assertThat(staleEval.summary().stale()).isTrue();
        assertThat(staleEval.summary().staleZoneCount()).isEqualTo(1);
        assertThat(staleEval.zones().get(0).stale()).isTrue();
        assertThat(staleEval.zones().get(0).ageMinutes()).isEqualTo(15L);
        assertThat(staleEval.zones().get(0).band()).isEqualTo(ZoneCongestionBand.STALE);

        var stalePlan = evaluator.evaluateAlertMutations(
                OccupancyDataSource.MANUAL_STAFF, staleEval.zones(), List.of(), now);
        assertThat(stalePlan.toCreate()).hasSize(1);
        assertThat(stalePlan.toCreate().get(0).alertType()).isEqualTo(MonitoringAlertType.STALE_DATA);
        assertThat(stalePlan.toCreate().get(0).severity()).isEqualTo(FindingSeverity.LOW);

        // Fresh reading arrives -> STALE_DATA resolves
        MonitoringAlertEvent openStale = new MonitoringAlertEvent();
        ReflectionTestUtils.setField(openStale, "id", 601L);
        openStale.setZone(z1);
        openStale.setDataSource(OccupancyDataSource.MANUAL_STAFF);
        openStale.setAlertType(MonitoringAlertType.STALE_DATA);
        openStale.setSeverity(FindingSeverity.LOW);

        List<OccupancyReading> freshReadings = List.of(
                makeReading(21L, z1, OccupancyDataSource.MANUAL_STAFF, 42, now.minus(Duration.ofMinutes(2)))
        );
        var freshEval = evaluator.evaluateChannel(
                OccupancyDataSource.MANUAL_STAFF, List.of(z1), List.of(cap1), freshReadings, List.of(customRule), now);
        var freshPlan = evaluator.evaluateAlertMutations(
                OccupancyDataSource.MANUAL_STAFF, freshEval.zones(), List.of(openStale), now);

        assertThat(freshEval.zones().get(0).stale()).isFalse();
        assertThat(freshEval.zones().get(0).band()).isEqualTo(ZoneCongestionBand.LOW_LOAD);
        assertThat(freshPlan.alertIdsToResolve()).containsExactly(601L);
        assertThat(freshPlan.toCreate()).isEmpty();
    }
}
