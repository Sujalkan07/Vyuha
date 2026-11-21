package com.vyuha.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Pure-Java Before-and-After comparison engine evaluating metric deltas and run parity
 * across two deterministic simulation runs.
 */
@Service
public class ComparisonService {

    public SimulationDtos.SimulationComparisonDto compare(SimulationDtos.SimulationRunSummaryDto beforeRun,
                                                          SimulationDtos.SimulationRunSummaryDto afterRun,
                                                          SimulationDtos.SerializedLayout beforeLayout,
                                                          SimulationDtos.SerializedLayout afterLayout,
                                                          String beforeFramesUrl,
                                                          String afterFramesUrl) {
        if (beforeRun == null || afterRun == null) {
            throw new IllegalArgumentException("Both Before and After simulation runs are required for comparison.");
        }
        SimulationDtos.SimulationMetricsSummary bm = beforeRun.metrics();
        SimulationDtos.SimulationMetricsSummary am = afterRun.metrics();
        if (bm == null || am == null) {
            throw new IllegalArgumentException("Both simulation runs must contain computed metrics summaries.");
        }

        boolean parityMatched = beforeRun.scenarioType() == afterRun.scenarioType()
                && beforeRun.randomSeed() == afterRun.randomSeed()
                && beforeRun.agentCount() == afterRun.agentCount()
                && beforeRun.durationSeconds() == afterRun.durationSeconds();

        String parityWarning = parityMatched
                ? null
                : String.format(Locale.US,
                "Comparison runs do not share identical parameters (Before: %s, seed %d, %d agents, %ds vs After: %s, seed %d, %d agents, %ds). Re-run paired comparison for strict apples-to-apples parity.",
                beforeRun.scenarioType().name(), beforeRun.randomSeed(), beforeRun.agentCount(), beforeRun.durationSeconds(),
                afterRun.scenarioType().name(), afterRun.randomSeed(), afterRun.agentCount(), afterRun.durationSeconds());

        SimulationDtos.KpiDelta peakDensity = delta(
                "peakDensity", "Peak Local Density", "p/m²",
                bm.peakLocalDensityPsm(), am.peakLocalDensityPsm(), true);
        SimulationDtos.KpiDelta peakQueueLength = delta(
                "peakQueueLength", "Peak Queue Length", "persons",
                bm.peakQueueLength(), am.peakQueueLength(), true);
        SimulationDtos.KpiDelta maxQueueWaitTime = delta(
                "maxQueueWaitTime", "Max Queue Wait Time", "s",
                bm.maxQueueWaitTimeSec(), am.maxQueueWaitTimeSec(), true);
        SimulationDtos.KpiDelta congestionDuration = delta(
                "congestionDuration", "Bottleneck Congestion Duration", "s",
                bm.bottleneckCongestionDurationSec(), am.bottleneckCongestionDurationSec(), true);
        SimulationDtos.KpiDelta avgTravelTime = delta(
                "avgTravelTime", "Average Clearance Time", "s",
                bm.avgTravelClearanceTimeSec(), am.avgTravelClearanceTimeSec(), true);
        SimulationDtos.KpiDelta p95TravelTime = delta(
                "p95TravelTime", "95th-Pct Clearance Time", "s",
                bm.p95TravelClearanceTimeSec(), am.p95TravelClearanceTimeSec(), true);
        SimulationDtos.KpiDelta unservedBlockedAgents = delta(
                "unservedBlockedAgents", "Unserved / Blocked Agents", "persons",
                bm.unservedBlockedAgentCount(), am.unservedBlockedAgentCount(), true);

        List<String> notes = buildNarrativeNotes(
                beforeRun, afterRun, parityMatched,
                peakDensity, peakQueueLength, maxQueueWaitTime,
                congestionDuration, p95TravelTime, unservedBlockedAgents);

        return new SimulationDtos.SimulationComparisonDto(
                beforeRun,
                afterRun,
                beforeLayout,
                afterLayout,
                beforeFramesUrl,
                afterFramesUrl,
                parityMatched,
                parityWarning,
                SimulationDtos.MANDATORY_DISCLAIMER,
                peakDensity,
                peakQueueLength,
                maxQueueWaitTime,
                congestionDuration,
                avgTravelTime,
                p95TravelTime,
                unservedBlockedAgents,
                List.copyOf(notes)
        );
    }

    SimulationDtos.KpiDelta delta(String key,
                                  String label,
                                  String unit,
                                  double beforeValue,
                                  double afterValue,
                                  boolean lowerIsBetter) {
        double before = round2(beforeValue);
        double after = round2(afterValue);
        double diff = round2(after - before);
        Double pct = Math.abs(before) > 1e-6 ? round2((diff / before) * 100.0) : null;

        String trend;
        if (Math.abs(diff) < 0.01) {
            trend = "UNCHANGED";
        } else if (lowerIsBetter) {
            trend = diff < 0 ? "IMPROVED" : "WORSE";
        } else {
            trend = diff > 0 ? "IMPROVED" : "WORSE";
        }

        return new SimulationDtos.KpiDelta(key, label, unit, before, after, diff, pct, lowerIsBetter, trend);
    }

    private List<String> buildNarrativeNotes(SimulationDtos.SimulationRunSummaryDto beforeRun,
                                             SimulationDtos.SimulationRunSummaryDto afterRun,
                                             boolean parityMatched,
                                             SimulationDtos.KpiDelta peakDensity,
                                             SimulationDtos.KpiDelta peakQueueLength,
                                             SimulationDtos.KpiDelta maxQueueWaitTime,
                                             SimulationDtos.KpiDelta congestionDuration,
                                             SimulationDtos.KpiDelta p95TravelTime,
                                             SimulationDtos.KpiDelta unservedBlockedAgents) {
        List<String> notes = new ArrayList<>();
        notes.add(String.format(Locale.US,
                "Comparing Snapshot V%d (%s) against Snapshot V%d (%s) under %s (seed %d).",
                beforeRun.snapshotVersion(), beforeRun.snapshotLabel(),
                afterRun.snapshotVersion(), afterRun.snapshotLabel(),
                afterRun.scenarioDisplayName(), afterRun.randomSeed()));

        if (!parityMatched) {
            notes.add("Warning: Simulation inputs differ between Before and After runs; re-run paired comparison for controlled parity.");
        }

        notes.add(formatDeltaSentence("Peak local density", peakDensity));
        notes.add(formatDeltaSentence("Peak queue length", peakQueueLength));
        notes.add(formatDeltaSentence("Max queue wait time", maxQueueWaitTime));
        notes.add(formatDeltaSentence("Bottleneck congestion duration", congestionDuration));
        notes.add(formatDeltaSentence("95th-percentile clearance time", p95TravelTime));
        if (beforeRun.metrics().unservedBlockedAgentCount() > 0 || afterRun.metrics().unservedBlockedAgentCount() > 0) {
            notes.add(formatDeltaSentence("Unserved / blocked agent count", unservedBlockedAgents));
        }
        return notes;
    }

    private String formatDeltaSentence(String metricName, SimulationDtos.KpiDelta kpi) {
        String direction = switch (kpi.trend()) {
            case "IMPROVED" -> "decreased";
            case "WORSE" -> "increased";
            default -> "remained unchanged";
        };
        if ("UNCHANGED".equals(kpi.trend())) {
            return String.format(Locale.US, "%s %s at %.2f %s.", metricName, direction, kpi.afterValue(), kpi.unit());
        }
        String pctStr = kpi.deltaPct() != null
                ? String.format(Locale.US, " (%+.1f%%)", kpi.deltaPct())
                : "";
        return String.format(Locale.US, "%s %s from %.2f to %.2f %s (%+.2f %s%s).",
                metricName, direction, kpi.beforeValue(), kpi.afterValue(), kpi.unit(), kpi.delta(), kpi.unit(), pctStr);
    }

    private double round2(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
