package com.vyuha.capacity;

import com.vyuha.grid.ZoneType;
import com.vyuha.venue.AreaBreakdownResult;
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
 * Request, calculation, and Thymeleaf view DTOs for Phase 4 Preliminary Capacity Review.
 */
public final class CapacityDtos {

    private CapacityDtos() {
    }

    public record CapacityParametersRequest(
            @Min(value = 1, message = "Official venue limit must be at least 1 person.")
            Integer officialVenueLimit,
            @Size(max = 255)
            String officialLimitSource,
            @DecimalMin(value = "0.1", message = "Entry throughput must be positive.")
            Double entryThroughputPerMin,
            @Min(value = 1, message = "Ingress window must be at least 1 minute.")
            @Max(value = 1440, message = "Ingress window must not exceed 1440 minutes.")
            Integer ingressWindowMinutes,
            @DecimalMin(value = "0.1", message = "Exit throughput must be positive.")
            Double exitThroughputPerMin,
            @Min(value = 1, message = "Egress window must be at least 1 minute.")
            @Max(value = 1440, message = "Egress window must not exceed 1440 minutes.")
            Integer egressWindowMinutes,
            @Min(value = 0, message = "Holding buffer capacity cannot be negative.")
            Integer holdingBufferCapacity,
            @Size(max = 2000)
            String notes
    ) {
    }

    public record ZoneAssumptionRequest(
            @NotNull(message = "Density assumption (persons/m²) is required.")
            @DecimalMin(value = "0.05", message = "Density must be at least 0.05 persons/m².")
            @DecimalMax(value = "10.0", message = "Density cannot exceed 10.0 persons/m².")
            Double densityPersonsPerSqm,
            @NotBlank(message = "Source citation is required for every density assumption.")
            @Size(max = 300)
            String sourceCitation,
            @Size(max = 150)
            String approvedByName
    ) {
    }

    public record ReviewerAssumptionAction(
            @NotNull(message = "Review action status is required.")
            AssumptionReviewStatus reviewStatus,
            @Size(max = 1000)
            String reviewerNote
    ) {
    }

    public record ReviewerOverallAction(
            @NotNull(message = "Review status is required.")
            CapacityReviewStatus reviewStatus,
            @Size(max = 2000)
            String reviewerNotes
    ) {
    }

    public record ZoneCapacityRow(
            Long zoneId,
            String zoneCode,
            String name,
            ZoneType zoneType,
            String zoneTypeName,
            ZoneType.CapacityBucket capacityBucket,
            boolean capacityBearing,
            String fillColor,
            double grossZoneAreaSqm,
            double usableZoneAreaSqm,
            boolean isolated,
            Double densityPersonsPerSqm,
            Integer computedCapacity,
            String sourceCitation,
            String approvedByName,
            Instant approvedAt,
            String verifiedByName,
            AssumptionReviewStatus reviewStatus,
            String reviewerNote
    ) {
    }

    public record CapacityWarning(
            String code,
            String severity,
            String title,
            String detail
    ) {
    }

    public record CapacityCalculationResult(
            boolean hasBoundary,
            double grossAreaSqm,
            double usableGatheringTotalSqm,
            Integer expectedAttendance,
            Integer usableSpaceCapacity,
            Integer entryFlowCapacity,
            Integer exitFlowCapacity,
            Integer flowCapacity,
            Integer holdingZoneAreaCapacity,
            int holdingBufferCapacity,
            Integer holdingSpaceCapacity,
            Integer officialVenueLimit,
            Integer preliminaryCapacity,
            GoverningConstraint governingConstraint,
            List<GoverningConstraint> tiedConstraints,
            String governingExplanation,
            Integer attendanceHeadroom,
            Double utilizationPct,
            boolean exceedsPreliminaryCeiling,
            int totalCapacityZones,
            int documentedCapacityZones,
            int verifiedCapacityZones,
            int flaggedCapacityZones,
            CapacityReviewStatus effectiveReviewStatus,
            List<ZoneCapacityRow> zoneRows,
            List<CapacityWarning> warnings
    ) {
        public int barWidthPct(Integer value) {
            if (value == null || value <= 0) {
                return 0;
            }
            int max = 1;
            if (usableSpaceCapacity != null && usableSpaceCapacity > max) max = usableSpaceCapacity;
            if (flowCapacity != null && flowCapacity > max) max = flowCapacity;
            if (holdingSpaceCapacity != null && holdingSpaceCapacity > max) max = holdingSpaceCapacity;
            if (officialVenueLimit != null && officialVenueLimit > max) max = officialVenueLimit;
            if (expectedAttendance != null && expectedAttendance > max) max = expectedAttendance;
            return (int) Math.min(100L, Math.max(4L, Math.round((value * 100.0) / max)));
        }
    }

    public record CapacityPageView(
            Long eventId,
            String eventName,
            Integer expectedAttendance,
            boolean canEditAssumptions,
            boolean canVerifyReview,
            AreaBreakdownResult areaBreakdown,
            CapacityParametersView parameters,
            CapacityCalculationResult calculation
    ) {
    }

    public record CapacityParametersView(
            Integer officialVenueLimit,
            String officialLimitSource,
            Double entryThroughputPerMin,
            Integer ingressWindowMinutes,
            Double exitThroughputPerMin,
            Integer egressWindowMinutes,
            int holdingBufferCapacity,
            String notes,
            String reviewerNotes,
            String verifiedByName,
            Instant verifiedAt,
            CapacityReviewStatus persistedReviewStatus
    ) {
    }
}
