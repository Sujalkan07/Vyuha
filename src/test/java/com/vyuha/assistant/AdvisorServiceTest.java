package com.vyuha.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.capacity.CapacityDtos;
import com.vyuha.capacity.CapacityReviewStatus;
import com.vyuha.capacity.GoverningConstraint;
import com.vyuha.infrastructure.ConflictType;
import com.vyuha.infrastructure.FindingReviewStatus;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.network.BottleneckFindingType;
import com.vyuha.network.NetworkDtos;
import com.vyuha.simulation.SimulationConfig;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.simulation.SimulationScenarioType;
import com.vyuha.venue.AreaBreakdownResult;
import com.vyuha.venue.VenueDtos;
import com.vyuha.venue.WorkspaceMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdvisorServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GuidelineSearchService guidelineSearchService = new GuidelineSearchService(objectMapper);
    private final AdvisorService advisorService = new AdvisorService(
            guidelineSearchService, new LocalDeterministicAssistantProvider(), objectMapper);

    @Test
    void translatesVerifiedJavaMetricsConflictsBottlenecksAndSimWithoutInventingMathOrSafeLabels() {
        VenueDtos.VenuePlanDto plan = new VenueDtos.VenuePlanDto(
                1L, 10L, WorkspaceMode.INTERACTIVE_MAP, "campus-quadrangle", null,
                false, null, null, null, null, "POLYGON ((0 0, 100 0, 100 80, 0 80, 0 0))",
                List.of(), null, true, false, true, 1.0, null);
        AreaBreakdownResult area = new AreaBreakdownResult(
                true, 8000.0, 360.0, 1200.0, 600.0, 1800.0, 6200.0,
                77.5, 15.0, 7.5, 22.5, 0.0, 0.0, Map.of(), List.of(), null);
        VenueDtos.VenueWorkspaceDto venue = new VenueDtos.VenueWorkspaceDto(
                10L, "Campus Fest", 3500, false, plan, List.of(), area, List.of(), null);

        CapacityDtos.CapacityCalculationResult cap = new CapacityDtos.CapacityCalculationResult(
                true, 8000.0, 4800.0, 3500, 9600, 2400, 2800, 2400, 800, 200, 1000, 5000,
                1000, GoverningConstraint.HOLDING_SPACE, List.of(GoverningConstraint.HOLDING_SPACE),
                "Holding space allows 1000 persons.", -2500, 350.0, true,
                2, 2, 0, 0, CapacityReviewStatus.PENDING_REVIEW, List.of(), List.of());

        InfrastructureDtos.ConflictFindingDto conflict = new InfrastructureDtos.ConflictFindingDto(
                51L, "EMERG-STALL-3", ConflictType.EMERGENCY_LANE_INTRUSION,
                ConflictType.EMERGENCY_LANE_INTRUSION.getDisplayName(), FindingSeverity.CRITICAL,
                "Food Stall #3 intrudes into East Ambulance Lane",
                "Food Stall #3 overlaps East Ambulance Lane by 1.40 m.",
                "1.40 m overlap", 72.5, 38.0, List.of(12L, 15L),
                true, FindingReviewStatus.OPEN, "Open", null, null, null, null);

        NetworkDtos.BottleneckFindingDto bottleneck = new NetworkDtos.BottleneckFindingDto(
                61L, "NARROW-C2", BottleneckFindingType.NARROW_WALKWAY,
                BottleneckFindingType.NARROW_WALKWAY.getDisplayName(), FindingSeverity.HIGH,
                "Main Corridor C2 is narrower than 2.4 m",
                "Corridor width is 1.80 m.", "1.80 m", "< 2.40 m", 55.0, 40.0,
                List.of(3L, 4L), List.of(9L), true, FindingReviewStatus.OPEN, "Open", null, null, null, null);

        SimulationDtos.SimulationMetricsSummary simMetrics = new SimulationDtos.SimulationMetricsSummary(
                350, 350, 338, 12, 0, 12, 3.15, 55.0, 40.0, 120.0, "Main Corridor C2",
                48, 95.0, 62.5, 24.0, 88.0, 2.0, 110.0, 185.0, 96.57, null,
                List.of(new SimulationDtos.HotspotSummary("Main Corridor C2", 55.0, 40.0, 3.15, 88.0, 1.8, 1.8)),
                List.of());
        SimulationDtos.SimulationRunSummaryDto latestRun = new SimulationDtos.SimulationRunSummaryDto(
                91L, 7L, 1, "Baseline V1", "V1 · Arrival Rush (seed 42)",
                SimulationScenarioType.ARRIVAL_RUSH, "Arrival Rush (2.5x Surge)", 42L, 300, 350,
                SimulationConfig.defaults(), simMetrics, "/api/events/10/simulation/runs/91/frames",
                "Organizer", Instant.now());

        AssistantDtos.AdvisorResponseDto resp = advisorService.explain(new AdvisorService.AdvisorInput(
                10L, "Campus Fest", 3500, venue, cap, List.of(conflict), List.of(bottleneck),
                latestRun, "ALL", "How do I fix the ambulance lane?"));

        assertThat(resp.attributionTag()).isEqualTo(AssistantConstants.ATTRIBUTION_TAG);
        assertThat(resp.provider()).isEqualTo("VYUHA_LOCAL_JAVA");
        assertThat(resp.verifiedMetrics().grossAreaSqm()).isEqualTo(8000.0);
        assertThat(resp.verifiedMetrics().usableAreaSqm()).isEqualTo(6200.0);
        assertThat(resp.verifiedMetrics().preliminaryCapacity()).isEqualTo(1000);
        assertThat(resp.verifiedMetrics().activeConflictCount()).isEqualTo(1);
        assertThat(resp.verifiedMetrics().activeBottleneckCount()).isEqualTo(1);
        assertThat(resp.verifiedMetrics().latestPeakDensityPsm()).isEqualTo(3.15);

        assertThat(resp.recommendations()).hasSizeGreaterThanOrEqualTo(5);
        // CRITICAL items precede HIGH and LOW
        assertThat(resp.recommendations().get(0).severity()).isEqualTo("CRITICAL");
        assertThat(resp.recommendations())
                .anySatisfy(item -> {
                    assertThat(item.title()).contains("Food Stall #3");
                    assertThat(item.measuredEvidence()).contains("1.40 m");
                    assertThat(item.manualRemediationSteps()).isNotEmpty();
                    assertThat(item.citations()).isNotEmpty();
                })
                .anySatisfy(item -> {
                    assertThat(item.title()).contains("Main Corridor C2");
                    assertThat(item.measuredEvidence()).contains("1.80 m");
                });

        assertThat(resp.citations()).isNotEmpty();
        assertThat(resp.executiveSummary()).contains("6200.00 m²").contains("1000");
        assertThat(resp.executiveSummary().toUpperCase())
                .doesNotContain("STAMPEDE-PROOF")
                .doesNotContain("CERTIFIED SAFE")
                .doesNotContain("APPROVED BY VYUHA");
    }

    @Test
    void zeroFindingsStillEnforcesHumanSafetyReviewWithoutDeclaringVenueSafe() {
        VenueDtos.VenuePlanDto plan = new VenueDtos.VenuePlanDto(
                1L, 20L, WorkspaceMode.INTERACTIVE_MAP, "campus-quadrangle", null,
                false, null, null, null, null, "POLYGON ((0 0, 50 0, 50 50, 0 50, 0 0))",
                List.of(), null, true, false, true, 1.0, null);
        AreaBreakdownResult area = new AreaBreakdownResult(
                true, 2500.0, 200.0, 0.0, 0.0, 0.0, 2500.0,
                100.0, 0.0, 0.0, 0.0, 0.0, 0.0, Map.of(), List.of(), null);
        VenueDtos.VenueWorkspaceDto venue = new VenueDtos.VenueWorkspaceDto(
                20L, "Clean Site", 400, false, plan, List.of(), area, List.of(), null);

        CapacityDtos.CapacityCalculationResult cap = new CapacityDtos.CapacityCalculationResult(
                true, 2500.0, 2500.0, 400, 2500, 1200, 1200, 1200, 600, 0, 600, 1000,
                600, GoverningConstraint.HOLDING_SPACE, List.of(GoverningConstraint.HOLDING_SPACE),
                "Holding space governs.", 200, 66.7, false,
                1, 1, 1, 0, CapacityReviewStatus.REVIEWER_VERIFIED, List.of(), List.of());

        AssistantDtos.AdvisorResponseDto resp = advisorService.explain(new AdvisorService.AdvisorInput(
                20L, "Clean Site", 400, venue, cap, List.of(), List.of(), null, "ALL", null));

        assertThat(resp.recommendations()).isEmpty();
        assertThat(resp.executiveSummary())
                .contains("No automated layout warnings were detected")
                .contains("human professional safety review");
        assertThat(resp.executiveSummary())
                .doesNotMatch("(?is).*\\b(safe|certified|stampede-proof)\\b.*");
    }
}
