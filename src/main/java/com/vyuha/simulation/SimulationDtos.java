package com.vyuha.simulation;

import com.vyuha.common.GeometryUtil;
import com.vyuha.emergency.EmergencyAssetType;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.NetworkLayer;
import com.vyuha.network.PathwayDirection;
import com.vyuha.network.PathwayNodeType;
import java.time.Instant;
import java.util.List;

/**
 * DTO contracts for Phase 9 Layout Snapshots, Deterministic Crowd Simulation Runs,
 * Frame Playback Streams, and Side-by-Side Before-and-After Comparisons.
 */
public final class SimulationDtos {

    public static final String MANDATORY_DISCLAIMER =
            "Simplified Planning Simulation — Designed for relative layout comparison, not exact real-world emergency or stampede prediction.";

    private SimulationDtos() {
    }

    public record SnapExclusion(
            Long id,
            String label,
            String exclusionType,
            boolean corridor,
            String fillColor,
            String strokeColor,
            String polygonWkt,
            List<GeometryUtil.Point2D> points
    ) {
    }

    public record SnapZone(
            Long id,
            String zoneCode,
            String name,
            ZoneType zoneType,
            boolean walkable,
            boolean hatched,
            String fillColor,
            String strokeColor,
            String polygonWkt,
            List<GeometryUtil.PolygonShape> shapes,
            GeometryUtil.Point2D labelPoint
    ) {
    }

    public record SnapElement(
            Long id,
            String elementUid,
            InfrastructureType elementType,
            String label,
            double centerX,
            double centerY,
            double widthM,
            double heightM,
            double rotationDeg,
            Double throughputPersonsPerMin,
            boolean active,
            String fillColor,
            String strokeColor,
            String footprintWkt,
            List<GeometryUtil.Point2D> footprintPoints
    ) {
    }

    public record SnapNode(
            Long id,
            String nodeUid,
            PathwayNodeType nodeType,
            String label,
            double x,
            double y,
            Long linkedZoneId,
            Long linkedInfraId,
            String fillColor,
            String strokeColor
    ) {
    }

    public record SnapEdge(
            Long id,
            String edgeUid,
            String fromNodeUid,
            String toNodeUid,
            String label,
            NetworkLayer networkLayer,
            double widthM,
            double lengthM,
            PathwayDirection direction,
            boolean active,
            String colorHex,
            boolean dashed,
            String polylineWkt,
            List<GeometryUtil.Point2D> points
    ) {
    }

    public record SnapArrivalWave(
            int startMinute,
            int endMinute,
            int expectedArrivals,
            Long targetGateId,
            String label
    ) {
    }

    public record SnapEmergencyAsset(
            Long id,
            EmergencyAssetType assetType,
            String label,
            String geometryWkt,
            Double minWidthM
    ) {
    }

    public record SerializedLayout(
            String eventName,
            Integer expectedAttendance,
            String workspaceMode,
            String sampleMapKey,
            boolean hasBlueprint,
            String blueprintUrl,
            Integer blueprintWidthPx,
            Integer blueprintHeightPx,
            Double pixelsPerMeter,
            String boundaryWkt,
            List<GeometryUtil.Point2D> boundaryPoints,
            GeometryUtil.Bounds bounds,
            List<SnapExclusion> exclusions,
            List<SnapZone> zones,
            List<SnapElement> elements,
            List<SnapNode> nodes,
            List<SnapEdge> edges,
            List<SnapArrivalWave> arrivalWaves,
            List<SnapEmergencyAsset> emergencyAssets
    ) {
    }

    public record HotspotSummary(
            String label,
            double locationX,
            double locationY,
            double peakDensityPsm,
            double congestedDurationSec,
            double effectiveWidthM,
            double nominalWidthM
    ) {
    }

    public record TimeSeriesSample(
            double timeSec,
            int activeAgents,
            int queuingAgents,
            int congestedAgents,
            int exitedAgents,
            double peakDensityPsm
    ) {
    }

    public record SimulationMetricsSummary(
            int totalAgentsScheduled,
            int totalAgentsSpawned,
            int totalAgentsExited,
            int unservedBlockedAgentCount,
            int blockedNoRouteCount,
            int unservedAtEndCount,
            double peakLocalDensityPsm,
            Double peakDensityLocationX,
            Double peakDensityLocationY,
            double peakDensityTimeSec,
            String peakDensityLabel,
            int peakQueueLength,
            double peakQueueTimeSec,
            double maxQueueWaitTimeSec,
            double avgQueueWaitTimeSec,
            double bottleneckCongestionDurationSec,
            double highDensityThresholdPsm,
            double avgTravelClearanceTimeSec,
            double p95TravelClearanceTimeSec,
            double clearanceRatePct,
            String disruptionAppliedNote,
            List<HotspotSummary> hotspots,
            List<TimeSeriesSample> timeSeries
    ) {
    }

    /**
     * Compact frame entry for HTML5 Canvas playback.
     * agents tuples: [agentId, xMeters, yMeters, stateCode] (0=WALKING, 1=QUEUING, 2=CONGESTED, 3=EXITING, 4=BLOCKED).
     * hotCells tuples: [col, row, densityPsm] for non-negligible grid cells (>= 0.08 p/m²).
     */
    public record SimulationFrame(
            double t,
            int active,
            int queuing,
            int congested,
            int exited,
            double peakDensity,
            List<double[]> agents,
            List<double[]> hotCells
    ) {
    }

    public record SimulationFrameStream(
            double gridOriginX,
            double gridOriginY,
            double cellSizeM,
            int gridCols,
            int gridRows,
            int durationSeconds,
            double sampleIntervalSec,
            double highDensityThresholdPsm,
            List<SimulationFrame> frames
    ) {
    }

    public record CreateSnapshotRequest(
            String label,
            String notes,
            String thumbnailDataUrl
    ) {
    }

    public record RunSimulationRequest(
            Long snapshotId,
            String label,
            SimulationScenarioType scenarioType,
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
            Integer disruptionStartSec
    ) {
    }

    public record CompareSnapshotsRequest(
            Long beforeSnapshotId,
            Long afterSnapshotId,
            SimulationScenarioType scenarioType,
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
            Integer disruptionStartSec
    ) {
    }

    public record LayoutSnapshotSummaryDto(
            Long id,
            int versionNumber,
            String label,
            String notes,
            String thumbnailUrl,
            int nodeCount,
            int edgeCount,
            int elementCount,
            int zoneCount,
            String createdByName,
            Instant createdAt
    ) {
    }

    public record LayoutSnapshotDetailDto(
            LayoutSnapshotSummaryDto summary,
            SerializedLayout layout
    ) {
    }

    public record SimulationRunSummaryDto(
            Long id,
            Long snapshotId,
            int snapshotVersion,
            String snapshotLabel,
            String label,
            SimulationScenarioType scenarioType,
            String scenarioDisplayName,
            long randomSeed,
            int durationSeconds,
            int agentCount,
            SimulationConfig config,
            SimulationMetricsSummary metrics,
            String framesUrl,
            String createdByName,
            Instant createdAt
    ) {
    }

    public record SimulationRunDetailDto(
            SimulationRunSummaryDto run,
            SerializedLayout layout,
            SimulationFrameStream frameStream
    ) {
    }

    public record KpiDelta(
            String key,
            String label,
            String unit,
            double beforeValue,
            double afterValue,
            double delta,
            Double deltaPct,
            boolean lowerIsBetter,
            String trend
    ) {
    }

    public record SimulationComparisonDto(
            SimulationRunSummaryDto beforeRun,
            SimulationRunSummaryDto afterRun,
            SerializedLayout beforeLayout,
            SerializedLayout afterLayout,
            String beforeFramesUrl,
            String afterFramesUrl,
            boolean parityMatched,
            String parityWarning,
            String disclaimer,
            KpiDelta peakDensity,
            KpiDelta peakQueueLength,
            KpiDelta maxQueueWaitTime,
            KpiDelta congestionDuration,
            KpiDelta avgTravelTime,
            KpiDelta p95TravelTime,
            KpiDelta unservedBlockedAgents,
            List<String> narrativeNotes
    ) {
    }

    public record ScenarioOptionDto(
            SimulationScenarioType type,
            String displayName,
            String description,
            boolean requiresTargetEdge,
            boolean requiresTargetExit
    ) {
    }

    public record SimulationWorkspaceDto(
            Long eventId,
            String eventName,
            Integer expectedAttendance,
            boolean canManageSnapshots,
            boolean canRunSimulation,
            String disclaimer,
            boolean layoutReadyForSimulation,
            String readinessMessage,
            SerializedLayout currentLayout,
            List<LayoutSnapshotSummaryDto> snapshots,
            List<SimulationRunSummaryDto> runs,
            List<ScenarioOptionDto> scenarios
    ) {
    }
}
