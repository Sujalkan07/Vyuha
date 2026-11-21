package com.vyuha.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComparisonServiceTest {

    private final ComparisonService comparisonService = new ComparisonService();

    @Test
    void computesExactDeltasTrendsAndParityFlag() {
        SimulationDtos.SimulationRunSummaryDto before = sampleRun(1L, 1, "Initial V1",
                SimulationScenarioType.ARRIVAL_RUSH, 42L, 300, 250,
                3.80, 48, 22.5, 95.0, 110.0, 165.0, 12);
        SimulationDtos.SimulationRunSummaryDto after = sampleRun(2L, 2, "Widened V2",
                SimulationScenarioType.ARRIVAL_RUSH, 42L, 300, 250,
                1.40, 18, 6.0, 0.0, 62.0, 98.0, 0);

        SimulationDtos.SimulationComparisonDto cmp = comparisonService.compare(
                before, after, null, null, "/frames/1", "/frames/2");

        assertThat(cmp.parityMatched()).isTrue();
        assertThat(cmp.parityWarning()).isNull();
        assertThat(cmp.disclaimer()).contains("Simplified Planning Simulation");
        assertThat(cmp.peakDensity().delta()).isEqualTo(-2.40);
        assertThat(cmp.peakDensity().trend()).isEqualTo("IMPROVED");
        assertThat(cmp.peakQueueLength().delta()).isEqualTo(-30.0);
        assertThat(cmp.peakQueueLength().trend()).isEqualTo("IMPROVED");
        assertThat(cmp.unservedBlockedAgents().delta()).isEqualTo(-12.0);
        assertThat(cmp.unservedBlockedAgents().trend()).isEqualTo("IMPROVED");
        assertThat(cmp.narrativeNotes()).isNotEmpty();
    }

    @Test
    void flagsParityMismatchAndSafeZeroDenominator() {
        SimulationDtos.SimulationRunSummaryDto before = sampleRun(1L, 1, "Baseline V1",
                SimulationScenarioType.NORMAL_OPERATIONS, 10L, 300, 200,
                1.00, 10, 2.0, 0.0, 50.0, 80.0, 0);
        SimulationDtos.SimulationRunSummaryDto after = sampleRun(2L, 2, "Rush V2",
                SimulationScenarioType.ARRIVAL_RUSH, 99L, 300, 200,
                2.50, 25, 8.0, 15.0, 75.0, 120.0, 0);

        SimulationDtos.SimulationComparisonDto cmp = comparisonService.compare(
                before, after, null, null, "/frames/1", "/frames/2");

        assertThat(cmp.parityMatched()).isFalse();
        assertThat(cmp.parityWarning()).contains("do not share identical parameters");
        assertThat(cmp.congestionDuration().deltaPct()).isNull();
        assertThat(cmp.congestionDuration().trend()).isEqualTo("WORSE");
        assertThat(cmp.unservedBlockedAgents().trend()).isEqualTo("UNCHANGED");
    }

    private SimulationDtos.SimulationRunSummaryDto sampleRun(Long id,
                                                             int snapVer,
                                                             String snapLabel,
                                                             SimulationScenarioType scenario,
                                                             long seed,
                                                             int durationSec,
                                                             int agents,
                                                             double peakDensity,
                                                             int peakQueue,
                                                             double maxWait,
                                                             double congestionSec,
                                                             double avgClear,
                                                             double p95Clear,
                                                             int unserved) {
        SimulationDtos.SimulationMetricsSummary metrics = new SimulationDtos.SimulationMetricsSummary(
                agents, agents, agents - unserved, unserved, 0, unserved,
                peakDensity, 40.0, 30.0, 50.0, "Corridor",
                peakQueue, 45.0, maxWait, maxWait / 2.0,
                congestionSec, 2.0, avgClear, p95Clear,
                ((agents - unserved) * 100.0) / agents,
                "Test note", List.of(), List.of());

        return new SimulationDtos.SimulationRunSummaryDto(
                id, (long) snapVer, snapVer, snapLabel, "Run " + id,
                scenario, scenario.getDisplayName(), seed, durationSec, agents,
                SimulationConfig.normalized(scenario, seed, agents, durationSec,
                        4.0, 2.0, 1.35, null, null, null, null, 0),
                metrics, "/frames/" + id, "Tester", Instant.now());
    }
}
