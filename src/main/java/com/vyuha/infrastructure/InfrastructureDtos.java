package com.vyuha.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.GridDtos;
import com.vyuha.venue.AreaBreakdownResult;
import com.vyuha.venue.VenueDtos;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Request, snapshot, and workspace DTO contracts for Phase 5 Infrastructure Placement,
 * Command-Pattern Undo/Redo, and Spatial Conflict Detection.
 */
public final class InfrastructureDtos {

    public static final double DEFAULT_EXIT_CLEARANCE_METERS = 3.0;

    private InfrastructureDtos() {
    }

    public record ElementMetadata(
            Double clearanceBufferM,
            Boolean linearAligned,
            String notes
    ) {
        public double effectiveClearanceBufferM() {
            if (clearanceBufferM == null || !Double.isFinite(clearanceBufferM) || clearanceBufferM <= 0.0) {
                return DEFAULT_EXIT_CLEARANCE_METERS;
            }
            return clearanceBufferM;
        }

        public boolean isLinearAligned() {
            return Boolean.TRUE.equals(linearAligned);
        }

        public static ElementMetadata parse(ObjectMapper mapper, String json) {
            if (json == null || json.isBlank()) {
                return new ElementMetadata(DEFAULT_EXIT_CLEARANCE_METERS, false, null);
            }
            try {
                ElementMetadata parsed = mapper.readValue(json, ElementMetadata.class);
                if (parsed == null) {
                    return new ElementMetadata(DEFAULT_EXIT_CLEARANCE_METERS, false, null);
                }
                return parsed;
            } catch (JsonProcessingException ex) {
                return new ElementMetadata(DEFAULT_EXIT_CLEARANCE_METERS, false, null);
            }
        }

        public static String write(ObjectMapper mapper, ElementMetadata metadata) {
            if (metadata == null) {
                return null;
            }
            try {
                return mapper.writeValueAsString(metadata);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Failed to serialize infrastructure metadata.", ex);
            }
        }
    }

    public record CreateElementRequest(
            @NotNull(message = "Infrastructure type is required.")
            InfrastructureType elementType,
            @Size(max = 160, message = "Label must be 160 characters or fewer.")
            String label,
            @NotNull(message = "Center X is required.")
            Double centerX,
            @NotNull(message = "Center Y is required.")
            Double centerY,
            @DecimalMin(value = "0.3", message = "Width must be at least 0.3 m.")
            @DecimalMax(value = "200.0", message = "Width must not exceed 200.0 m.")
            Double widthM,
            @DecimalMin(value = "0.3", message = "Height must be at least 0.3 m.")
            @DecimalMax(value = "200.0", message = "Height must not exceed 200.0 m.")
            Double heightM,
            @DecimalMin(value = "-360.0", message = "Rotation must be between -360 and 360 degrees.")
            @DecimalMax(value = "360.0", message = "Rotation must be between -360 and 360 degrees.")
            Double rotationDeg,
            @DecimalMin(value = "0.0", message = "Throughput must be zero or positive.")
            @DecimalMax(value = "1000.0", message = "Throughput must not exceed 1000 persons/min.")
            Double throughputPersonsPerMin,
            Boolean active,
            @DecimalMin(value = "0.5", message = "Exit clearance apron must be at least 0.5 m.")
            @DecimalMax(value = "20.0", message = "Exit clearance apron must not exceed 20.0 m.")
            Double clearanceBufferM,
            Boolean linearAligned,
            @Size(max = 500, message = "Notes must be 500 characters or fewer.")
            String notes
    ) {
    }

    public record UpdateElementRequest(
            @Size(max = 160, message = "Label must be 160 characters or fewer.")
            String label,
            @NotNull(message = "Center X is required.")
            Double centerX,
            @NotNull(message = "Center Y is required.")
            Double centerY,
            @NotNull(message = "Width is required.")
            @DecimalMin(value = "0.3", message = "Width must be at least 0.3 m.")
            @DecimalMax(value = "200.0", message = "Width must not exceed 200.0 m.")
            Double widthM,
            @NotNull(message = "Height is required.")
            @DecimalMin(value = "0.3", message = "Height must be at least 0.3 m.")
            @DecimalMax(value = "200.0", message = "Height must not exceed 200.0 m.")
            Double heightM,
            @NotNull(message = "Rotation is required.")
            @DecimalMin(value = "-360.0", message = "Rotation must be between -360 and 360 degrees.")
            @DecimalMax(value = "360.0", message = "Rotation must be between -360 and 360 degrees.")
            Double rotationDeg,
            @DecimalMin(value = "0.0", message = "Throughput must be zero or positive.")
            @DecimalMax(value = "1000.0", message = "Throughput must not exceed 1000 persons/min.")
            Double throughputPersonsPerMin,
            Boolean active,
            @DecimalMin(value = "0.5", message = "Exit clearance apron must be at least 0.5 m.")
            @DecimalMax(value = "20.0", message = "Exit clearance apron must not exceed 20.0 m.")
            Double clearanceBufferM,
            Boolean linearAligned,
            @Size(max = 500, message = "Notes must be 500 characters or fewer.")
            String notes,
            @Size(max = 40)
            String commandType
    ) {
    }

    public record ReviewFindingRequest(
            @NotNull(message = "Review status is required.")
            FindingReviewStatus reviewStatus,
            @Size(max = 1000, message = "Reviewer note must be 1000 characters or fewer.")
            String reviewerNote
    ) {
    }

    public record ElementSnapshot(
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
            String metadataJson,
            int sortOrder
    ) {
    }

    public record InfrastructureElementDto(
            Long id,
            String elementUid,
            InfrastructureType elementType,
            String elementTypeName,
            String shortCode,
            String label,
            double centerX,
            double centerY,
            double widthM,
            double heightM,
            double rotationDeg,
            double areaSqm,
            Double throughputPersonsPerMin,
            boolean supportsThroughput,
            boolean exitPortal,
            boolean emergencyLane,
            boolean rigidStructure,
            boolean active,
            double clearanceBufferM,
            boolean linearAligned,
            String notes,
            String fillColor,
            String strokeColor,
            String footprintWkt,
            List<GeometryUtil.Point2D> footprintPoints,
            List<GeometryUtil.Point2D> clearanceApronPoints
    ) {
    }

    public record ConflictFindingDto(
            Long id,
            String findingKey,
            ConflictType conflictType,
            String conflictTypeName,
            FindingSeverity severity,
            String title,
            String description,
            String measuredValue,
            Double locationX,
            Double locationY,
            List<Long> involvedElementIds,
            boolean active,
            FindingReviewStatus reviewStatus,
            String reviewStatusName,
            String reviewerNote,
            String reviewedByName,
            Instant reviewedAt,
            Instant resolvedAt
    ) {
    }

    public record RevisionSummaryDto(
            int revisionNumber,
            String commandType,
            String summary,
            boolean current,
            String createdByName,
            Instant createdAt
    ) {
    }

    public record HistoryStateDto(
            boolean canUndo,
            boolean canRedo,
            int currentRevisionNumber,
            int totalRevisions,
            String undoLabel,
            String redoLabel,
            List<RevisionSummaryDto> revisions
    ) {
    }

    public record InfrastructureSummaryDto(
            int totalElements,
            int activeElements,
            int entryGateCount,
            int exitPortalCount,
            int emergencyLaneCount,
            double totalEntryThroughputPerMin,
            double totalExitThroughputPerMin,
            double totalEmergencyExitThroughputPerMin,
            int criticalCount,
            int highCount,
            int lowCount,
            int infoCount,
            int openFindingsCount,
            int historyFindingsCount
    ) {
    }

    public record InfrastructureWorkspaceDto(
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
            List<InfrastructureElementDto> elements,
            List<ConflictFindingDto> activeFindings,
            List<ConflictFindingDto> resolvedHistoryFindings,
            InfrastructureSummaryDto summary,
            HistoryStateDto history,
            Long lastModifiedElementId
    ) {
    }
}
