package com.vyuha.reporting;

import com.vyuha.capacity.CapacityDtos;
import com.vyuha.capacity.CapacityReviewStatus;
import com.vyuha.capacity.CapacityService;
import com.vyuha.emergency.EmergencyDtos;
import com.vyuha.emergency.EmergencyService;
import com.vyuha.event.ChecklistProgress;
import com.vyuha.event.ChecklistTemplateService;
import com.vyuha.event.Event;
import com.vyuha.event.EventChecklistItem;
import com.vyuha.event.EventService;
import com.vyuha.event.SafetyReviewRecord;
import com.vyuha.event.SafetyReviewService;
import com.vyuha.event.SafetyReviewStatus;
import com.vyuha.event.SupportingDocument;
import com.vyuha.event.SupportingDocumentService;
import com.vyuha.grid.GridDtos;
import com.vyuha.grid.GridZoneService;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.infrastructure.InfrastructureService;
import com.vyuha.monitoring.LiveMonitoringService;
import com.vyuha.monitoring.MonitoringDtos;
import com.vyuha.monitoring.OccupancyDataSource;
import com.vyuha.network.NetworkDtos;
import com.vyuha.network.NetworkService;
import com.vyuha.operations.OperationsDtos;
import com.vyuha.operations.OperationsService;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.simulation.SimulationService;
import com.vyuha.venue.VenueDtos;
import com.vyuha.venue.VenueService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates read-only domain projections from Phases 2–10 into a single unified EventReportBundle.
 */
@Service
public class ReportBundleBuilderService {

    public static final String DEMO_EVENT_NAME = "TechnoVyuha Annual Campus Festival";

    private final EventService eventService;
    private final ChecklistTemplateService checklistTemplateService;
    private final SafetyReviewService safetyReviewService;
    private final SupportingDocumentService supportingDocumentService;
    private final VenueService venueService;
    private final GridZoneService gridZoneService;
    private final CapacityService capacityService;
    private final InfrastructureService infrastructureService;
    private final NetworkService networkService;
    private final OperationsService operationsService;
    private final EmergencyService emergencyService;
    private final SimulationService simulationService;
    private final LiveMonitoringService liveMonitoringService;

    public ReportBundleBuilderService(EventService eventService,
                                      ChecklistTemplateService checklistTemplateService,
                                      SafetyReviewService safetyReviewService,
                                      SupportingDocumentService supportingDocumentService,
                                      VenueService venueService,
                                      GridZoneService gridZoneService,
                                      CapacityService capacityService,
                                      InfrastructureService infrastructureService,
                                      NetworkService networkService,
                                      OperationsService operationsService,
                                      EmergencyService emergencyService,
                                      SimulationService simulationService,
                                      LiveMonitoringService liveMonitoringService) {
        this.eventService = eventService;
        this.checklistTemplateService = checklistTemplateService;
        this.safetyReviewService = safetyReviewService;
        this.supportingDocumentService = supportingDocumentService;
        this.venueService = venueService;
        this.gridZoneService = gridZoneService;
        this.capacityService = capacityService;
        this.infrastructureService = infrastructureService;
        this.networkService = networkService;
        this.operationsService = operationsService;
        this.emergencyService = emergencyService;
        this.simulationService = simulationService;
        this.liveMonitoringService = liveMonitoringService;
    }

    @Transactional(readOnly = true)
    public ReportDtos.EventReportBundle buildBundle(Long eventId) {
        Event event = eventService.getEventOrThrow(eventId);
        List<EventChecklistItem> checklist = eventService.getChecklist(eventId);
        ChecklistProgress checklistProgress = checklistTemplateService.computeProgress(checklist);
        List<SafetyReviewRecord> safetyReviews = safetyReviewService.getReviewsForEvent(eventId);
        SafetyReviewStatus latestReview = safetyReviews.isEmpty() ? null : safetyReviews.get(0).getReviewStatus();
        List<SupportingDocument> documents = supportingDocumentService.getDocumentsForEvent(eventId);

        VenueDtos.VenueWorkspaceDto venue = venueService.getWorkspaceState(eventId, false);
        GridDtos.GridWorkspaceDto grid = gridZoneService.getGridWorkspace(eventId, false);
        CapacityDtos.CapacityPageView capacity = capacityService.getCapacityView(eventId, false, false);
        InfrastructureDtos.InfrastructureWorkspaceDto infra = infrastructureService.getWorkspace(eventId, false, false);
        NetworkDtos.NetworkWorkspaceDto network = networkService.getWorkspace(eventId, false, false);
        OperationsDtos.OperationsWorkspaceDto ops = operationsService.getWorkspace(eventId, false, false);
        EmergencyDtos.EmergencyWorkspaceDto emergency = emergencyService.getWorkspace(eventId, false);
        SimulationDtos.SimulationWorkspaceDto sim = simulationService.getWorkspace(eventId, false, true);

        SimulationDtos.SimulationComparisonDto featuredComparison = resolveFeaturedComparison(eventId, sim.runs());

        MonitoringDtos.ControlCentreDto liveManual = liveMonitoringService.getControlCentre(
                eventId, LiveMonitoringService.MODE_LIVE, OccupancyDataSource.MANUAL_STAFF, false, false);
        MonitoringDtos.ControlCentreDto livePass = liveMonitoringService.getControlCentre(
                eventId, LiveMonitoringService.MODE_LIVE, OccupancyDataSource.PASS_SCAN, false, false);
        MonitoringDtos.ControlCentreDto practice = liveMonitoringService.getControlCentre(
                eventId, LiveMonitoringService.MODE_PRACTICE, OccupancyDataSource.PRACTICE_SIMULATION, false, false);

        List<ReportDtos.ReportSectionReadiness> sections = evaluateSections(
                event, checklistProgress, safetyReviews, latestReview,
                venue, grid, capacity, infra, network, ops, emergency, sim, featuredComparison,
                liveManual, livePass);

        int readyCount = (int) sections.stream().filter(ReportDtos.ReportSectionReadiness::ready).count();
        int activeCriticalOrHigh = countCriticalOrHighFindings(infra, network, ops, emergency);
        boolean isDemo = DEMO_EVENT_NAME.equalsIgnoreCase(event.getName());

        return new ReportDtos.EventReportBundle(
                event,
                Instant.now(),
                checklist,
                checklistProgress,
                safetyReviews,
                latestReview,
                documents,
                venue,
                grid,
                capacity,
                infra,
                network,
                ops,
                emergency,
                sim,
                featuredComparison,
                liveManual,
                livePass,
                practice,
                sections,
                readyCount,
                sections.size(),
                activeCriticalOrHigh,
                isDemo
        );
    }

    private SimulationDtos.SimulationComparisonDto resolveFeaturedComparison(
            Long eventId,
            List<SimulationDtos.SimulationRunSummaryDto> runs) {
        if (runs == null || runs.size() < 2) {
            return null;
        }
        List<SimulationDtos.SimulationRunSummaryDto> sorted = runs.stream()
                .sorted(Comparator.comparingInt(SimulationDtos.SimulationRunSummaryDto::snapshotVersion)
                        .thenComparing(SimulationDtos.SimulationRunSummaryDto::id))
                .toList();

        // Prefer a paired comparison with matching scenario, seed, agentCount, duration across distinct snapshot versions.
        for (int i = 0; i < sorted.size(); i++) {
            SimulationDtos.SimulationRunSummaryDto before = sorted.get(i);
            for (int j = sorted.size() - 1; j > i; j--) {
                SimulationDtos.SimulationRunSummaryDto after = sorted.get(j);
                if (after.snapshotVersion() > before.snapshotVersion()
                        && before.scenarioType() == after.scenarioType()
                        && before.randomSeed() == after.randomSeed()
                        && before.agentCount() == after.agentCount()
                        && before.durationSeconds() == after.durationSeconds()) {
                    return simulationService.compareExistingRuns(eventId, before.id(), after.id());
                }
            }
        }

        // Otherwise compare earliest and latest runs across distinct snapshots if available.
        SimulationDtos.SimulationRunSummaryDto earliest = sorted.get(0);
        SimulationDtos.SimulationRunSummaryDto latest = sorted.get(sorted.size() - 1);
        if (!earliest.id().equals(latest.id())) {
            return simulationService.compareExistingRuns(eventId, earliest.id(), latest.id());
        }
        return null;
    }

    private List<ReportDtos.ReportSectionReadiness> evaluateSections(
            Event event,
            ChecklistProgress checklistProgress,
            List<SafetyReviewRecord> safetyReviews,
            SafetyReviewStatus latestReview,
            VenueDtos.VenueWorkspaceDto venue,
            GridDtos.GridWorkspaceDto grid,
            CapacityDtos.CapacityPageView capacity,
            InfrastructureDtos.InfrastructureWorkspaceDto infra,
            NetworkDtos.NetworkWorkspaceDto network,
            OperationsDtos.OperationsWorkspaceDto ops,
            EmergencyDtos.EmergencyWorkspaceDto emergency,
            SimulationDtos.SimulationWorkspaceDto sim,
            SimulationDtos.SimulationComparisonDto comparison,
            MonitoringDtos.ControlCentreDto liveManual,
            MonitoringDtos.ControlCentreDto livePass) {

        Long id = event.getId();
        List<ReportDtos.ReportSectionReadiness> list = new ArrayList<>();

        // 1. Event Metadata & Review
        boolean hasReview = !safetyReviews.isEmpty();
        list.add(new ReportDtos.ReportSectionReadiness(
                "overview", "1", "Event Overview, Checklist & Safety Reviews",
                hasReview ? latestReview.getDisplayName() : "Review Pending",
                hasReview ? latestReview.getBadgeClass() : "badge-low",
                String.format(Locale.US, "%d/%d checklist items complete (%d%%) · %d review log entries",
                        checklistProgress.completedCount(), checklistProgress.applicableItems(),
                        checklistProgress.completionPct(), safetyReviews.size()),
                "/events/" + id,
                checklistProgress.applicableItems() > 0 && hasReview
        ));

        // 2. Site Plan & Area Breakdown
        boolean hasBoundary = venue.areaBreakdown().hasBoundary();
        boolean calOk = !venue.venuePlan().distortionWarning();
        list.add(new ReportDtos.ReportSectionReadiness(
                "site", "2", "Site Plan, Scale Calibration & Usable Area",
                !hasBoundary ? "Boundary Missing" : (calOk ? "Ready" : "Distortion Warning"),
                !hasBoundary ? "badge-low" : (calOk ? "badge-verified" : "badge-high"),
                hasBoundary
                        ? String.format(Locale.US, "Gross %.1f m² · Net Usable %.1f m² · Exclusions %d",
                        venue.areaBreakdown().grossAreaSqm(), venue.areaBreakdown().usableAreaSqm(),
                        venue.exclusions().size())
                        : "Draw outer boundary to compute usable area.",
                "/events/" + id + "/venue?mode=site",
                hasBoundary
        ));

        // 3. Zones & Preliminary Capacity Review
        boolean hasZones = !grid.zones().isEmpty();
        CapacityReviewStatus capStatus = capacity.calculation().effectiveReviewStatus();
        boolean capVerified = capStatus == CapacityReviewStatus.REVIEWER_VERIFIED;
        String capBadge = capVerified ? "badge-verified"
                : (capStatus == CapacityReviewStatus.FLAGGED ? "badge-high" : "badge-low");
        list.add(new ReportDtos.ReportSectionReadiness(
                "capacity", "3", "Zones & Preliminary Capacity Review",
                hasZones ? capStatus.getDisplayName() : "No Zones",
                hasZones ? capBadge : "badge-low",
                capacity.calculation().preliminaryCapacity() != null
                        ? String.format(Locale.US, "Ceiling: %d persons (%s) · %d zones",
                        capacity.calculation().preliminaryCapacity(),
                        capacity.calculation().governingConstraint() != null
                                ? capacity.calculation().governingConstraint().getDisplayName() : "—",
                        grid.zones().size())
                        : "Document zone density assumptions to compute capacity ceiling.",
                "/events/" + id + "/capacity?tab=review",
                hasZones && capacity.calculation().preliminaryCapacity() != null
        ));

        // 4. Infrastructure & Spatial Conflicts
        int infraCount = infra.elements().size();
        int conflictCount = infra.summary().openFindingsCount();
        list.add(new ReportDtos.ReportSectionReadiness(
                "infrastructure", "4", "Infrastructure & Spatial Conflict Audit",
                infraCount == 0 ? "No Elements" : (conflictCount > 0 ? "Warnings Found" : "No Active Conflicts"),
                infraCount == 0 ? "badge-neutral" : (conflictCount > 0 ? "badge-high" : "badge-verified"),
                String.format(Locale.US, "%d elements · %d active findings (%d critical, %d high)",
                        infraCount, conflictCount, infra.summary().criticalCount(), infra.summary().highCount()),
                "/events/" + id + "/venue?mode=infrastructure",
                infraCount > 0
        ));

        // 5. Pedestrian Network & Bottlenecks
        int nodeCount = network.nodes().size();
        int edgeCount = network.edges().size();
        int bnCount = network.summary().openFindingsCount();
        list.add(new ReportDtos.ReportSectionReadiness(
                "pathways", "5", "Pedestrian Pathways & Bottleneck Screening",
                edgeCount == 0 ? "No Network" : (bnCount > 0 ? "Warnings Found" : "No Active Bottlenecks"),
                edgeCount == 0 ? "badge-neutral" : (bnCount > 0 ? "badge-high" : "badge-verified"),
                String.format(Locale.US, "%d nodes · %d corridors · %d active findings",
                        nodeCount, edgeCount, bnCount),
                "/events/" + id + "/venue?mode=pathways",
                edgeCount > 0
        ));

        // 6. Arrival Waves & Queue Projection
        int waveCount = ops.queueProjection().intervals().size();
        int overflowCount = ops.queueProjection().overflowIntervalCount();
        list.add(new ReportDtos.ReportSectionReadiness(
                "arrivals", "6", "Arrival Waves & Queue Overflow Projection",
                waveCount == 0 ? "Not Configured" : (overflowCount > 0 ? "Overflow Flagged" : "Ready"),
                waveCount == 0 ? "badge-neutral" : (overflowCount > 0 ? "badge-high" : "badge-verified"),
                waveCount > 0
                        ? String.format(Locale.US, "%d intervals · Peak queue %d · Overflow intervals %d",
                        waveCount, ops.queueProjection().peakQueueLength(), overflowCount)
                        : "Add arrival wave intervals to project gate queues.",
                "/events/" + id + "/capacity?tab=arrivals",
                waveCount > 0
        ));

        // 7. Staff Roster, Coverage & Incident Log
        int staffCount = ops.staffMembers().size();
        int staffWarn = ops.staffCoverage().totalFindingCount();
        list.add(new ReportDtos.ReportSectionReadiness(
                "staff", "7", "Staff Roster, Shift Coverage & Incidents",
                staffCount == 0 ? "No Roster" : (staffWarn > 0 ? "Coverage Gaps" : "Ready"),
                staffCount == 0 ? "badge-neutral" : (staffWarn > 0 ? "badge-low" : "badge-verified"),
                String.format(Locale.US, "%d staff · %d shifts · %d incidents logged",
                        staffCount, ops.assignments().size(), ops.incidents().size()),
                "/events/" + id + "/capacity?tab=staff",
                staffCount > 0 && !ops.assignments().isEmpty()
        ));

        // 8. Emergency Preparedness
        boolean emWarn = emergency.validation().hasCriticalWarnings() || !emergency.validation().findings().isEmpty();
        boolean emHasData = !emergency.assets().isEmpty() || !emergency.contacts().isEmpty();
        list.add(new ReportDtos.ReportSectionReadiness(
                "emergency", "8", "Emergency Preparedness & Outage Resilience",
                !emHasData ? "Pending Setup" : (emWarn ? "Warnings Found" : "Route Checks Ready"),
                !emHasData ? "badge-neutral" : (emWarn ? "badge-high" : "badge-verified"),
                String.format(Locale.US, "%d/%d vehicle routes compliant · %d contacts · %d emergency exits",
                        emergency.validation().compliantVehicleRoutes(), emergency.validation().totalVehicleRoutes(),
                        emergency.validation().contactCount(), emergency.validation().emergencyExitCount()),
                "/events/" + id + "/emergency",
                emHasData
        ));

        // 9. Crowd Simulation & Comparison
        int runCount = sim.runs().size();
        list.add(new ReportDtos.ReportSectionReadiness(
                "simulation", "9", "Crowd Simulation & Before-and-After Comparison",
                comparison != null ? "Comparison Ready" : (runCount > 0 ? "Single Run Only" : "Not Run"),
                comparison != null ? "badge-verified" : (runCount > 0 ? "badge-info" : "badge-neutral"),
                String.format(Locale.US, "%d layout snapshots · %d simulation runs",
                        sim.snapshots().size(), runCount),
                "/events/" + id + "/simulation",
                runCount > 0
        ));

        // 10. Live Monitoring & Passes
        int passes = livePass.passSummary().totalIssued();
        int readings = liveManual.recentReadings().size() + livePass.recentReadings().size();
        list.add(new ReportDtos.ReportSectionReadiness(
                "live", "10", "Live Occupancy Telemetry & Attendee Passes",
                (passes > 0 || readings > 0) ? "Telemetry Recorded" : "Pre-Event",
                (passes > 0 || readings > 0) ? "badge-info" : "badge-neutral",
                String.format(Locale.US, "%d passes issued (%d checked in) · %d manual/scan readings",
                        passes, livePass.passSummary().checkedInCount(), readings),
                "/events/" + id + "/live",
                passes > 0 || readings > 0
        ));

        return List.copyOf(list);
    }

    private int countCriticalOrHighFindings(InfrastructureDtos.InfrastructureWorkspaceDto infra,
                                            NetworkDtos.NetworkWorkspaceDto network,
                                            OperationsDtos.OperationsWorkspaceDto ops,
                                            EmergencyDtos.EmergencyWorkspaceDto emergency) {
        int total = infra.summary().criticalCount() + infra.summary().highCount()
                + network.summary().criticalCount() + network.summary().highCount()
                + ops.staffCoverage().criticalFindingCount() + ops.staffCoverage().highFindingCount();
        for (EmergencyDtos.EmergencyValidationFinding f : emergency.validation().findings()) {
            if (f.severity() == FindingSeverity.CRITICAL || f.severity() == FindingSeverity.HIGH) {
                total++;
            }
        }
        return total;
    }
}
