package com.vyuha.network;

import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.GridDtos;
import com.vyuha.infrastructure.FindingReviewStatus;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.venue.AreaBreakdownResult;
import com.vyuha.venue.VenueDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Request, synchronization, and workspace DTO contracts for Phase 6 Pedestrian Movement Network
 * and Automated Bottleneck Screening.
 */
public final class NetworkDtos {

    public static final double MIN_EDGE_WIDTH_M = 0.3;
    public static final double MAX_EDGE_WIDTH_M = 100.0;

    private NetworkDtos() {
    }

    public record CreateNodeRequest(
            @NotNull(message = "Node type is required.")
            PathwayNodeType nodeType,
            @Size(max = 160, message = "Label must be 160 characters or fewer.")
            String label,
            @NotNull(message = "X coordinate is required.")
            Double x,
            @NotNull(message = "Y coordinate is required.")
            Double y,
            Long linkedZoneId,
            Long linkedInfraId,
            @Size(max = 500, message = "Notes must be 500 characters or fewer.")
            String notes
    ) {
    }

    public record UpdateNodeRequest(
            PathwayNodeType nodeType,
            @Size(max = 160, message = "Label must be 160 characters or fewer.")
            String label,
            @NotNull(message = "X coordinate is required.")
            Double x,
            @NotNull(message = "Y coordinate is required.")
            Double y,
            Long linkedZoneId,
            Long linkedInfraId,
            Boolean updateLinks,
            @Size(max = 500, message = "Notes must be 500 characters or fewer.")
            String notes
    ) {
    }

    public record CreateEdgeRequest(
            @NotNull(message = "Start node is required.")
            Long fromNodeId,
            @NotNull(message = "End node is required.")
            Long toNodeId,
            @Size(max = 160, message = "Label must be 160 characters or fewer.")
            String label,
            @NotNull(message = "Network layer is required.")
            NetworkLayer networkLayer,
            @DecimalMin(value = "0.3", message = "Width must be at least 0.3 m.")
            @DecimalMax(value = "100.0", message = "Width must not exceed 100.0 m.")
            Double widthM,
            PathwayDirection direction,
            List<GeometryUtil.Point2D> waypoints,
            Boolean active,
            @Size(max = 500, message = "Notes must be 500 characters or fewer.")
            String notes
    ) {
    }

    public record UpdateEdgeRequest(
            @Size(max = 160, message = "Label must be 160 characters or fewer.")
            String label,
            NetworkLayer networkLayer,
            @DecimalMin(value = "0.3", message = "Width must be at least 0.3 m.")
            @DecimalMax(value = "100.0", message = "Width must not exceed 100.0 m.")
            Double widthM,
            PathwayDirection direction,
            List<GeometryUtil.Point2D> waypoints,
            Boolean active,
            Boolean reverseDirection,
            @Size(max = 500, message = "Notes must be 500 characters or fewer.")
            String notes
    ) {
    }

    public record ReviewBottleneckRequest(
            @NotNull(message = "Review status is required.")
            FindingReviewStatus reviewStatus,
            @Size(max = 1000, message = "Reviewer note must be 1000 characters or fewer.")
            String reviewerNote
    ) {
    }

    public record NodeSyncItem(
            @NotBlank(message = "Node UID is required.")
            String nodeUid,
            @NotNull(message = "Node type is required.")
            PathwayNodeType nodeType,
            @Size(max = 160)
            String label,
            @NotNull(message = "Node X is required.")
            Double x,
            @NotNull(message = "Node Y is required.")
            Double y,
            Long linkedZoneId,
            Long linkedInfraId,
            @Size(max = 500)
            String notes,
            Integer sortOrder
    ) {
    }

    public record EdgeSyncItem(
            @NotBlank(message = "Edge UID is required.")
            String edgeUid,
            @NotBlank(message = "From node UID is required.")
            String fromNodeUid,
            @NotBlank(message = "To node UID is required.")
            String toNodeUid,
            @Size(max = 160)
            String label,
            @NotNull(message = "Network layer is required.")
            NetworkLayer networkLayer,
            @NotNull(message = "Width is required.")
            @DecimalMin(value = "0.3")
            @DecimalMax(value = "100.0")
            Double widthM,
            PathwayDirection direction,
            List<GeometryUtil.Point2D> points,
            Boolean active,
            @Size(max = 500)
            String notes,
            Integer sortOrder
    ) {
    }

    public record LayoutSyncRequest(
            @Valid List<NodeSyncItem> nodes,
            @Valid List<EdgeSyncItem> edges
    ) {
    }

    public record PathwayNodeDto(
            Long id,
            String nodeUid,
            PathwayNodeType nodeType,
            String nodeTypeName,
            String shortCode,
            String label,
            double x,
            double y,
            Long linkedZoneId,
            String linkedZoneCode,
            String linkedZoneName,
            Long linkedInfraId,
            String linkedInfraLabel,
            String notes,
            String fillColor,
            String strokeColor
    ) {
    }

    public record PathwayEdgeDto(
            Long id,
            String edgeUid,
            Long fromNodeId,
            Long toNodeId,
            String fromNodeUid,
            String toNodeUid,
            String fromNodeLabel,
            String toNodeLabel,
            String label,
            NetworkLayer networkLayer,
            String networkLayerName,
            String networkLayerShort,
            double widthM,
            double lengthM,
            PathwayDirection direction,
            String directionName,
            boolean active,
            String notes,
            String colorHex,
            boolean dashed,
            String polylineWkt,
            List<GeometryUtil.Point2D> points,
            GeometryUtil.Point2D midpoint
    ) {
    }

    public record BottleneckFindingDto(
            Long id,
            String findingKey,
            BottleneckFindingType findingType,
            String findingTypeName,
            FindingSeverity severity,
            String title,
            String description,
            String measuredValue,
            String thresholdNote,
            Double locationX,
            Double locationY,
            List<Long> involvedNodeIds,
            List<Long> involvedEdgeIds,
            boolean active,
            FindingReviewStatus reviewStatus,
            String reviewStatusName,
            String reviewerNote,
            String reviewedByName,
            Instant reviewedAt,
            Instant resolvedAt
    ) {
    }

    public record NetworkSummaryDto(
            int totalNodes,
            int totalEdges,
            int activeEdges,
            int entryLayerEdges,
            int circulationLayerEdges,
            int exitLayerEdges,
            int emergencyLayerEdges,
            double totalLengthM,
            int connectedComponents,
            double connectedEntryCapacityPerMin,
            double estimatedArrivalDemandPerMin,
            int effectiveIngressWindowMin,
            int criticalCount,
            int highCount,
            int lowCount,
            int infoCount,
            int openFindingsCount,
            int historyFindingsCount
    ) {
    }

    public record NetworkWorkspaceDto(
            Long eventId,
            String eventName,
            Integer expectedAttendance,
            boolean canEdit,
            boolean canReviewFindings,
            VenueDtos.VenuePlanDto venuePlan,
            List<VenueDtos.ExclusionAreaDto> exclusions,
            AreaBreakdownResult areaBreakdown,
            Object vectorMapGeoJson,
            List<GridDtos.FunctionalZoneDto> zones,
            List<InfrastructureDtos.InfrastructureElementDto> elements,
            List<PathwayNodeDto> nodes,
            List<PathwayEdgeDto> edges,
            List<BottleneckFindingDto> activeFindings,
            List<BottleneckFindingDto> resolvedHistoryFindings,
            NetworkSummaryDto summary,
            Long lastModifiedNodeId,
            Long lastModifiedEdgeId
    ) {
    }
}
