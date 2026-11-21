package com.vyuha.reporting;

import com.vyuha.capacity.CapacityDtos;
import com.vyuha.emergency.EmergencyDtos;
import com.vyuha.event.ChecklistProgress;
import com.vyuha.event.Event;
import com.vyuha.event.EventChecklistItem;
import com.vyuha.event.SafetyReviewRecord;
import com.vyuha.event.SafetyReviewStatus;
import com.vyuha.event.SupportingDocument;
import com.vyuha.grid.GridDtos;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.monitoring.MonitoringDtos;
import com.vyuha.network.NetworkDtos;
import com.vyuha.operations.OperationsDtos;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.venue.VenueDtos;
import java.time.Instant;
import java.util.List;

/**
 * Aggregated reporting bundle, section readiness indicators, and export audit DTOs for Phase 12.
 */
public final class ReportDtos {

    public static final String DECISION_SUPPORT_NOTICE =
            "Decision-Support Report — Vyuha highlights measurable layout risks; all capacity limits, safety approvals, and emergency decisions remain human-led.";
    public static final String SIMULATION_DISCLAIMER = SimulationDtos.MANDATORY_DISCLAIMER;
    public static final String EMERGENCY_DEFERENCE_NOTICE =
            "Emergency Authority Deference — This layout contingency summary supports planning review only and strictly defers operational emergency command and evacuation decisions to officially approved response plans and statutory authorities.";
    public static final String LIVE_TELEMETRY_NOTICE =
            "Strict Source Segregation — Manual Staff Counts (MANUAL_STAFF), Attendee Pass Scans (PASS_SCAN), and Practice Drills (PRACTICE_SIMULATION) are never blended into a combined occupancy figure.";

    private ReportDtos() {
    }

    public record ReportSectionReadiness(
            String key,
            String sectionNumber,
            String title,
            String statusLabel,
            String badgeClass,
            String detailSummary,
            String targetHref,
            boolean ready
    ) {
    }

    public record ReportExportAuditDto(
            Long id,
            ReportExportFormat format,
            String formatLabel,
            String scope,
            String filename,
            long fileSizeBytes,
            String exportedByName,
            Instant exportedAt,
            String summaryNote
    ) {
    }

    public record CsvDatasetOption(
            String key,
            String label,
            String description
    ) {
    }

    public record EventReportBundle(
            Event event,
            Instant generatedAt,
            List<EventChecklistItem> checklist,
            ChecklistProgress checklistProgress,
            List<SafetyReviewRecord> safetyReviews,
            SafetyReviewStatus latestSafetyReviewStatus,
            List<SupportingDocument> documents,
            VenueDtos.VenueWorkspaceDto venueWorkspace,
            GridDtos.GridWorkspaceDto gridWorkspace,
            CapacityDtos.CapacityPageView capacityView,
            InfrastructureDtos.InfrastructureWorkspaceDto infraWorkspace,
            NetworkDtos.NetworkWorkspaceDto networkWorkspace,
            OperationsDtos.OperationsWorkspaceDto operationsWorkspace,
            EmergencyDtos.EmergencyWorkspaceDto emergencyWorkspace,
            SimulationDtos.SimulationWorkspaceDto simulationWorkspace,
            SimulationDtos.SimulationComparisonDto featuredComparison,
            MonitoringDtos.ControlCentreDto liveManualView,
            MonitoringDtos.ControlCentreDto livePassView,
            MonitoringDtos.ControlCentreDto practiceView,
            List<ReportSectionReadiness> sectionReadiness,
            int readySectionCount,
            int totalSectionCount,
            int activeCriticalOrHighFindings,
            boolean demoCampusFestival
    ) {
    }

    public record ReportPageSummary(
            Long eventId,
            String eventName,
            String safetyReviewLabel,
            String safetyReviewBadgeClass,
            int readySectionCount,
            int totalSectionCount,
            int activeCriticalOrHighFindings,
            String capacityStatusLabel,
            String capacityBadgeClass,
            String simulationStatusLabel,
            String simulationBadgeClass,
            boolean hasComparison,
            boolean demoCampusFestival,
            List<ReportSectionReadiness> sections,
            List<CsvDatasetOption> csvDatasets,
            List<ReportExportAuditDto> recentExports
    ) {
    }

    public record GeneratedExportFile(
            ReportExportFormat format,
            String scope,
            String filename,
            String contentType,
            byte[] bytes,
            String summaryNote
    ) {
    }
}
