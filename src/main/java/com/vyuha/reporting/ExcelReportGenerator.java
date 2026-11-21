package com.vyuha.reporting;

import com.vyuha.capacity.CapacityDtos;
import com.vyuha.emergency.EmergencyDtos;
import com.vyuha.event.Event;
import com.vyuha.event.SafetyReviewRecord;
import com.vyuha.grid.GridDtos;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.monitoring.MonitoringDtos;
import com.vyuha.network.NetworkDtos;
import com.vyuha.operations.OperationsDtos;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.venue.AreaBreakdownResult;
import com.vyuha.venue.VenueDtos;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Generates multi-sheet Apache POI .xlsx workbooks covering all Vyuha planning and operations modules.
 */
@Component
public class ExcelReportGenerator {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public byte[] generateWorkbook(ReportDtos.EventReportBundle bundle) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = createStyles(wb);

            buildEventSummarySheet(wb, styles, bundle);
            buildAreaBreakdownSheet(wb, styles, bundle);
            buildCapacityAssumptionsSheet(wb, styles, bundle);
            buildZonesSheet(wb, styles, bundle);
            buildInfrastructureSheet(wb, styles, bundle);
            buildConflictsSheet(wb, styles, bundle);
            buildBottlenecksSheet(wb, styles, bundle);
            buildArrivalWavesSheet(wb, styles, bundle);
            buildStaffAssignmentsSheet(wb, styles, bundle);
            buildLiveOccupancySheet(wb, styles, bundle);
            buildSimulationMetricsSheet(wb, styles, bundle);
            buildEmergencyPlanSheet(wb, styles, bundle);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to generate XLSX workbook.", ex);
        }
    }

    private void buildEventSummarySheet(Workbook wb, Styles s, ReportDtos.EventReportBundle bundle) {
        Sheet sheet = wb.createSheet("Event Summary");
        Event ev = bundle.event();
        int r = 0;
        r = writeHeader(sheet, s, r, "Field", "Value");
        r = writeRow(sheet, s, r, "Decision-Support Notice", ReportDtos.DECISION_SUPPORT_NOTICE);
        r = writeRow(sheet, s, r, "Event ID", ev.getId());
        r = writeRow(sheet, s, r, "Event Name", ev.getName());
        r = writeRow(sheet, s, r, "Category", ev.getCategory().getDisplayName());
        r = writeRow(sheet, s, r, "Lifecycle Status", ev.getStatus().getDisplayName());
        r = writeRow(sheet, s, r, "Location", safe(ev.getLocationName()));
        r = writeRow(sheet, s, r, "Schedule", (ev.getStartDateTime() != null ? LOCAL_FMT.format(ev.getStartDateTime()) : "TBD")
                + " to " + (ev.getEndDateTime() != null ? LOCAL_FMT.format(ev.getEndDateTime()) : "TBD"));
        r = writeRow(sheet, s, r, "Expected Attendance", ev.getExpectedAttendance());
        r = writeRow(sheet, s, r, "Organizer", ev.getOrganizer() != null ? ev.getOrganizer().getFullName() : "—");
        r = writeRow(sheet, s, r, "Assigned Safety Reviewer", ev.getAssignedReviewer() != null ? ev.getAssignedReviewer().getFullName() : "Unassigned");
        r = writeRow(sheet, s, r, "Latest Safety Review Status",
                bundle.latestSafetyReviewStatus() != null ? bundle.latestSafetyReviewStatus().getDisplayName() : "Review Pending");
        r = writeRow(sheet, s, r, "Checklist Progress",
                bundle.checklistProgress().completedCount() + " / " + bundle.checklistProgress().applicableItems()
                        + " (" + bundle.checklistProgress().completionPct() + "%)");
        r = writeRow(sheet, s, r, "Capacity Review Status",
                bundle.capacityView().calculation().effectiveReviewStatus().getDisplayName());
        r = writeRow(sheet, s, r, "Preliminary Capacity Ceiling",
                bundle.capacityView().calculation().preliminaryCapacity());
        r = writeRow(sheet, s, r, "Governing Constraint",
                bundle.capacityView().calculation().governingConstraint() != null
                        ? bundle.capacityView().calculation().governingConstraint().getDisplayName() : "—");
        r = writeRow(sheet, s, r, "Generated At", formatInstant(bundle.generatedAt()));

        if (!bundle.safetyReviews().isEmpty()) {
            r++;
            r = writeHeader(sheet, s, r, "Reviewed At", "Reviewer", "Status", "Findings Summary", "Organizer Response");
            for (SafetyReviewRecord rec : bundle.safetyReviews()) {
                r = writeRow(sheet, s, r, formatInstant(rec.getReviewedAt()), rec.getReviewer().getFullName(),
                        rec.getReviewStatus().getDisplayName(), rec.getFindingsSummary(), safe(rec.getOrganizerResponse()));
            }
        }
        setWidths(sheet, 28, 52, 24, 48, 42);
    }

    private void buildAreaBreakdownSheet(Workbook wb, Styles s, ReportDtos.EventReportBundle bundle) {
        Sheet sheet = wb.createSheet("Area Breakdown");
        AreaBreakdownResult area = bundle.venueWorkspace().areaBreakdown();
        int r = 0;
        r = writeHeader(sheet, s, r, "Metric", "Area (m²)", "Percent of Gross");
        r = writeRow(sheet, s, r, "Gross Site Area", area.grossAreaSqm(), 100.0);
        r = writeRow(sheet, s, r, "Reserved Walkways / Corridors", area.corridorsAreaSqm(), area.corridorsPercent());
        r = writeRow(sheet, s, r, "Buildings, Stages & Restricted Exclusions", area.restrictedAreaSqm(), area.restrictedPercent());
        r = writeRow(sheet, s, r, "Total Excluded (JTS Deduplicated Union)", area.excludedTotalAreaSqm(), area.excludedPercent());
        r = writeRow(sheet, s, r, "Overlap Deduplicated (Prevented Double-Count)", area.overlapDeduplicatedSqm(), 0.0);
        r = writeRow(sheet, s, r, "Net Usable Gathering Area", area.usableAreaSqm(), area.usablePercent());

        if (!bundle.venueWorkspace().exclusions().isEmpty()) {
            r++;
            r = writeHeader(sheet, s, r, "Exclusion Label", "Type", "Clipped Area (m²)", "Outside Boundary (m²)", "Notes");
            for (VenueDtos.ExclusionAreaDto ex : bundle.venueWorkspace().exclusions()) {
                r = writeRow(sheet, s, r, ex.label(), ex.exclusionTypeName(), ex.clippedAreaSqm(), ex.outsideBoundarySqm(), safe(ex.notes()));
            }
        }
        setWidths(sheet, 38, 22, 20, 22, 40);
    }

    private void buildCapacityAssumptionsSheet(Workbook wb, Styles s, ReportDtos.EventReportBundle bundle) {
        Sheet sheet = wb.createSheet("Capacity Assumptions");
        CapacityDtos.CapacityCalculationResult cap = bundle.capacityView().calculation();
        int r = 0;
        r = writeHeader(sheet, s, r, "Capacity Ceiling", "Value (persons)", "Notes");
        r = writeRow(sheet, s, r, "Usable Standing Space (C_space)", cap.usableSpaceCapacity(), "Sum of zone usable area * reviewed density");
        r = writeRow(sheet, s, r, "Entry/Exit Flow (C_flow)", cap.flowCapacity(), "Min(entry flow, exit flow)");
        r = writeRow(sheet, s, r, "Holding / Queue Space (C_holding)", cap.holdingSpaceCapacity(), "Holding zone capacity + buffer");
        r = writeRow(sheet, s, r, "Official Venue Limit (C_official)", cap.officialVenueLimit(), safe(bundle.capacityView().parameters().officialLimitSource()));
        r = writeRow(sheet, s, r, "Preliminary Capacity Ceiling", cap.preliminaryCapacity(), cap.governingExplanation());

        r++;
        r = writeHeader(sheet, s, r, "Zone Code", "Zone Name", "Type", "Usable m²", "Density (p/m²)", "Computed Cap", "Source Citation", "Approved By", "Review Status");
        for (CapacityDtos.ZoneCapacityRow zr : cap.zoneRows()) {
            r = writeRow(sheet, s, r, zr.zoneCode(), zr.name(), zr.zoneTypeName(), zr.usableZoneAreaSqm(),
                    zr.densityPersonsPerSqm(), zr.computedCapacity(), safe(zr.sourceCitation()),
                    safe(zr.approvedByName()), zr.reviewStatus() != null ? zr.reviewStatus().getDisplayName() : "Pending");
        }
        setWidths(sheet, 16, 26, 20, 14, 15, 14, 34, 24, 20);
    }

    private void buildZonesSheet(Workbook wb, Styles s, ReportDtos.EventReportBundle bundle) {
        Sheet sheet = wb.createSheet("Zones");
        int r = 0;
        r = writeHeader(sheet, s, r, "Zone Code", "Name", "Type", "Walkable", "Clipped m²", "Usable m²", "Isolated", "Notes");
        for (GridDtos.FunctionalZoneDto z : bundle.gridWorkspace().zones()) {
            r = writeRow(sheet, s, r, z.zoneCode(), z.name(), z.zoneTypeName(), z.walkable(),
                    z.clippedAreaSqm(), z.usableAreaSqm(), z.isolated(), safe(z.notes()));
        }
        setWidths(sheet, 14, 28, 22, 12, 14, 14, 12, 36);
    }

    private void buildInfrastructureSheet(Workbook wb, Styles s, ReportDtos.EventReportBundle bundle) {
        Sheet sheet = wb.createSheet("Infrastructure");
        int r = 0;
        r = writeHeader(sheet, s, r, "ID", "Label", "Type", "Center X (m)", "Center Y (m)", "Width (m)", "Height (m)", "Rotation (°)", "Throughput (p/min)", "Active", "Notes");
        for (InfrastructureDtos.InfrastructureElementDto el : bundle.infraWorkspace().elements()) {
            r = writeRow(sheet, s, r, el.id(), el.label(), el.elementTypeName(), el.centerX(), el.centerY(),
                    el.widthM(), el.heightM(), el.rotationDeg(), el.throughputPersonsPerMin(), el.active(), safe(el.notes()));
        }
        setWidths(sheet, 10, 28, 22, 13, 13, 12, 12, 13, 18, 10, 32);
    }

    private void buildConflictsSheet(Workbook wb, Styles s, ReportDtos.EventReportBundle bundle) {
        Sheet sheet = wb.createSheet("Conflicts");
        int r = 0;
        r = writeHeader(sheet, s, r, "ID", "Severity", "Type", "Title", "Measured", "Location X", "Location Y", "Review Status", "Description");
        for (InfrastructureDtos.ConflictFindingDto cf : bundle.infraWorkspace().activeFindings()) {
            r = writeRow(sheet, s, r, cf.id(), cf.severity().name(), cf.conflictTypeName(), cf.title(),
                    safe(cf.measuredValue()), cf.locationX(), cf.locationY(), cf.reviewStatusName(), cf.description());
        }
        setWidths(sheet, 10, 12, 26, 34, 20, 12, 12, 18, 48);
    }

    private void buildBottlenecksSheet(Workbook wb, Styles s, ReportDtos.EventReportBundle bundle) {
        Sheet sheet = wb.createSheet("Bottlenecks");
        int r = 0;
        r = writeHeader(sheet, s, r, "ID", "Severity", "Type", "Title", "Measured", "Threshold", "Review Status", "Description");
        for (NetworkDtos.BottleneckFindingDto bf : bundle.networkWorkspace().activeFindings()) {
            r = writeRow(sheet, s, r, bf.id(), bf.severity().name(), bf.findingTypeName(), bf.title(),
                    safe(bf.measuredValue()), safe(bf.thresholdNote()), bf.reviewStatusName(), bf.description());
        }
        setWidths(sheet, 10, 12, 26, 34, 22, 26, 18, 48);
    }

    private void buildArrivalWavesSheet(Workbook wb, Styles s, ReportDtos.EventReportBundle bundle) {
        Sheet sheet = wb.createSheet("Arrival Waves");
        int r = 0;
        r = writeHeader(sheet, s, r, "Window", "Label", "Target Gate", "Expected Arrivals", "Gate Capacity", "Opening Queue", "Ending Queue", "Est Wait (min)", "Overflow", "Overflow Persons");
        for (OperationsDtos.QueueIntervalRowDto row : bundle.operationsWorkspace().queueProjection().intervals()) {
            r = writeRow(sheet, s, r, row.clockWindowLabel(), safe(row.label()), safe(row.targetGateLabel()),
                    row.expectedArrivals(), row.gateCapacity(), row.openingQueue(), row.endingQueue(),
                    row.estimatedWaitMinutes(), row.overflow(), row.overflowPersons());
        }
        setWidths(sheet, 18, 26, 22, 16, 14, 14, 14, 14, 11, 16);
    }

    private void buildStaffAssignmentsSheet(Workbook wb, Styles s, ReportDtos.EventReportBundle bundle) {
        Sheet sheet = wb.createSheet("Staff Assignments");
        int r = 0;
        r = writeHeader(sheet, s, r, "Staff Name", "Role", "Location / Post", "Shift Window", "Duration (min)", "Notes");
        for (OperationsDtos.StaffAssignmentDto a : bundle.operationsWorkspace().assignments()) {
            r = writeRow(sheet, s, r, a.staffName(), a.roleDisplayName(), a.locationSummary(),
                    a.shiftWindowLabel(), a.durationMinutes(), safe(a.notes()));
        }
        setWidths(sheet, 24, 16, 28, 28, 15, 36);
    }

    private void buildLiveOccupancySheet(Workbook wb, Styles s, ReportDtos.EventReportBundle bundle) {
        Sheet sheet = wb.createSheet("Live Occupancy Logs");
        int r = 0;
        r = writeRow(sheet, s, r, "Source Segregation Notice", ReportDtos.LIVE_TELEMETRY_NOTICE);
        r++;
        r = writeHeader(sheet, s, r, "Data Source", "Zone", "Gate", "Count", "Recorded By", "Recorded At", "Note");
        for (MonitoringDtos.OccupancyReadingDto rd : bundle.liveManualView().recentReadings()) {
            r = writeRow(sheet, s, r, rd.dataSource().name(), safe(rd.zoneName()), safe(rd.gateLabel()),
                    rd.countValue(), rd.recordedBy(), formatInstant(rd.recordedAt()), safe(rd.note()));
        }
        for (MonitoringDtos.OccupancyReadingDto rd : bundle.livePassView().recentReadings()) {
            r = writeRow(sheet, s, r, rd.dataSource().name(), safe(rd.zoneName()), safe(rd.gateLabel()),
                    rd.countValue(), rd.recordedBy(), formatInstant(rd.recordedAt()), safe(rd.note()));
        }
        for (MonitoringDtos.OccupancyReadingDto rd : bundle.practiceView().recentReadings()) {
            r = writeRow(sheet, s, r, rd.dataSource().name(), safe(rd.zoneName()), safe(rd.gateLabel()),
                    rd.countValue(), rd.recordedBy(), formatInstant(rd.recordedAt()), safe(rd.note()));
        }
        setWidths(sheet, 22, 24, 22, 12, 22, 24, 34);
    }

    private void buildSimulationMetricsSheet(Workbook wb, Styles s, ReportDtos.EventReportBundle bundle) {
        Sheet sheet = wb.createSheet("Simulation Metrics");
        int r = 0;
        r = writeRow(sheet, s, r, "Disclaimer", ReportDtos.SIMULATION_DISCLAIMER);
        r++;
        r = writeHeader(sheet, s, r, "Run ID", "Snapshot", "Run Label", "Scenario", "Seed", "Agents", "Duration (s)",
                "Peak Density (p/m²)", "Peak Queue", "Max Wait (s)", "Avg Clearance (s)", "P95 Clearance (s)", "Clearance %", "Unserved");
        for (SimulationDtos.SimulationRunSummaryDto run : bundle.simulationWorkspace().runs()) {
            SimulationDtos.SimulationMetricsSummary m = run.metrics();
            r = writeRow(sheet, s, r, run.id(), "V" + run.snapshotVersion() + " " + run.snapshotLabel(),
                    run.label(), run.scenarioDisplayName(), run.randomSeed(), run.agentCount(), run.durationSeconds(),
                    m.peakLocalDensityPsm(), m.peakQueueLength(), m.maxQueueWaitTimeSec(),
                    m.avgTravelClearanceTimeSec(), m.p95TravelClearanceTimeSec(), m.clearanceRatePct(), m.unservedBlockedAgentCount());
        }
        setWidths(sheet, 10, 26, 28, 22, 12, 10, 13, 18, 12, 13, 16, 16, 13, 12);
    }

    private void buildEmergencyPlanSheet(Workbook wb, Styles s, ReportDtos.EventReportBundle bundle) {
        Sheet sheet = wb.createSheet("Emergency Plan");
        int r = 0;
        r = writeRow(sheet, s, r, "Authority Deference Notice", ReportDtos.EMERGENCY_DEFERENCE_NOTICE);
        r++;
        r = writeHeader(sheet, s, r, "Priority", "Agency", "Contact", "Role", "Phone", "Notes");
        for (EmergencyDtos.EmergencyContactDto c : bundle.emergencyWorkspace().contacts()) {
            r = writeRow(sheet, s, r, c.priorityOrder(), c.agencyName(), c.contactName(), c.role(), c.phone(), safe(c.notes()));
        }
        r++;
        r = writeHeader(sheet, s, r, "Emergency Asset", "Type", "Clear Width (m)", "Compliant (>=3.5m)", "Capacity Notes");
        for (EmergencyDtos.EmergencyAssetDto a : bundle.emergencyWorkspace().assets()) {
            r = writeRow(sheet, s, r, a.label(), a.assetTypeName(), a.minWidthM(),
                    a.vehicleRoute() ? (a.minWidthM() != null && a.minWidthM() >= 3.5) : "N/A", safe(a.capacityNotes()));
        }
        setWidths(sheet, 26, 24, 22, 22, 18, 36);
    }

    private int writeHeader(Sheet sheet, Styles s, int rowIdx, String... cols) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < cols.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(s.headerStyle);
        }
        return rowIdx + 1;
    }

    private int writeRow(Sheet sheet, Styles s, int rowIdx, Object... values) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < values.length; i++) {
            Cell c = row.createCell(i);
            Object v = values[i];
            if (v == null) {
                c.setCellValue("—");
            } else if (v instanceof Number num) {
                c.setCellValue(num.doubleValue());
            } else if (v instanceof Boolean b) {
                c.setCellValue(b ? "Yes" : "No");
            } else {
                c.setCellValue(String.valueOf(v));
            }
            c.setCellStyle(s.bodyStyle);
        }
        return rowIdx + 1;
    }

    private void setWidths(Sheet sheet, int... charWidths) {
        for (int i = 0; i < charWidths.length; i++) {
            sheet.setColumnWidth(i, Math.min(255 * 256, charWidths[i] * 256));
        }
    }

    private Styles createStyles(Workbook wb) {
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());

        CellStyle headerStyle = wb.createCellStyle();
        headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setFont(headerFont);
        headerStyle.setBorderBottom(BorderStyle.THIN);

        CellStyle bodyStyle = wb.createCellStyle();
        bodyStyle.setWrapText(false);
        return new Styles(headerStyle, bodyStyle);
    }

    private String formatInstant(Instant instant) {
        return instant != null ? TS_FMT.format(instant) : "—";
    }

    private String safe(String v) {
        return v == null || v.isBlank() ? "—" : v;
    }

    private record Styles(CellStyle headerStyle, CellStyle bodyStyle) {
    }
}
