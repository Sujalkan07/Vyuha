package com.vyuha.grid;

import com.vyuha.common.GeometryUtil;
import com.vyuha.venue.AreaBreakdownResult;
import com.vyuha.venue.VenueDtos;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request and response DTO contracts for Phase 4 Grid & Zone Layout Designer endpoints.
 */
public final class GridDtos {

    private GridDtos() {
    }

    public record GridConfigRequest(
            @NotNull(message = "Cell size is required.")
            @DecimalMin(value = "2.0", message = "Grid cell size must be at least 2.0 m.")
            @DecimalMax(value = "20.0", message = "Grid cell size must not exceed 20.0 m.")
            Double cellSizeMeters,
            Double originX,
            Double originY,
            @DecimalMin(value = "-180.0", message = "Rotation must be between -180 and 180 degrees.")
            @DecimalMax(value = "180.0", message = "Rotation must be between -180 and 180 degrees.")
            Double rotationDeg
    ) {
    }

    public record GridConfigDto(
            Long id,
            double cellSizeMeters,
            double originX,
            double originY,
            double rotationDeg,
            int totalCells,
            int partialCells,
            double totalClippedAreaSqm,
            double totalUsableAreaSqm
    ) {
    }

    public record GridCellDto(
            String cellRef,
            int col,
            int row,
            double clippedAreaSqm,
            double usableAreaSqm,
            boolean partial,
            GeometryUtil.Point2D center,
            List<GeometryUtil.PolygonShape> shapes
    ) {
    }

    public record GeneratedGridResult(
            List<GridCellDto> cells,
            int totalCells,
            int partialCells,
            double totalClippedAreaSqm,
            double totalUsableAreaSqm
    ) {
    }

    public record ZoneCreateRequest(
            @Size(max = 32, message = "Zone code must be 32 characters or fewer.")
            String zoneCode,
            @NotBlank(message = "Zone name is required.")
            @Size(max = 160)
            String name,
            @NotNull(message = "Zone category is required.")
            ZoneType zoneType,
            @Size(max = 500)
            String notes,
            List<String> gridCellRefs,
            List<GeometryUtil.Point2D> points,
            String polygonWkt
    ) {
    }

    public record ZoneUpdateRequest(
            @Size(max = 32, message = "Zone code must be 32 characters or fewer.")
            String zoneCode,
            @NotBlank(message = "Zone name is required.")
            @Size(max = 160)
            String name,
            @NotNull(message = "Zone category is required.")
            ZoneType zoneType,
            @Size(max = 500)
            String notes,
            List<GeometryUtil.Point2D> points,
            String polygonWkt
    ) {
    }

    public record ZoneCellsMutationRequest(
            @NotBlank(message = "Action is required (ADD or REMOVE).")
            String action,
            @NotEmpty(message = "At least one grid cell reference is required.")
            List<String> gridCellRefs
    ) {
    }

    public record ZoneSyncItem(
            Long id,
            String zoneCode,
            String name,
            ZoneType zoneType,
            String notes,
            String polygonWkt,
            List<String> gridCellRefs
    ) {
    }

    public record ZoneLayoutSyncRequest(
            GridConfigRequest gridConfig,
            List<ZoneSyncItem> zones
    ) {
    }

    public record FunctionalZoneDto(
            Long id,
            String zoneCode,
            String name,
            ZoneType zoneType,
            String zoneTypeName,
            ZoneType.CapacityBucket capacityBucket,
            boolean walkable,
            boolean hatched,
            String fillColor,
            String strokeColor,
            String polygonWkt,
            List<GeometryUtil.PolygonShape> shapes,
            List<String> gridCellRefs,
            double rawAreaSqm,
            double clippedAreaSqm,
            double usableAreaSqm,
            boolean isolated,
            GeometryUtil.Point2D labelPoint,
            String notes
    ) {
    }

    public record ZoneFindingDto(
            String code,
            String severity,
            String title,
            String detail,
            String measuredValue,
            Double locateX,
            Double locateY,
            List<Long> involvedZoneIds
    ) {
    }

    public record ZoneCoverageSummary(
            boolean hasBoundary,
            double grossAreaSqm,
            double totalUsableAreaSqm,
            double assignedUsableAreaSqm,
            double unassignedUsableAreaSqm,
            double assignedPercent,
            double overlapAreaSqm,
            int zoneCount,
            int isolatedZoneCount
    ) {
    }

    public record ZoneValidationResult(
            ZoneCoverageSummary coverage,
            List<ZoneFindingDto> findings,
            List<Long> isolatedZoneIds,
            List<Double> perZoneClippedAreasSqm,
            List<Double> perZoneUsableAreasSqm
    ) {
    }

    public record GridWorkspaceDto(
            Long eventId,
            String eventName,
            Integer expectedAttendance,
            boolean canEdit,
            VenueDtos.VenuePlanDto venuePlan,
            List<VenueDtos.ExclusionAreaDto> exclusions,
            AreaBreakdownResult areaBreakdown,
            Object vectorMapGeoJson,
            GridConfigDto gridConfig,
            List<GridCellDto> gridCells,
            List<FunctionalZoneDto> zones,
            ZoneCoverageSummary coverage,
            List<ZoneFindingDto> findings
    ) {
    }
}
