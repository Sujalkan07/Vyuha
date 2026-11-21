package com.vyuha.reporting;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.vyuha.capacity.CapacityDtos;
import com.vyuha.emergency.EmergencyDtos;
import com.vyuha.event.Event;
import com.vyuha.event.EventChecklistItem;
import com.vyuha.event.SafetyReviewRecord;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.monitoring.MonitoringDtos;
import com.vyuha.network.NetworkDtos;
import com.vyuha.operations.OperationsDtos;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.venue.AreaBreakdownResult;
import com.vyuha.venue.CalibrationData;
import com.vyuha.venue.VenueDtos;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Generates printable, offline A4 PDF reports via OpenHTMLToPDF with embedded PNG snapshots
 * and all mandatory Vyuha human-led decision-support disclaimers.
 */
@Component
public class PdfReportGenerator {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReportCanvasRenderer canvasRenderer;

    public PdfReportGenerator(ReportCanvasRenderer canvasRenderer) {
        this.canvasRenderer = canvasRenderer;
    }

    public byte[] generatePdf(ReportDtos.EventReportBundle bundle) {
        byte[] layoutPng = canvasRenderer.renderCurrentLayoutPng(bundle);
        byte[] comparisonPng = bundle.featuredComparison() != null
                ? canvasRenderer.renderComparisonPng(bundle.featuredComparison())
                : null;

        String xhtml = buildXhtml(bundle, layoutPng, comparisonPng);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(xhtml, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate PDF report via OpenHTMLToPDF.", ex);
        }
    }

    String buildXhtml(ReportDtos.EventReportBundle bundle, byte[] layoutPng, byte[] comparisonPng) {
        Event ev = bundle.event();
        StringBuilder sb = new StringBuilder(16384);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\" />\n");
        sb.append("<title>").append(esc(ev.getName())).append(" — Vyuha Planning Report</title>\n");
        sb.append("<style>\n");
        sb.append(buildPrintCss());
        sb.append("</style>\n</head>\n<body>\n");

        // Cover & Event Metadata
        sb.append("<div class=\"report-header\">\n");
        sb.append("  <div class=\"brand-line\">VYUHA &#183; CROWD SAFETY &amp; VENUE PLANNING REPORT</div>\n");
        sb.append("  <h1>").append(esc(ev.getName())).append("</h1>\n");
        sb.append("  <div class=\"meta-line\">Category: ").append(esc(ev.getCategory().getDisplayName()))
                .append(" | Lifecycle: ").append(esc(ev.getStatus().getDisplayName()))
                .append(" | Expected Attendance: <span class=\"mono\">")
                .append(ev.getExpectedAttendance() != null ? ev.getExpectedAttendance() : "TBD")
                .append("</span> | Generated: <span class=\"mono\">").append(formatInstant(bundle.generatedAt()))
                .append("</span></div>\n");
        sb.append("</div>\n");

        // Mandatory Decision-Support Banner
        sb.append("<div class=\"banner banner-info\"><strong>Human-Led Decision-Support Notice:</strong> ")
                .append(esc(ReportDtos.DECISION_SUPPORT_NOTICE)).append("</div>\n");

        // Section 1: Event Metadata & Safety Reviews
        sb.append("<h2>1. Event Metadata, Safety Review Status &amp; Checklist Progress</h2>\n");
        sb.append("<table class=\"kv-table\"><tbody>\n");
        rowKv(sb, "Location", safe(ev.getLocationName()) + (ev.getLocationAddress() != null ? " — " + ev.getLocationAddress() : ""));
        rowKv(sb, "Schedule", (ev.getStartDateTime() != null ? LOCAL_FMT.format(ev.getStartDateTime()) : "TBD")
                + " to " + (ev.getEndDateTime() != null ? LOCAL_FMT.format(ev.getEndDateTime()) : "TBD"));
        rowKv(sb, "Organizer", ev.getOrganizer() != null ? ev.getOrganizer().getFullName() : "—");
        rowKv(sb, "Assigned Reviewer", ev.getAssignedReviewer() != null ? ev.getAssignedReviewer().getFullName() : "Unassigned");
        rowKv(sb, "Latest Human Safety Review", bundle.latestSafetyReviewStatus() != null
                ? bundle.latestSafetyReviewStatus().getDisplayName() : "Review Pending");
        rowKv(sb, "Checklist Completion", String.format(Locale.US, "%d / %d applicable items complete (%d%%); %d reviewer sign-offs",
                bundle.checklistProgress().completedCount(), bundle.checklistProgress().applicableItems(),
                bundle.checklistProgress().completionPct(), bundle.checklistProgress().signedOffCount()));
        sb.append("</tbody></table>\n");

        if (!bundle.safetyReviews().isEmpty()) {
            sb.append("<h3>Formal Safety Review Log</h3>\n<table class=\"data-table\"><thead><tr>")
                    .append("<th>Date</th><th>Reviewer</th><th>Status</th><th>Findings Summary &amp; Response</th></tr></thead><tbody>\n");
            for (SafetyReviewRecord rec : bundle.safetyReviews()) {
                sb.append("<tr><td class=\"mono\">").append(formatInstant(rec.getReviewedAt())).append("</td>")
                        .append("<td>").append(esc(rec.getReviewer().getFullName())).append("</td>")
                        .append("<td>").append(esc(rec.getReviewStatus().getDisplayName())).append("</td>")
                        .append("<td>").append(esc(rec.getFindingsSummary()));
                if (rec.getOrganizerResponse() != null && !rec.getOrganizerResponse().isBlank()) {
                    sb.append("<br/><em>Organizer response:</em> ").append(esc(rec.getOrganizerResponse()));
                }
                sb.append("</td></tr>\n");
            }
            sb.append("</tbody></table>\n");
        }

        // Section 2: Site Plan Snapshot & Calibration Audit
        sb.append("<h2>2. Site Plan Visual Snapshot &amp; Scale Calibration Audit</h2>\n");
        VenueDtos.VenuePlanDto vp = bundle.venueWorkspace().venuePlan();
        sb.append("<table class=\"kv-table\"><tbody>\n");
        rowKv(sb, "Workspace Mode", vp.workspaceMode().getDisplayName());
        rowKv(sb, "Scale Ratio", vp.pixelsPerMeter() != null
                ? String.format(Locale.US, "%.2f px/m (%.3f m/px)", vp.pixelsPerMeter(), 1.0 / vp.pixelsPerMeter())
                : "Vector metric workspace (1.0 m units)");
        CalibrationData cal = vp.calibration();
        if (cal != null) {
            rowKv(sb, "Calibration Audit", String.format(Locale.US,
                    "Primary: %.1f px = %.2f m | Verification Mismatch: %s | Verified: %s | Distortion Warning: %s",
                    cal.primaryPixelLength(), cal.primaryRealDistanceMeters(),
                    cal.mismatchPercent() != null ? String.format(Locale.US, "%.2f%%", cal.mismatchPercent()) : "N/A",
                    vp.scaleVerified() ? "YES" : "NO",
                    vp.distortionWarning() ? "FLAGGED (>5%)" : "None"));
        } else {
            rowKv(sb, "Calibration Audit", vp.hasBlueprint() ? "Blueprint uploaded (calibration pending)" : "Built-in metric vector site map");
        }
        sb.append("</tbody></table>\n");
        sb.append("<div class=\"img-box\"><img class=\"snapshot-img\" alt=\"Current Venue Layout\" src=\"")
                .append(canvasRenderer.toBase64DataUri(layoutPng)).append("\" /></div>\n");

        // Section 3: Area Breakdown
        sb.append("<h2>3. Real-World Usable Area Breakdown (JTS Union Deduplicated)</h2>\n");
        AreaBreakdownResult area = bundle.venueWorkspace().areaBreakdown();
        sb.append("<table class=\"data-table\"><thead><tr><th>Space Category</th><th class=\"num\">Area (m&#178;)</th><th class=\"num\">Share of Gross</th></tr></thead><tbody>\n");
        rowArea(sb, "Gross Site Area", area.grossAreaSqm(), 100.0);
        rowArea(sb, "Reserved Walkways / Corridors", area.corridorsAreaSqm(), area.corridorsPercent());
        rowArea(sb, "Buildings, Stages &amp; Restricted Exclusions", area.restrictedAreaSqm(), area.restrictedPercent());
        rowArea(sb, "Total Excluded (Deduplicated Union)", area.excludedTotalAreaSqm(), area.excludedPercent());
        rowArea(sb, "Net Usable Gathering Area", area.usableAreaSqm(), area.usablePercent());
        sb.append("</tbody></table>\n");

        // Section 4: Zones & Capacity Review
        sb.append("<h2>4. Functional Zones &amp; Preliminary Capacity Review</h2>\n");
        CapacityDtos.CapacityCalculationResult cap = bundle.capacityView().calculation();
        sb.append("<table class=\"kv-table\"><tbody>\n");
        rowKv(sb, "Capacity Review Status", cap.effectiveReviewStatus().getDisplayName());
        rowKv(sb, "Usable Standing Capacity (C_space)", intStr(cap.usableSpaceCapacity()));
        rowKv(sb, "Entry/Exit Flow Capacity (C_flow)", intStr(cap.flowCapacity())
                + " (Entry " + intStr(cap.entryFlowCapacity()) + " / Exit " + intStr(cap.exitFlowCapacity()) + ")");
        rowKv(sb, "Holding / Queue Capacity (C_holding)", intStr(cap.holdingSpaceCapacity()));
        rowKv(sb, "Official Venue Limit (C_official)", intStr(cap.officialVenueLimit()));
        rowKv(sb, "Preliminary Governing Ceiling", intStr(cap.preliminaryCapacity())
                + (cap.governingConstraint() != null ? " — Governing: " + cap.governingConstraint().getDisplayName() : ""));
        sb.append("</tbody></table>\n");

        if (!cap.zoneRows().isEmpty()) {
            sb.append("<table class=\"data-table\"><thead><tr>")
                    .append("<th>Zone</th><th>Type</th><th class=\"num\">Usable m&#178;</th><th class=\"num\">Density (p/m&#178;)</th><th class=\"num\">Cap</th><th>Source &amp; Review</th></tr></thead><tbody>\n");
            for (CapacityDtos.ZoneCapacityRow zr : cap.zoneRows()) {
                sb.append("<tr><td>").append(esc(zr.zoneCode() + " · " + zr.name())).append("</td>")
                        .append("<td>").append(esc(zr.zoneTypeName())).append("</td>")
                        .append("<td class=\"num mono\">").append(fmt2(zr.usableZoneAreaSqm())).append("</td>")
                        .append("<td class=\"num mono\">").append(zr.densityPersonsPerSqm() != null ? fmt2(zr.densityPersonsPerSqm()) : "—").append("</td>")
                        .append("<td class=\"num mono\">").append(zr.computedCapacity() != null ? zr.computedCapacity() : "—").append("</td>")
                        .append("<td>").append(esc(safe(zr.sourceCitation()))).append(" (")
                        .append(esc(zr.reviewStatus() != null ? zr.reviewStatus().getDisplayName() : "Pending")).append(")</td></tr>\n");
            }
            sb.append("</tbody></table>\n");
        }

        // Section 5: Infrastructure & Conflicts
        sb.append("<h2>5. Infrastructure Inventory &amp; Automated Conflict Audit</h2>\n");
        InfrastructureDtos.InfrastructureSummaryDto isum = bundle.infraWorkspace().summary();
        sb.append("<p class=\"summary-line mono\">Elements: ").append(isum.totalElements())
                .append(" (Active: ").append(isum.activeElements())
                .append(") | Active Conflict Findings: ").append(isum.openFindingsCount())
                .append(" (Critical: ").append(isum.criticalCount()).append(", High: ").append(isum.highCount()).append(")</p>\n");
        if (!bundle.infraWorkspace().activeFindings().isEmpty()) {
            sb.append("<table class=\"data-table\"><thead><tr><th>Severity</th><th>Conflict</th><th>Measured</th><th>Review</th></tr></thead><tbody>\n");
            for (InfrastructureDtos.ConflictFindingDto cf : bundle.infraWorkspace().activeFindings()) {
                sb.append("<tr><td><span class=\"sev-").append(cf.severity().name().toLowerCase(Locale.ROOT)).append("\">")
                        .append(cf.severity().name()).append("</span></td>")
                        .append("<td><strong>").append(esc(cf.title())).append("</strong><br/>").append(esc(cf.description())).append("</td>")
                        .append("<td class=\"mono\">").append(esc(safe(cf.measuredValue()))).append("</td>")
                        .append("<td>").append(esc(cf.reviewStatusName())).append("</td></tr>\n");
            }
            sb.append("</tbody></table>\n");
        }

        // Section 6: Pedestrian Network & Bottlenecks
        sb.append("<h2>6. Pedestrian Movement Network &amp; Bottleneck Screening</h2>\n");
        NetworkDtos.NetworkSummaryDto nsum = bundle.networkWorkspace().summary();
        sb.append("<p class=\"summary-line mono\">Nodes: ").append(nsum.totalNodes())
                .append(" | Corridors: ").append(nsum.totalEdges())
                .append(" | Total Length: ").append(fmt1(nsum.totalLengthM())).append(" m")
                .append(" | Active Bottleneck Findings: ").append(nsum.openFindingsCount()).append("</p>\n");
        if (!bundle.networkWorkspace().activeFindings().isEmpty()) {
            sb.append("<table class=\"data-table\"><thead><tr><th>Severity</th><th>Finding</th><th>Measured</th><th>Review</th></tr></thead><tbody>\n");
            for (NetworkDtos.BottleneckFindingDto bf : bundle.networkWorkspace().activeFindings()) {
                sb.append("<tr><td><span class=\"sev-").append(bf.severity().name().toLowerCase(Locale.ROOT)).append("\">")
                        .append(bf.severity().name()).append("</span></td>")
                        .append("<td><strong>").append(esc(bf.title())).append("</strong><br/>").append(esc(bf.description())).append("</td>")
                        .append("<td class=\"mono\">").append(esc(safe(bf.measuredValue()))).append("</td>")
                        .append("<td>").append(esc(bf.reviewStatusName())).append("</td></tr>\n");
            }
            sb.append("</tbody></table>\n");
        }

        // Section 7: Arrival Waves & Staff Coverage
        sb.append("<h2>7. Arrival Wave Queue Analysis &amp; Staff Coverage</h2>\n");
        OperationsDtos.QueueProjectionResult qp = bundle.operationsWorkspace().queueProjection();
        sb.append("<p class=\"summary-line mono\">Total Wave Arrivals: ").append(qp.totalExpectedArrivals())
                .append(" | Entry Rate: ").append(fmt1(qp.totalActiveEntryRatePerMin())).append(" p/min")
                .append(" | Peak Queue: ").append(qp.peakQueueLength())
                .append(" | Overflow Intervals: ").append(qp.overflowIntervalCount()).append("</p>\n");
        if (!qp.intervals().isEmpty()) {
            sb.append("<table class=\"data-table\"><thead><tr><th>Window</th><th class=\"num\">Arrivals</th><th class=\"num\">Gate Cap</th><th class=\"num\">End Queue</th><th class=\"num\">Est Wait</th><th>Status</th></tr></thead><tbody>\n");
            for (OperationsDtos.QueueIntervalRowDto row : qp.intervals()) {
                sb.append("<tr><td class=\"mono\">").append(esc(row.clockWindowLabel())).append("</td>")
                        .append("<td class=\"num mono\">").append(row.expectedArrivals()).append("</td>")
                        .append("<td class=\"num mono\">").append(row.gateCapacity()).append("</td>")
                        .append("<td class=\"num mono\">").append(row.endingQueue()).append("</td>")
                        .append("<td class=\"num mono\">").append(row.estimatedWaitMinutes() != null ? fmt1(row.estimatedWaitMinutes()) + " min" : "Unbounded").append("</td>")
                        .append("<td>").append(row.overflow() ? "OVERFLOW (" + row.overflowPersons() + ")" : "Within Holding").append("</td></tr>\n");
            }
            sb.append("</tbody></table>\n");
        }
        OperationsDtos.StaffCoverageResult sc = bundle.operationsWorkspace().staffCoverage();
        sb.append("<p class=\"summary-line mono\">Staff Roster: ").append(sc.totalStaffCount())
                .append(" (Security: ").append(sc.securityCount())
                .append(", Volunteers: ").append(sc.volunteerCount())
                .append(", Medical: ").append(sc.medicalCount())
                .append(", Supervisors: ").append(sc.supervisorCount())
                .append(") | Shifts: ").append(sc.assignmentCount())
                .append(" | Coverage Warnings: ").append(sc.totalFindingCount()).append("</p>\n");

        // Section 8: Emergency Preparedness
        sb.append("<h2>8. Emergency Preparedness, Contacts &amp; Outage Baseline</h2>\n");
        sb.append("<div class=\"banner banner-warn\"><strong>Authority Deference Notice:</strong> ")
                .append(esc(ReportDtos.EMERGENCY_DEFERENCE_NOTICE)).append("</div>\n");
        EmergencyDtos.EmergencyValidationSummary evs = bundle.emergencyWorkspace().validation();
        sb.append("<p class=\"summary-line mono\">Vehicle Routes (&gt;=3.50 m): ").append(evs.compliantVehicleRoutes()).append(" / ").append(evs.totalVehicleRoutes())
                .append(" | Emergency Exits: ").append(evs.emergencyExitCount())
                .append(" | Assembly Points: ").append(evs.assemblyPointCount())
                .append(" | Baseline Egress Width: ").append(fmt2(bundle.emergencyWorkspace().baselineOutage().baselineTotalEgressWidthM())).append(" m</p>\n");
        if (!bundle.emergencyWorkspace().contacts().isEmpty()) {
            sb.append("<table class=\"data-table\"><thead><tr><th>Priority</th><th>Agency</th><th>Contact</th><th>Role</th><th>Phone</th></tr></thead><tbody>\n");
            for (EmergencyDtos.EmergencyContactDto c : bundle.emergencyWorkspace().contacts()) {
                sb.append("<tr><td class=\"mono\">P").append(c.priorityOrder()).append("</td>")
                        .append("<td>").append(esc(c.agencyName())).append("</td>")
                        .append("<td>").append(esc(c.contactName())).append("</td>")
                        .append("<td>").append(esc(c.role())).append("</td>")
                        .append("<td class=\"mono\">").append(esc(c.phone())).append("</td></tr>\n");
            }
            sb.append("</tbody></table>\n");
        }

        // Section 9: Before-and-After Crowd Simulation Comparison
        sb.append("<h2>9. Crowd Simulation &amp; Before-and-After Comparison</h2>\n");
        sb.append("<div class=\"banner banner-warn\"><strong>Simplified Planning Simulation Notice:</strong> ")
                .append(esc(ReportDtos.SIMULATION_DISCLAIMER)).append("</div>\n");
        SimulationDtos.SimulationComparisonDto cmp = bundle.featuredComparison();
        if (cmp != null && comparisonPng != null) {
            sb.append("<div class=\"img-box\"><img class=\"snapshot-img\" alt=\"Before and After Comparison\" src=\"")
                    .append(canvasRenderer.toBase64DataUri(comparisonPng)).append("\" /></div>\n");
            sb.append("<table class=\"data-table\"><thead><tr><th>Simulation Metric</th><th class=\"num\">Before (V")
                    .append(cmp.beforeRun().snapshotVersion()).append(")</th><th class=\"num\">After (V")
                    .append(cmp.afterRun().snapshotVersion()).append(")</th><th class=\"num\">Delta</th><th>Trend</th></tr></thead><tbody>\n");
            rowKpi(sb, cmp.peakDensity());
            rowKpi(sb, cmp.peakQueueLength());
            rowKpi(sb, cmp.maxQueueWaitTime());
            rowKpi(sb, cmp.congestionDuration());
            rowKpi(sb, cmp.avgTravelTime());
            rowKpi(sb, cmp.p95TravelTime());
            rowKpi(sb, cmp.unservedBlockedAgents());
            sb.append("</tbody></table>\n");
        } else if (!bundle.simulationWorkspace().runs().isEmpty()) {
            SimulationDtos.SimulationRunSummaryDto latest = bundle.simulationWorkspace().runs().get(0);
            sb.append("<p class=\"summary-line mono\">Latest Simulation Run: ").append(esc(latest.label()))
                    .append(" (").append(esc(latest.scenarioDisplayName())).append(", Seed ").append(latest.randomSeed())
                    .append(") | Peak Density: ").append(fmt2(latest.metrics().peakLocalDensityPsm()))
                    .append(" p/m&#178; | Peak Queue: ").append(latest.metrics().peakQueueLength())
                    .append(" | P95 Clearance: ").append(fmt1(latest.metrics().p95TravelClearanceTimeSec())).append(" s</p>\n");
        } else {
            sb.append("<p class=\"muted\">No crowd simulation runs have been executed for this event yet.</p>\n");
        }

        // Section 10: Live Occupancy Logs & Incident Summary
        sb.append("<h2>10. Live Occupancy Telemetry &amp; Incident Summary</h2>\n");
        sb.append("<div class=\"banner banner-info\"><strong>Source Integrity Notice:</strong> ")
                .append(esc(ReportDtos.LIVE_TELEMETRY_NOTICE)).append("</div>\n");
        MonitoringDtos.ChannelSummaryDto manCh = bundle.liveManualView().manualSummary();
        MonitoringDtos.ChannelSummaryDto passCh = bundle.livePassView().passScanSummary();
        MonitoringDtos.ChannelSummaryDto pracCh = bundle.practiceView().practiceSummary();
        sb.append("<table class=\"data-table\"><thead><tr><th>Independent Channel</th><th class=\"num\">Reported Total</th><th>Last Updated</th><th>Status</th></tr></thead><tbody>\n");
        rowChannel(sb, "Manual Staff Counts (MANUAL_STAFF)", manCh);
        rowChannel(sb, "Attendee Pass Scans (PASS_SCAN)", passCh);
        rowChannel(sb, "Practice Simulation (PRACTICE_SIMULATION)", pracCh);
        sb.append("</tbody></table>\n");

        List<OperationsDtos.IncidentLogDto> incidents = bundle.operationsWorkspace().incidents();
        if (!incidents.isEmpty()) {
            sb.append("<h3>Operational Incident Log</h3>\n<table class=\"data-table\"><thead><tr><th>Time</th><th>Severity</th><th>Zone</th><th>Description &amp; Response</th><th>Status</th></tr></thead><tbody>\n");
            for (OperationsDtos.IncidentLogDto inc : incidents) {
                sb.append("<tr><td class=\"mono\">").append(formatInstant(inc.reportedAt())).append("</td>")
                        .append("<td>").append(inc.severity().name()).append("</td>")
                        .append("<td>").append(esc(inc.zoneName() != null ? inc.zoneName() : "Venue-Wide")).append("</td>")
                        .append("<td>").append(esc(inc.description()))
                        .append(inc.responseTaken() != null ? "<br/><em>Response:</em> " + esc(inc.responseTaken()) : "")
                        .append("</td><td>").append(esc(inc.statusDisplayName())).append("</td></tr>\n");
            }
            sb.append("</tbody></table>\n");
        }

        // Checklist Appendix summary
        if (!bundle.checklist().isEmpty()) {
            sb.append("<h2>11. Planning Checklist Summary</h2>\n<table class=\"data-table\"><thead><tr><th>Section</th><th>Checklist Item</th><th>Status</th><th>Reviewer Sign-Off</th></tr></thead><tbody>\n");
            for (EventChecklistItem item : bundle.checklist()) {
                sb.append("<tr><td>").append(esc(item.getSectionName())).append("</td>")
                        .append("<td>").append(esc(item.getTitle())).append("</td>")
                        .append("<td>").append(esc(item.getStatus().getDisplayName())).append("</td>")
                        .append("<td>").append(item.isReviewerSignedOff()
                                ? "Signed (" + esc(item.getSignedOffBy() != null ? item.getSignedOffBy().getFullName() : "Reviewer") + ")"
                                : "Pending").append("</td></tr>\n");
            }
            sb.append("</tbody></table>\n");
        }

        sb.append("</body>\n</html>");
        return sb.toString();
    }

    private String buildPrintCss() {
        return """
                @page { size: A4 portrait; margin: 13mm; }
                body { font-family: sans-serif; font-size: 9.5pt; line-height: 1.45; color: #0F172A; margin: 0; }
                .report-header { border-bottom: 2px solid #0F172A; padding-bottom: 8px; margin-bottom: 10px; }
                .brand-line { font-size: 8pt; font-weight: bold; letter-spacing: 0.08em; color: #475569; }
                h1 { font-size: 16pt; margin: 4px 0; color: #0F172A; }
                h2 { font-size: 11.5pt; margin: 14px 0 6px 0; padding-bottom: 3px; border-bottom: 1px solid #CBD5E1; color: #0F172A; page-break-after: avoid; }
                h3 { font-size: 10pt; margin: 10px 0 4px 0; color: #334155; page-break-after: avoid; }
                .meta-line { font-size: 8.5pt; color: #475569; }
                .summary-line { font-size: 8.5pt; background: #F1F5F9; padding: 5px 8px; border: 1px solid #E2E8F0; margin: 4px 0 8px 0; }
                .mono { font-family: monospace; }
                .muted { color: #64748B; font-style: italic; }
                .banner { padding: 7px 10px; margin: 8px 0; font-size: 8.5pt; border: 1px solid #E2E8F0; border-left: 4px solid #0284C7; background: #F0F9FF; page-break-inside: avoid; }
                .banner-warn { border-left-color: #B45309; background: #FFFBEB; }
                table { width: 100%; border-collapse: collapse; margin: 6px 0 10px 0; font-size: 8.5pt; }
                th, td { border: 1px solid #E2E8F0; padding: 4px 6px; text-align: left; vertical-align: top; }
                th { background: #F8FAFC; font-weight: bold; color: #334155; }
                .kv-table th { width: 28%; background: #F8FAFC; }
                .num { text-align: right; }
                tr { page-break-inside: avoid; }
                .img-box { border: 1px solid #CBD5E1; padding: 4px; background: #FFFFFF; margin: 6px 0 10px 0; page-break-inside: avoid; }
                .snapshot-img { width: 100%; height: auto; display: block; }
                .sev-critical { color: #B91C1C; font-weight: bold; }
                .sev-high { color: #C2410C; font-weight: bold; }
                .sev-low { color: #B45309; font-weight: bold; }
                .sev-info { color: #0284C7; font-weight: bold; }
                """;
    }

    private void rowKv(StringBuilder sb, String key, String value) {
        sb.append("<tr><th>").append(esc(key)).append("</th><td>").append(esc(safe(value))).append("</td></tr>\n");
    }

    private void rowArea(StringBuilder sb, String label, double sqm, double pct) {
        sb.append("<tr><td>").append(label).append("</td><td class=\"num mono\">")
                .append(fmt2(sqm)).append("</td><td class=\"num mono\">")
                .append(fmt1(pct)).append("%</td></tr>\n");
    }

    private void rowKpi(StringBuilder sb, SimulationDtos.KpiDelta kpi) {
        sb.append("<tr><td>").append(esc(kpi.label())).append(" (").append(esc(kpi.unit())).append(")</td>")
                .append("<td class=\"num mono\">").append(fmt2(kpi.beforeValue())).append("</td>")
                .append("<td class=\"num mono\">").append(fmt2(kpi.afterValue())).append("</td>")
                .append("<td class=\"num mono\">").append(String.format(Locale.US, "%+.2f", kpi.delta())).append("</td>")
                .append("<td>").append(esc(kpi.trend())).append("</td></tr>\n");
    }

    private void rowChannel(StringBuilder sb, String label, MonitoringDtos.ChannelSummaryDto ch) {
        sb.append("<tr><td>").append(esc(label)).append("</td>")
                .append("<td class=\"num mono\">").append(ch.totalCount()).append("</td>")
                .append("<td class=\"mono\">").append(formatInstant(ch.lastUpdatedAt())).append("</td>")
                .append("<td>").append(ch.lastUpdatedAt() == null ? "No readings" : (ch.stale() ? "STALE" : "Current")).append("</td></tr>\n");
    }

    private String formatInstant(Instant instant) {
        return instant != null ? TS_FMT.format(instant) : "—";
    }

    private String intStr(Integer val) {
        return val != null ? String.valueOf(val) : "—";
    }

    private String fmt1(double val) {
        return String.format(Locale.US, "%.1f", val);
    }

    private String fmt2(double val) {
        return String.format(Locale.US, "%.2f", val);
    }

    private String safe(String v) {
        return v == null || v.isBlank() ? "—" : v;
    }

    private String esc(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
