package com.vyuha.simulation;

/**
 * Deterministic configuration parameters for a crowd simulation run.
 */
public record SimulationConfig(
        SimulationScenarioType scenarioType,
        long randomSeed,
        int agentCount,
        int durationSeconds,
        double gridCellSizeM,
        double highDensityThresholdPsm,
        double freeFlowSpeedMps,
        String targetEdgeUid,
        Long targetEdgeId,
        String targetExitNodeUid,
        Long targetExitInfraId,
        int disruptionStartSec
) {
    public static final long DEFAULT_RANDOM_SEED = 42L;
    public static final int DEFAULT_AGENT_COUNT = 350;
    public static final int MIN_AGENT_COUNT = 20;
    public static final int MAX_AGENT_COUNT = 2500;
    public static final int DEFAULT_DURATION_SECONDS = 300;
    public static final int MIN_DURATION_SECONDS = 60;
    public static final int MAX_DURATION_SECONDS = 1800;
    public static final double DEFAULT_GRID_CELL_SIZE_M = 4.0;
    public static final double MIN_GRID_CELL_SIZE_M = 2.0;
    public static final double MAX_GRID_CELL_SIZE_M = 10.0;
    public static final double DEFAULT_HIGH_DENSITY_THRESHOLD_PSM = 2.0;
    public static final double MIN_HIGH_DENSITY_THRESHOLD_PSM = 0.5;
    public static final double MAX_HIGH_DENSITY_THRESHOLD_PSM = 6.0;
    public static final double DEFAULT_FREE_FLOW_SPEED_MPS = 1.35;
    public static final double MIN_FREE_FLOW_SPEED_MPS = 0.6;
    public static final double MAX_FREE_FLOW_SPEED_MPS = 2.2;

    public static SimulationConfig defaults() {
        return normalized(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static SimulationConfig normalized(SimulationScenarioType scenarioType,
                                              Long randomSeed,
                                              Integer agentCount,
                                              Integer durationSeconds,
                                              Double gridCellSizeM,
                                              Double highDensityThresholdPsm,
                                              Double freeFlowSpeedMps,
                                              String targetEdgeUid,
                                              Long targetEdgeId,
                                              String targetExitNodeUid,
                                              Long targetExitInfraId,
                                              Integer disruptionStartSec) {
        SimulationScenarioType resolvedScenario = scenarioType != null
                ? scenarioType
                : SimulationScenarioType.NORMAL_OPERATIONS;
        long resolvedSeed = randomSeed != null ? randomSeed : DEFAULT_RANDOM_SEED;
        int resolvedAgents = clampInt(agentCount != null ? agentCount : DEFAULT_AGENT_COUNT,
                MIN_AGENT_COUNT, MAX_AGENT_COUNT);
        int resolvedDuration = clampInt(durationSeconds != null ? durationSeconds : DEFAULT_DURATION_SECONDS,
                MIN_DURATION_SECONDS, MAX_DURATION_SECONDS);
        double resolvedCellSize = clampDouble(gridCellSizeM != null ? gridCellSizeM : DEFAULT_GRID_CELL_SIZE_M,
                MIN_GRID_CELL_SIZE_M, MAX_GRID_CELL_SIZE_M);
        double resolvedHighDensity = clampDouble(
                highDensityThresholdPsm != null ? highDensityThresholdPsm : DEFAULT_HIGH_DENSITY_THRESHOLD_PSM,
                MIN_HIGH_DENSITY_THRESHOLD_PSM, MAX_HIGH_DENSITY_THRESHOLD_PSM);
        double resolvedSpeed = clampDouble(
                freeFlowSpeedMps != null ? freeFlowSpeedMps : DEFAULT_FREE_FLOW_SPEED_MPS,
                MIN_FREE_FLOW_SPEED_MPS, MAX_FREE_FLOW_SPEED_MPS);
        int maxDisruptionStart = Math.max(0, resolvedDuration / 2);
        int resolvedDisruptionStart = clampInt(disruptionStartSec != null ? disruptionStartSec : 0,
                0, maxDisruptionStart);

        return new SimulationConfig(
                resolvedScenario,
                resolvedSeed,
                resolvedAgents,
                resolvedDuration,
                round2(resolvedCellSize),
                round2(resolvedHighDensity),
                round2(resolvedSpeed),
                trimToNull(targetEdgeUid),
                targetEdgeId,
                trimToNull(targetExitNodeUid),
                targetExitInfraId,
                resolvedDisruptionStart
        );
    }

    private static int clampInt(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private static double clampDouble(double val, double min, double max) {
        if (!Double.isFinite(val)) {
            return min;
        }
        return Math.max(min, Math.min(max, val));
    }

    private static double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    private static String trimToNull(String val) {
        if (val == null) {
            return null;
        }
        String trimmed = val.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
