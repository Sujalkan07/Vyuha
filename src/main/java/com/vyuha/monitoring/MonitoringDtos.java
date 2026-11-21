package com.vyuha.monitoring;

import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.simulation.SimulationDtos;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Request, evaluation, SSE, Control Centre, and Field View DTO contracts for Phase 10 Live Monitoring.
 */
public final class MonitoringDtos {

    private MonitoringDtos() {
    }

    public record ManualCountRequest(
            @NotNull(message = "Zone selection is required.")
            Long zoneId,
            @NotNull(message = "Count value is required.")
            @Min(value = 0, message = "Occupancy count cannot be negative.")
            @Max(value = 1_000_000, message = "Occupancy count exceeds limit.")
            Integer countValue,
            @Size(max = 500, message = "Note must not exceed 500 characters.")
            String note
    ) {
    }

    public record GeneratePassBatchRequest(
            @NotNull(message = "Batch count is required.")
            @Min(value = 1, message = "Generate at least 1 pass.")
            @Max(value = 200, message = "Batch limit is 200 passes per request.")
            Integer count,
            @Size(max = 80, message = "Label prefix must not exceed 80 characters.")
            String labelPrefix
    ) {
    }

    public record ScanPassRequest(
            @NotBlank(message = "Pass code is required.")
            @Size(max = 32, message = "Pass code must not exceed 32 characters.")
            String passCode,
            @NotNull(message = "Scan action is required.")
            PassScanAction action,
            Long gateId,
            Long zoneId
    ) {
    }

    public record AlertRuleRequest(
            Long zoneId,
            @NotNull(message = "Warning threshold (%) is required.")
            @DecimalMin(value = "5.0", message = "Warning threshold must be at least 5%.")
            @DecimalMax(value = "200.0", message = "Warning threshold cannot exceed 200%.")
            Double warningThresholdPct,
            @NotNull(message = "Critical threshold (%) is required.")
            @DecimalMin(value = "10.0", message = "Critical threshold must be at least 10%.")
            @DecimalMax(value = "300.0", message = "Critical threshold cannot exceed 300%.")
            Double criticalThresholdPct,
            @NotNull(message = "Staleness threshold (minutes) is required.")
            @Min(value = 1, message = "Staleness window must be at least 1 minute.")
            @Max(value = 1440, message = "Staleness window cannot exceed 1440 minutes.")
            Integer staleAfterMinutes
    ) {
    }

    public record PracticeStepRequest(
            Long runId,
            @Min(value = 0, message = "Frame index cannot be negative.")
            Integer frameIndex,
            Boolean resetFirst
    ) {
    }

    public record ChannelSummaryDto(
            OccupancyDataSource dataSource,
            String sourceLabel,
            String shortLabel,
            String badgeClass,
            int totalCount,
            int unzonedCount,
            Instant lastUpdatedAt,
            boolean stale,
            int staleZoneCount,
            int reportingZoneCount,
            int staleAfterMinutes
    ) {
    }

    public record ZoneOccupancyDto(
            Long zoneId,
            String zoneCode,
            String zoneName,
            ZoneType zoneType,
            String zoneTypeName,
            double usableAreaSqm,
            Integer reviewedCapacity,
            String capacityStatusLabel,
            OccupancyDataSource dataSource,
            String sourceShortLabel,
            String sourceBadgeClass,
            Integer currentCount,
            Double occupancyPct,
            Double currentDensityPsm,
            Instant lastUpdatedAt,
            String recordedBy,
            String note,
            Long ageMinutes,
            boolean stale,
            double warningThresholdPct,
            double criticalThresholdPct,
            int staleAfterMinutes,
            ZoneCongestionBand band,
            String bandLabel,
            String bandFillColor,
            String bandStrokeColor,
            boolean bandHatched,
            String bandBadgeClass,
            List<GeometryUtil.PolygonShape> shapes,
            GeometryUtil.Point2D labelPoint
    ) {
    }

    public record GateStatusDto(
            Long id,
            String label,
            InfrastructureType elementType,
            String typeName,
            boolean active,
            Double throughputPersonsPerMin,
            int checkedInCount,
            int checkedOutCount,
            Instant lastScannedAt,
            double centerX,
            double centerY
    ) {
    }

    public record QueuePressureSummaryDto(
            int holdingZoneCount,
            int holdingCapacity,
            int currentHoldingCount,
            OccupancyDataSource dataSource,
            Double holdingUtilizationPct,
            Instant lastUpdatedAt,
            boolean stale
    ) {
    }

    public record StaffDutySummaryDto(
            int totalStaffCount,
            int scheduledShiftCount,
            int onDutyNowCount,
            int openIncidentCount
    ) {
    }

    public record PreEventSafetySummaryDto(
            int incompleteChecklistCount,
            int totalChecklistCount,
            int activeConflictCount,
            int activeBottleneckCount,
            String capacityReviewStatusLabel,
            String capacityReviewBadgeClass
    ) {
    }

    public record AlertEventDto(
            Long id,
            Long zoneId,
            String zoneCode,
            String zoneName,
            OccupancyDataSource dataSource,
            String sourceShortLabel,
            String sourceBadgeClass,
            FindingSeverity severity,
            String severityBadgeClass,
            MonitoringAlertType alertType,
            String alertTypeLabel,
            String message,
            Instant triggeredAt,
            String acknowledgedBy,
            Instant acknowledgedAt,
            Instant resolvedAt,
            boolean active
    ) {
    }

    public record AlertRuleDto(
            Long id,
            Long zoneId,
            String zoneCode,
            String zoneName,
            double warningThresholdPct,
            double criticalThresholdPct,
            int staleAfterMinutes,
            boolean defaultRule
    ) {
    }

    public record AttendeePassDto(
            Long id,
            String passCode,
            String holderLabel,
            PassStatus status,
            String statusLabel,
            String statusBadgeClass,
            Long lastGateId,
            String lastGateLabel,
            Long lastZoneId,
            String lastZoneLabel,
            Instant lastScannedAt,
            String lastScannedByName,
            String qrImageUrl
    ) {
    }

    public record PassSummaryDto(
            int totalIssued,
            int notEnteredCount,
            int checkedInCount,
            int checkedOutCount,
            Instant lastScannedAt
    ) {
    }

    public record OccupancyReadingDto(
            Long id,
            Long zoneId,
            String zoneCode,
            String zoneName,
            Long gateId,
            String gateLabel,
            int countValue,
            OccupancyDataSource dataSource,
            String sourceShortLabel,
            String sourceBadgeClass,
            String recordedBy,
            Instant recordedAt,
            String note
    ) {
    }

    public record PracticeRunOptionDto(
            Long runId,
            String label,
            String scenarioDisplayName,
            int frameCount,
            int durationSeconds,
            int agentCount
    ) {
    }

    public record PracticeStepResultDto(
            Long runId,
            String runLabel,
            int frameIndex,
            int totalFrames,
            double frameTimeSec,
            int mappedAgentsCount,
            int unmappedAgentsCount,
            ControlCentreDto controlCentre
    ) {
    }

    public record HeatmapGeometryDto(
            String workspaceMode,
            boolean hasBlueprint,
            String blueprintUrl,
            Integer blueprintWidthPx,
            Integer blueprintHeightPx,
            Double pixelsPerMeter,
            List<GeometryUtil.Point2D> boundaryPoints,
            GeometryUtil.Bounds bounds,
            List<SimulationDtos.SnapExclusion> exclusions,
            List<GateStatusDto> gates
    ) {
    }

    public record ControlCentreDto(
            Long eventId,
            String eventName,
            String eventStatusLabel,
            Integer expectedAttendance,
            Integer preliminaryCapacity,
            String mode,
            OccupancyDataSource activeSource,
            boolean canEdit,
            boolean canAcknowledge,
            Instant evaluatedAt,
            ChannelSummaryDto activeChannel,
            ChannelSummaryDto manualSummary,
            ChannelSummaryDto passScanSummary,
            ChannelSummaryDto practiceSummary,
            List<ZoneOccupancyDto> zones,
            List<GateStatusDto> gates,
            QueuePressureSummaryDto queuePressure,
            StaffDutySummaryDto staffDuty,
            PreEventSafetySummaryDto preEventSafety,
            List<AlertEventDto> activeAlerts,
            List<AlertEventDto> resolvedAlerts,
            List<AlertRuleDto> alertRules,
            PassSummaryDto passSummary,
            List<AttendeePassDto> recentPasses,
            List<OccupancyReadingDto> recentReadings,
            List<PracticeRunOptionDto> practiceRuns,
            HeatmapGeometryDto heatmapGeometry
    ) {
    }

    public record FieldMonitoringDto(
            Long eventId,
            List<ZoneOccupancyDto> manualZones,
            List<GateStatusDto> gates,
            PassSummaryDto passSummary,
            List<AttendeePassDto> recentPasses,
            List<OccupancyReadingDto> recentManualReadings,
            List<AlertEventDto> liveAlerts
    ) {
    }
}
