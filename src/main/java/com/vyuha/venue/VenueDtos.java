package com.vyuha.venue;

import com.vyuha.common.GeometryUtil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request and response DTO contracts for Phase 3 Venue Plan endpoints and workspace rendering.
 */
public final class VenueDtos {

    private VenueDtos() {
    }

    public record ModeUpdateRequest(
            @NotNull WorkspaceMode workspaceMode,
            String sampleMapKey,
            Double gridSnapMeters
    ) {
    }

    public record CalibrationRequest(
            @NotNull Double primaryX1Px,
            @NotNull Double primaryY1Px,
            @NotNull Double primaryX2Px,
            @NotNull Double primaryY2Px,
            @NotNull @Positive(message = "Known primary distance must be greater than zero.")
            Double primaryRealDistanceMeters,
            Double verifyX1Px,
            Double verifyY1Px,
            Double verifyX2Px,
            Double verifyY2Px,
            Double verifyRealDistanceMeters,
            boolean orthographicConfirmed,
            Boolean rescaleExistingGeometry
    ) {
    }

    public record BoundaryUpdateRequest(
            List<GeometryUtil.Point2D> points,
            String polygonWkt,
            String coordinateSpace,
            boolean clear
    ) {
    }

    public record ExclusionRequest(
            @NotBlank(message = "Exclusion label is required.")
            @Size(max = 160)
            String label,
            @NotNull(message = "Exclusion category is required.")
            ExclusionType exclusionType,
            @Size(max = 500)
            String notes,
            List<GeometryUtil.Point2D> points,
            String polygonWkt,
            String coordinateSpace
    ) {
    }

    public record ExclusionSyncItem(
            String label,
            ExclusionType exclusionType,
            String notes,
            String polygonWkt
    ) {
    }

    public record LayoutSyncRequest(
            String boundaryWkt,
            List<ExclusionSyncItem> exclusions
    ) {
    }

    public record SampleMapSummary(
            String key,
            String name,
            String description,
            double widthMeters,
            double heightMeters
    ) {
    }

    public record VenueCheckFinding(
            String code,
            String severity,
            String title,
            String detail,
            String measuredValue,
            Double locateX,
            Double locateY
    ) {
    }

    public record ExclusionAreaDto(
            Long id,
            String label,
            ExclusionType exclusionType,
            String exclusionTypeName,
            boolean corridor,
            String fillColor,
            String strokeColor,
            String polygonWkt,
            List<GeometryUtil.Point2D> points,
            String notes,
            double rawAreaSqm,
            double clippedAreaSqm,
            double outsideBoundarySqm,
            GeometryUtil.Point2D centroid
    ) {
    }

    public record VenuePlanDto(
            Long id,
            Long eventId,
            WorkspaceMode workspaceMode,
            String sampleMapKey,
            String customVectorFilename,
            boolean hasBlueprint,
            String blueprintUrl,
            String blueprintOriginalFilename,
            Integer blueprintWidthPx,
            Integer blueprintHeightPx,
            String boundaryWkt,
            List<GeometryUtil.Point2D> boundaryPoints,
            Double pixelsPerMeter,
            boolean scaleVerified,
            boolean distortionWarning,
            boolean orthographicConfirmed,
            double gridSnapMeters,
            CalibrationData calibration
    ) {
    }

    public record VenueWorkspaceDto(
            Long eventId,
            String eventName,
            Integer expectedAttendance,
            boolean canEdit,
            VenuePlanDto venuePlan,
            List<ExclusionAreaDto> exclusions,
            AreaBreakdownResult areaBreakdown,
            List<VenueCheckFinding> findings,
            Object vectorMapGeoJson
    ) {
    }
}
