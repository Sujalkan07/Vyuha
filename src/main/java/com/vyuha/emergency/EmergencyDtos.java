package com.vyuha.emergency;

import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.GridDtos;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.network.NetworkDtos;
import com.vyuha.venue.AreaBreakdownResult;
import com.vyuha.venue.VenueDtos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request and response DTO contracts for Phase 8 Emergency Preparedness Workspace
 * and Failure-Mode Outage Analysis.
 */
public final class EmergencyDtos {

    private EmergencyDtos() {
    }

    public record EmergencyAssetRequest(
            @NotNull(message = "Emergency asset type is required.")
            EmergencyAssetType assetType,
            @NotBlank(message = "Asset label is required.")
            @Size(max = 160, message = "Label must be 160 characters or fewer.")
            String label,
            String geometryWkt,
            List<GeometryUtil.Point2D> points,
            Double x,
            Double y,
            Double minWidthM,
            @Size(max = 500, message = "Capacity notes must be 500 characters or fewer.")
            String capacityNotes,
            @Size(max = 500, message = "Notes must be 500 characters or fewer.")
            String notes
    ) {
    }

    public record EmergencyContactRequest(
            @NotBlank(message = "Agency name is required.")
            @Size(max = 160, message = "Agency name must be 160 characters or fewer.")
            String agencyName,
            @NotBlank(message = "Contact name is required.")
            @Size(max = 150, message = "Contact name must be 150 characters or fewer.")
            String contactName,
            @NotBlank(message = "Phone number is required.")
            @Size(max = 50, message = "Phone number must be 50 characters or fewer.")
            String phone,
            @NotBlank(message = "Role is required.")
            @Size(max = 120, message = "Role must be 120 characters or fewer.")
            String role,
            Integer priorityOrder,
            @Size(max = 500, message = "Notes must be 500 characters or fewer.")
            String notes
    ) {
    }

    public record OutageTestRequest(
            List<Long> disabledExitInfraIds,
            List<Long> disabledNodeIds,
            List<Long> disabledEdgeIds,
            List<Long> disabledAssetIds,
            Boolean emergencyExitsOnly
    ) {
    }

    public record EmergencyAssetDto(
            Long id,
            EmergencyAssetType assetType,
            String assetTypeName,
            String shortCode,
            String fillColor,
            String strokeColor,
            boolean vehicleRoute,
            String label,
            String geometryWkt,
            String geometryKind,
            List<GeometryUtil.Point2D> points,
            List<GeometryUtil.PolygonShape> shapes,
            GeometryUtil.Point2D anchorPoint,
            Double minWidthM,
            Double lengthM,
            Double areaSqm,
            String capacityNotes,
            String notes
    ) {
    }

    public record EmergencyContactDto(
            Long id,
            String agencyName,
            String contactName,
            String phone,
            String role,
            int priorityOrder,
            String notes
    ) {
    }

    public record VehicleRouteStatusDto(
            String routeKey,
            String sourceType,
            Long sourceId,
            String label,
            String routeRole,
            Double clearWidthM,
            double minRequiredWidthM,
            boolean widthCompliant,
            int obstacleCount,
            List<String> obstacleLabels,
            boolean compliant,
            Double locateX,
            Double locateY
    ) {
    }

    public record EmergencyValidationFinding(
            String code,
            FindingSeverity severity,
            String title,
            String description,
            String measuredValue,
            String thresholdNote,
            Double locateX,
            Double locateY,
            String sourceType,
            Long sourceId
    ) {
    }

    public record EmergencyValidationSummary(
            int totalVehicleRoutes,
            int compliantVehicleRoutes,
            int narrowRouteCount,
            int obstructedRouteCount,
            int emergencyExitCount,
            int assemblyPointCount,
            int contactCount,
            boolean hasCriticalWarnings,
            List<VehicleRouteStatusDto> routeStatuses,
            List<EmergencyValidationFinding> findings
    ) {
    }

    public enum ZoneOutageStatus {
        UNAFFECTED("Unaffected", "badge-verified"),
        REROUTED("Rerouted", "badge-low"),
        NEWLY_ISOLATED("Newly Isolated", "badge-critical"),
        ALREADY_ISOLATED("Already Isolated", "badge-high");

        private final String displayName;
        private final String badgeClass;

        ZoneOutageStatus(String displayName, String badgeClass) {
            this.displayName = displayName;
            this.badgeClass = badgeClass;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getBadgeClass() {
            return badgeClass;
        }
    }

    public record ExitOutageStatusDto(
            String portalKey,
            String sourceType,
            Long infraId,
            Long nodeId,
            Long assetId,
            String label,
            boolean dedicatedEmergencyExit,
            double egressWidthM,
            Double throughputPerMin,
            boolean directlyDisabled,
            boolean cutOffByCorridorOutage,
            boolean operational,
            Double x,
            Double y
    ) {
    }

    public record ZoneOutageImpactDto(
            Long zoneId,
            String zoneCode,
            String zoneName,
            Long hubNodeId,
            String hubNodeLabel,
            ZoneOutageStatus status,
            boolean lostDedicatedEmergencyExit,
            String baselineExitLabel,
            Double baselineDistanceM,
            String backupExitLabel,
            Double backupDistanceM,
            Double detourDistanceM,
            Double detourPct,
            Double backupMinWidthM,
            boolean backupNarrowWarning,
            List<Long> backupNodeIds,
            List<Long> backupEdgeIds,
            List<GeometryUtil.Point2D> backupPathPoints,
            Double locateX,
            Double locateY
    ) {
    }

    public record OutageAnalysisResult(
            boolean emergencyExitsOnly,
            List<Long> disabledExitInfraIds,
            List<Long> disabledNodeIds,
            List<Long> disabledEdgeIds,
            List<Long> disabledAssetIds,
            int totalEvaluatedZones,
            int unaffectedZoneCount,
            int reroutedZoneCount,
            int newlyIsolatedZoneCount,
            int alreadyIsolatedZoneCount,
            double baselineTotalEgressWidthM,
            double remainingEgressWidthM,
            double egressWidthLossM,
            double egressWidthLossPct,
            double remainingEgressCapacityPct,
            double baselineThroughputPerMin,
            double remainingThroughputPerMin,
            double throughputLossPct,
            double remainingThroughputPct,
            int activeExitCount,
            int operationalExitCount,
            int cutOffExitCount,
            List<ExitOutageStatusDto> exitStatuses,
            List<ZoneOutageImpactDto> zoneImpacts
    ) {
    }

    public record EmergencyWorkspaceDto(
            Long eventId,
            String eventName,
            Integer expectedAttendance,
            boolean canEdit,
            VenueDtos.VenuePlanDto venuePlan,
            List<VenueDtos.ExclusionAreaDto> exclusions,
            AreaBreakdownResult areaBreakdown,
            Object vectorMapGeoJson,
            List<GridDtos.FunctionalZoneDto> zones,
            List<InfrastructureDtos.InfrastructureElementDto> elements,
            List<NetworkDtos.PathwayNodeDto> nodes,
            List<NetworkDtos.PathwayEdgeDto> edges,
            List<EmergencyAssetDto> assets,
            List<EmergencyContactDto> contacts,
            EmergencyValidationSummary validation,
            OutageAnalysisResult baselineOutage
    ) {
    }
}
