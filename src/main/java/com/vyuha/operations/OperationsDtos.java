package com.vyuha.operations;

import com.vyuha.infrastructure.FindingSeverity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Request, projection, coverage analysis, and Thymeleaf view DTOs for Phase 7 Operations.
 */
public final class OperationsDtos {

    private OperationsDtos() {
    }

    public record ArrivalWaveRequest(
            @Size(max = 120, message = "Interval label must not exceed 120 characters.")
            String label,
            @NotNull(message = "Start minute is required.")
            @Min(value = 0, message = "Start minute cannot be negative.")
            Integer startMinute,
            @NotNull(message = "End minute is required.")
            @Min(value = 1, message = "End minute must be at least 1.")
            Integer endMinute,
            @NotNull(message = "Expected arrivals count is required.")
            @Min(value = 0, message = "Expected arrivals cannot be negative.")
            Integer expectedArrivals,
            Long targetGateId,
            @Size(max = 500, message = "Notes must not exceed 500 characters.")
            String notes
    ) {
    }

    public record GenerateWavesRequest(
            @NotNull(message = "Bucket duration is required.")
            @Min(value = 5, message = "Bucket duration must be at least 5 minutes.")
            @Max(value = 240, message = "Bucket duration must not exceed 240 minutes.")
            Integer bucketMinutes,
            @NotNull(message = "Total arrival window duration is required.")
            @Min(value = 10, message = "Total duration must be at least 10 minutes.")
            @Max(value = 1440, message = "Total duration must not exceed 1440 minutes.")
            Integer totalDurationMinutes,
            @Min(value = 0, message = "Total arrivals cannot be negative.")
            Integer totalArrivals,
            Long targetGateId,
            Boolean replaceExisting
    ) {
    }

    public record StaffMemberRequest(
            @NotBlank(message = "Staff member name is required.")
            @Size(max = 150, message = "Name must not exceed 150 characters.")
            String name,
            @NotNull(message = "Role type is required.")
            StaffRoleType roleType,
            @Size(max = 40, message = "Contact phone must not exceed 40 characters.")
            String contactPhone,
            @Size(max = 500, message = "Certifications must not exceed 500 characters.")
            String certifications,
            @Size(max = 500, message = "Notes must not exceed 500 characters.")
            String notes,
            Long userId
    ) {
    }

    public record StaffAssignmentRequest(
            @NotNull(message = "Staff member selection is required.")
            Long staffId,
            Long zoneId,
            Long infraId,
            @Size(max = 160, message = "Post label must not exceed 160 characters.")
            String postLabel,
            @NotNull(message = "Shift start is required.")
            LocalDateTime shiftStart,
            @NotNull(message = "Shift end is required.")
            LocalDateTime shiftEnd,
            @Size(max = 500, message = "Notes must not exceed 500 characters.")
            String notes
    ) {
    }

    public record StaffingTargetsRequest(
            @NotNull(message = "Minimum security per active gate is required.")
            @Min(value = 0, message = "Target cannot be negative.")
            @Max(value = 50, message = "Target must not exceed 50.")
            Integer minSecurityPerActiveGate,
            @NotNull(message = "Minimum staff per public zone is required.")
            @Min(value = 0, message = "Target cannot be negative.")
            @Max(value = 50, message = "Target must not exceed 50.")
            Integer minStaffPerPublicZone,
            @NotNull(message = "Minimum medical staff total is required.")
            @Min(value = 0, message = "Target cannot be negative.")
            @Max(value = 100, message = "Target must not exceed 100.")
            Integer minMedicalStaffTotal,
            @NotNull(message = "Minimum supervisors total is required.")
            @Min(value = 0, message = "Target cannot be negative.")
            @Max(value = 50, message = "Target must not exceed 50.")
            Integer minSupervisorsTotal,
            @NotNull(message = "Attendees-per-staff ratio is required.")
            @Min(value = 0, message = "Ratio cannot be negative.")
            @Max(value = 5000, message = "Ratio must not exceed 5000.")
            Integer attendeesPerStaffRatio,
            @NotNull(message = "Shift window granularity is required.")
            @Min(value = 15, message = "Shift window must be at least 15 minutes.")
            @Max(value = 240, message = "Shift window must not exceed 240 minutes.")
            Integer shiftWindowMinutes,
            @Size(max = 500, message = "Notes must not exceed 500 characters.")
            String notes
    ) {
    }

    public record CreateIncidentRequest(
            @NotNull(message = "Incident severity is required.")
            FindingSeverity severity,
            Long zoneId,
            Long assignedStaffId,
            @NotBlank(message = "Incident description is required.")
            @Size(max = 1500, message = "Description must not exceed 1500 characters.")
            String description,
            @Size(max = 1500, message = "Response taken must not exceed 1500 characters.")
            String responseTaken
    ) {
    }

    public record UpdateIncidentRequest(
            @NotNull(message = "Incident status is required.")
            IncidentStatus status,
            FindingSeverity severity,
            Long assignedStaffId,
            @Size(max = 1500, message = "Response taken must not exceed 1500 characters.")
            String responseTaken
    ) {
    }

    public record EntryGateRateDto(
            Long id,
            String label,
            double throughputPersonsPerMin,
            boolean active
    ) {
    }

    public record QueueIntervalRowDto(
            Long waveId,
            String label,
            int startMinute,
            int endMinute,
            int durationMinutes,
            String minuteWindowLabel,
            String clockWindowLabel,
            Long targetGateId,
            String targetGateLabel,
            int expectedArrivals,
            double effectiveRatePerMin,
            int gateCapacity,
            int openingQueue,
            int processedCount,
            int endingQueue,
            int peakQueueInInterval,
            Double estimatedWaitMinutes,
            boolean unboundedWait,
            boolean overflow,
            int overflowPersons,
            Double holdingDeficitSqm,
            int queueBarPct,
            int arrivalsBarPct,
            int capacityBarPct,
            String notes
    ) {
    }

    public record QueueWarningDto(
            String code,
            String severity,
            String title,
            String detail
    ) {
    }

    public record QueueProjectionResult(
            int totalExpectedArrivals,
            Integer eventExpectedAttendance,
            double totalActiveEntryRatePerMin,
            String rateSourceNote,
            int activeEntryGateCount,
            double holdingZoneAreaSqm,
            Integer holdingZoneAreaCapacity,
            int holdingBufferCapacity,
            int effectiveHoldingCapacity,
            Double effectiveHoldingDensityPerSqm,
            int peakQueueLength,
            String peakQueueWindowLabel,
            Double peakWaitMinutes,
            boolean hasUnboundedWait,
            int overflowIntervalCount,
            int residualUnservedQueue,
            Double maxHoldingDeficitSqm,
            int chartMaxScale,
            int holdingThresholdLinePct,
            List<EntryGateRateDto> entryGates,
            List<QueueIntervalRowDto> intervals,
            List<QueueWarningDto> warnings
    ) {
    }

    public record StaffMemberDto(
            Long id,
            String name,
            StaffRoleType roleType,
            String roleDisplayName,
            String roleBadgeClass,
            String contactPhone,
            String certifications,
            String notes,
            Long linkedUserId,
            String linkedUserEmail,
            String linkedUserName,
            int assignmentCount
    ) {
    }

    public record StaffAssignmentDto(
            Long id,
            Long staffId,
            String staffName,
            StaffRoleType roleType,
            String roleDisplayName,
            String roleBadgeClass,
            Long zoneId,
            String zoneCode,
            String zoneName,
            Long infraId,
            String infraLabel,
            String postLabel,
            String locationSummary,
            LocalDateTime shiftStart,
            LocalDateTime shiftEnd,
            String shiftWindowLabel,
            long durationMinutes,
            String notes
    ) {
    }

    public record StaffingTargetsDto(
            int minSecurityPerActiveGate,
            int minStaffPerPublicZone,
            int minMedicalStaffTotal,
            int minSupervisorsTotal,
            int attendeesPerStaffRatio,
            int shiftWindowMinutes,
            String notes
    ) {
    }

    public record StaffCoverageFindingDto(
            String code,
            FindingSeverity severity,
            String severityBadgeClass,
            String title,
            String detail,
            String measuredValue,
            String targetNote
    ) {
    }

    public record StaffCoverageResult(
            int totalStaffCount,
            int securityCount,
            int volunteerCount,
            int medicalCount,
            int supervisorCount,
            int assignmentCount,
            int uniqueAssignedStaffCount,
            int requiredAttendeeRatioStaff,
            int criticalFindingCount,
            int highFindingCount,
            int totalFindingCount,
            List<StaffCoverageFindingDto> findings
    ) {
    }

    public record IncidentLogDto(
            Long id,
            Long zoneId,
            String zoneCode,
            String zoneName,
            Long assignedStaffId,
            String assignedStaffName,
            StaffRoleType assignedStaffRole,
            String reportedByName,
            Instant reportedAt,
            FindingSeverity severity,
            String severityBadgeClass,
            String description,
            String responseTaken,
            IncidentStatus status,
            String statusDisplayName,
            String statusBadgeClass,
            Instant resolvedAt
    ) {
    }

    public record OptionItemDto(
            Long id,
            String label
    ) {
    }

    public record OperationsWorkspaceDto(
            Long eventId,
            String eventName,
            Integer expectedAttendance,
            LocalDateTime eventStart,
            LocalDateTime eventEnd,
            boolean canEdit,
            boolean canVerify,
            QueueProjectionResult queueProjection,
            List<StaffMemberDto> staffMembers,
            List<StaffAssignmentDto> assignments,
            StaffingTargetsDto staffingTargets,
            StaffCoverageResult staffCoverage,
            List<IncidentLogDto> incidents,
            List<OptionItemDto> entryGateOptions,
            List<OptionItemDto> zoneOptions,
            List<OptionItemDto> infraOptions,
            List<OptionItemDto> fieldUserOptions
    ) {
    }

    public record FieldOperationsViewDto(
            Long selectedEventId,
            String selectedEventName,
            String currentUserName,
            String currentUserRole,
            List<OptionItemDto> availableEvents,
            List<StaffAssignmentDto> myShifts,
            List<StaffAssignmentDto> eventShifts,
            List<OptionItemDto> zoneOptions,
            List<OptionItemDto> staffOptions,
            List<IncidentLogDto> incidents
    ) {
    }
}
