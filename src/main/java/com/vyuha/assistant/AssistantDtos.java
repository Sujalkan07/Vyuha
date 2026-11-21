package com.vyuha.assistant;

import com.vyuha.simulation.SimulationConfig;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.simulation.SimulationScenarioType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Request and response DTO contracts for Phase 11 Planning Assistant & Guideline Search.
 */
public final class AssistantDtos {

    private AssistantDtos() {
    }

    public record ExplainRequest(
            @Size(max = 1000, message = "Prompt must be 1000 characters or fewer.")
            String prompt,
            @Size(max = 40)
            String focusArea
    ) {
    }

    public record VerifiedMetricsSnapshot(
            boolean hasBoundary,
            double grossAreaSqm,
            double usableAreaSqm,
            double excludedTotalAreaSqm,
            boolean scaleVerified,
            boolean distortionWarning,
            Integer expectedAttendance,
            Integer preliminaryCapacity,
            String governingConstraint,
            String capacityReviewStatus,
            int activeConflictCount,
            int criticalConflictCount,
            int activeBottleneckCount,
            int criticalBottleneckCount,
            Long latestSimulationRunId,
            String latestSimulationLabel,
            Double latestPeakDensityPsm,
            Integer latestPeakQueueLength,
            Integer latestUnservedCount
    ) {
    }

    public record AdviceItemDto(
            String id,
            String sourceModule,
            String severity,
            String title,
            String measuredEvidence,
            String plainLanguageExplanation,
            List<String> manualRemediationSteps,
            Double locationX,
            Double locationY,
            String navigateUrl,
            List<GuidelineDocument.GuidelineHit> citations
    ) {
    }

    public record AdvisorResponseDto(
            String attributionTag,
            String readOnlyNotice,
            String provider,
            Long eventId,
            String eventName,
            String focusArea,
            String prompt,
            String executiveSummary,
            VerifiedMetricsSnapshot verifiedMetrics,
            List<AdviceItemDto> recommendations,
            List<GuidelineDocument.GuidelineHit> citations,
            Long interactionLogId,
            Instant generatedAt
    ) {
    }

    public record WhatIfPreviewRequest(
            @NotBlank(message = "Enter a plain-English What-If scenario prompt.")
            @Size(max = 1000, message = "Prompt must be 1000 characters or fewer.")
            String prompt,
            Long snapshotId,
            Long baselineRunId,
            Long randomSeed,
            Integer agentCount,
            Integer durationSeconds
    ) {
    }

    public record TargetOptionDto(
            String uid,
            Long entityId,
            String label,
            String detail
    ) {
    }

    public record WhatIfPreviewDto(
            String attributionTag,
            String readOnlyNotice,
            String provider,
            Long interactionLogId,
            boolean requiresConfirmation,
            boolean layoutReadyForSimulation,
            String readinessMessage,
            SimulationScenarioType parsedScenarioType,
            String parsedScenarioDisplayName,
            SimulationConfig proposedConfig,
            String matchedTargetType,
            String matchedTargetLabel,
            boolean targetAutoSelected,
            String confidence,
            Long targetSnapshotId,
            String targetSnapshotLabel,
            Long baselineRunId,
            String baselineRunLabel,
            boolean willCreateBaselineRun,
            List<TargetOptionDto> availableExits,
            List<TargetOptionDto> availableCorridors,
            List<String> parsingNotes,
            String confirmationSummary
    ) {
    }

    public record WhatIfConfirmRequest(
            Long interactionLogId,
            boolean confirmed,
            @NotNull(message = "Scenario type is required.")
            SimulationScenarioType scenarioType,
            Long snapshotId,
            Long baselineRunId,
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
            Integer disruptionStartSec,
            @Size(max = 160)
            String label
    ) {
    }

    public record WhatIfExecutionDto(
            String attributionTag,
            String disclaimer,
            String provider,
            Long interactionLogId,
            SimulationDtos.SimulationRunSummaryDto whatIfRun,
            SimulationDtos.SimulationRunSummaryDto baselineRun,
            SimulationDtos.SimulationComparisonDto comparison,
            String plainLanguageSummary,
            List<String> keyTakeaways,
            String replayUrl,
            String compareUrl,
            List<GuidelineDocument.GuidelineHit> citations,
            Instant executedAt
    ) {
    }

    public record InteractionLogSummaryDto(
            Long id,
            AssistantMode mode,
            String modeDisplayName,
            String prompt,
            String responseExcerpt,
            boolean confirmedByUser,
            String userName,
            Instant createdAt
    ) {
    }
}
