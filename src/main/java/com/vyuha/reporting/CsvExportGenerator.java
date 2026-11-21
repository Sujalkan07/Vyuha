package com.vyuha.reporting;

import com.vyuha.capacity.CapacityDtos;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.monitoring.MonitoringDtos;
import com.vyuha.network.NetworkDtos;
import com.vyuha.operations.OperationsDtos;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.venue.AreaBreakdownResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * RFC-4180 compliant CSV exporter supporting modular Vyuha planning & telemetry datasets.
 */
@Component
public class CsvExportGenerator {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    public static final List<ReportDtos.CsvDatasetOption> DATASET_OPTIONS = List.of(
            new ReportDtos.CsvDatasetOption("conflicts-bottlenecks", "Conflicts & Bottlenecks",
                    "All active spatial conflicts and pedestrian network bottleneck findings"),
            new ReportDtos.CsvDatasetOption("area-capacity", "Area & Capacity Review",
                    "Usable area breakdown and zone-by-zone capacity assumptions"),
            new ReportDtos.CsvDatasetOption("arrivals-staff", "Arrival Waves & Queues",
                    "Arrival intervals, gate capacities, ending queues, and overflow deficits"),
            new ReportDtos.CsvDatasetOption("occupancy-logs", "Live Occupancy Logs",
                    "Strictly source-tagged MANUAL_STAFF, PASS_SCAN, and PRACTICE_SIMULATION readings"),
            new ReportDtos.CsvDatasetOption("simulation-runs", "Simulation Runs",
                    "All crowd simulation runs and Before-and-After KPI metrics")
    );

    public byte[] generateCsv(ReportDtos.EventReportBundle bundle, String requestedScope) {
        String scope = normalizeScope(requestedScope);
        StringBuilder sb = new StringBuilder(4096);

        switch (scope) {
            case "area-capacity" -> appendAreaAndCapacityCsv(sb, bundle);
            case "arrivals-staff" -> appendArrivalsCsv(sb, bundle);
            case "occupancy-logs" -> appendOccupancyCsv(sb, bundle);
            case "simulation-runs" -> appendSimulationCsv(sb, bundle);
            default -> appendConflictsAndBottlenecksCsv(sb, bundle);
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public String normalizeScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return "conflicts-bottlenecks";
        }
        String norm = raw.trim().toLowerCase(Locale.ROOT);
        for (ReportDtos.CsvDatasetOption opt : DATASET_OPTIONS) {
            if (opt.key().equals(norm)) {
                return norm;
            }
        }
        return "conflicts-bottlenecks";
    }

    private void appendConflictsAndBottlenecksCsv(StringBuilder sb, ReportDtos.EventReportBundle bundle) {
        row(sb, "Category", "Finding ID", "Severity", "Type", "Title", "Measured Value", "Review Status", "Description");
        for (InfrastructureDtos.ConflictFindingDto cf : bundle.infraWorkspace().activeFindings()) {
            row(sb, "SPATIAL_CONFLICT", cf.id(), cf.severity().name(), cf.conflictTypeName(),
                    cf.title(), cf.measuredValue(), cf.reviewStatusName(), cf.description());
        }
        for (NetworkDtos.BottleneckFindingDto bf : bundle.networkWorkspace().activeFindings()) {
            row(sb, "NETWORK_BOTTLENECK", bf.id(), bf.severity().name(), bf.findingTypeName(),
                    bf.title(), bf.measuredValue(), bf.reviewStatusName(), bf.description());
        }
    }

    private void appendAreaAndCapacityCsv(StringBuilder sb, ReportDtos.EventReportBundle bundle) {
        AreaBreakdownResult area = bundle.venueWorkspace().areaBreakdown();
        CapacityDtos.CapacityCalculationResult cap = bundle.capacityView().calculation();
        row(sb, "Section", "Code", "Name", "Type", "AreaSqm", "DensityPsm", "ComputedCap", "Citation", "ReviewStatus");
        row(sb, "AREA_SUMMARY", "GROSS", "Gross Site Area", "SITE", area.grossAreaSqm(), "", "", "", "");
        row(sb, "AREA_SUMMARY", "CORRIDOR", "Reserved Corridors", "EXCLUSION", area.corridorsAreaSqm(), "", "", "", "");
        row(sb, "AREA_SUMMARY", "RESTRICTED", "Restricted Exclusions", "EXCLUSION", area.restrictedAreaSqm(), "", "", "", "");
        row(sb, "AREA_SUMMARY", "USABLE", "Net Usable Gathering Area", "USABLE", area.usableAreaSqm(), "", "", "", "");
        row(sb, "CAPACITY_CEILING", "PRELIM", "Preliminary Capacity Ceiling",
                cap.governingConstraint() != null ? cap.governingConstraint().name() : "",
                cap.usableGatheringTotalSqm(), "", cap.preliminaryCapacity(), "", cap.effectiveReviewStatus().name());
        for (CapacityDtos.ZoneCapacityRow zr : cap.zoneRows()) {
            row(sb, "ZONE_ASSUMPTION", zr.zoneCode(), zr.name(), zr.zoneTypeName(), zr.usableZoneAreaSqm(),
                    zr.densityPersonsPerSqm(), zr.computedCapacity(), zr.sourceCitation(),
                    zr.reviewStatus() != null ? zr.reviewStatus().name() : "PENDING");
        }
    }

    private void appendArrivalsCsv(StringBuilder sb, ReportDtos.EventReportBundle bundle) {
        row(sb, "Window", "Label", "TargetGate", "ExpectedArrivals", "GateCapacity", "OpeningQueue", "EndingQueue", "EstWaitMin", "Overflow", "OverflowPersons");
        for (OperationsDtos.QueueIntervalRowDto q : bundle.operationsWorkspace().queueProjection().intervals()) {
            row(sb, q.clockWindowLabel(), q.label(), q.targetGateLabel(), q.expectedArrivals(),
                    q.gateCapacity(), q.openingQueue(), q.endingQueue(), q.estimatedWaitMinutes(),
                    q.overflow(), q.overflowPersons());
        }
    }

    private void appendOccupancyCsv(StringBuilder sb, ReportDtos.EventReportBundle bundle) {
        row(sb, "DataSource", "ZoneCode", "ZoneName", "Gate", "Count", "RecordedBy", "RecordedAt", "Note");
        for (MonitoringDtos.OccupancyReadingDto rd : bundle.liveManualView().recentReadings()) {
            row(sb, rd.dataSource().name(), rd.zoneCode(), rd.zoneName(), rd.gateLabel(),
                    rd.countValue(), rd.recordedBy(), formatInstant(rd.recordedAt()), rd.note());
        }
        for (MonitoringDtos.OccupancyReadingDto rd : bundle.livePassView().recentReadings()) {
            row(sb, rd.dataSource().name(), rd.zoneCode(), rd.zoneName(), rd.gateLabel(),
                    rd.countValue(), rd.recordedBy(), formatInstant(rd.recordedAt()), rd.note());
        }
        for (MonitoringDtos.OccupancyReadingDto rd : bundle.practiceView().recentReadings()) {
            row(sb, rd.dataSource().name(), rd.zoneCode(), rd.zoneName(), rd.gateLabel(),
                    rd.countValue(), rd.recordedBy(), formatInstant(rd.recordedAt()), rd.note());
        }
    }

    private void appendSimulationCsv(StringBuilder sb, ReportDtos.EventReportBundle bundle) {
        row(sb, "RunId", "SnapshotVersion", "SnapshotLabel", "RunLabel", "Scenario", "Seed", "Agents", "DurationSec",
                "PeakDensityPsm", "PeakQueue", "MaxWaitSec", "AvgClearanceSec", "P95ClearanceSec", "ClearancePct", "UnservedAgents");
        for (SimulationDtos.SimulationRunSummaryDto r : bundle.simulationWorkspace().runs()) {
            SimulationDtos.SimulationMetricsSummary m = r.metrics();
            row(sb, r.id(), r.snapshotVersion(), r.snapshotLabel(), r.label(), r.scenarioType().name(),
                    r.randomSeed(), r.agentCount(), r.durationSeconds(),
                    m.peakLocalDensityPsm(), m.peakQueueLength(), m.maxQueueWaitTimeSec(),
                    m.avgTravelClearanceTimeSec(), m.p95TravelClearanceTimeSec(), m.clearanceRatePct(),
                    m.unservedBlockedAgentCount());
        }
    }

    private void row(StringBuilder sb, Object... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCsv(fields[i] != null ? String.valueOf(fields[i]) : ""));
        }
        sb.append("\r\n");
    }

    String escapeCsv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private String formatInstant(Instant instant) {
        return instant != null ? TS_FMT.format(instant) : "";
    }
}
