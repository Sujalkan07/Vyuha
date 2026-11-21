package com.vyuha.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyuha.auth.AppUser;
import com.vyuha.auth.Role;
import com.vyuha.capacity.CapacityCalculatorService;
import com.vyuha.capacity.CapacityDtos;
import com.vyuha.capacity.CapacityReviewStatus;
import com.vyuha.capacity.GoverningConstraint;
import com.vyuha.common.GeometryUtil;
import com.vyuha.emergency.EmergencyDtos;
import com.vyuha.event.ChecklistProgress;
import com.vyuha.event.Event;
import com.vyuha.event.EventCategory;
import com.vyuha.event.EventStatus;
import com.vyuha.event.SafetyReviewStatus;
import com.vyuha.grid.GridDtos;
import com.vyuha.infrastructure.FindingReviewStatus;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.infrastructure.ConflictType;
import com.vyuha.monitoring.MonitoringDtos;
import com.vyuha.monitoring.OccupancyDataSource;
import com.vyuha.network.BottleneckFindingType;
import com.vyuha.network.NetworkDtos;
import com.vyuha.operations.OperationsDtos;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.venue.AreaBreakdownResult;
import com.vyuha.venue.VenueDtos;
import com.vyuha.venue.WorkspaceMode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ReportGeneratorsUnitTest {

    private final ReportCanvasRenderer canvasRenderer = new ReportCanvasRenderer();
    private final PdfReportGenerator pdfGenerator = new PdfReportGenerator(canvasRenderer);
    private final ExcelReportGenerator excelGenerator = new ExcelReportGenerator();
    private final CsvExportGenerator csvGenerator = new CsvExportGenerator();

    @Test
    void generatesValidPdfXlsxCsvAndPngFromEventReportBundle() throws Exception {
        ReportDtos.EventReportBundle bundle = buildMinimalBundle();

        byte[] pngBytes = canvasRenderer.renderCurrentLayoutPng(bundle);
        assertThat(pngBytes).startsWith(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        var decodedImg = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertThat(decodedImg.getWidth()).isEqualTo(1200);
        assertThat(decodedImg.getHeight()).isEqualTo(760);

        byte[] pdfBytes = pdfGenerator.generatePdf(bundle);
        assertThat(new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Unit Test Venue Event");
            assertThat(text).contains("Decision-Support");
            assertThat(text).contains("Simplified Planning Simulation");
            assertThat(text).doesNotContain("Stampede-Proof");
        }

        byte[] xlsxBytes = excelGenerator.generateWorkbook(bundle);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(12);
            assertThat(wb.getSheet("Event Summary")).isNotNull();
            assertThat(wb.getSheet("Conflicts")).isNotNull();
            assertThat(wb.getSheet("Simulation Metrics")).isNotNull();
        }

        byte[] csvBytes = csvGenerator.generateCsv(bundle, "conflicts-bottlenecks");
        String csv = new String(csvBytes, StandardCharsets.UTF_8);
        assertThat(csv).contains("SPATIAL_CONFLICT");
        assertThat(csv).contains("NETWORK_BOTTLENECK");
        assertThat(csv).contains("\"Narrow 1.5m corridor, pinch\"");
    }

    @Test
    void escapesRfc4180CsvValuesCorrectly() {
        assertThat(csvGenerator.escapeCsv("plain")).isEqualTo("plain");
        assertThat(csvGenerator.escapeCsv("a,b")).isEqualTo("\"a,b\"");
        assertThat(csvGenerator.escapeCsv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }

    private ReportDtos.EventReportBundle buildMinimalBundle() {
        AppUser org = new AppUser();
        org.setFullName("Aarav Mehta");
        org.setEmail("organizer@vyuha.local");
        org.setRole(Role.ORGANIZER);

        Event event = new Event();
        event.setName("Unit Test Venue Event");
        event.setCategory(EventCategory.COLLEGE_FESTIVAL);
        event.setStatus(EventStatus.PLANNING);
        event.setStartDateTime(LocalDateTime.of(2026, 12, 10, 16, 0));
        event.setEndDateTime(LocalDateTime.of(2026, 12, 10, 22, 0));
        event.setExpectedAttendance(1000);
        event.setLocationName("Test Quadrangle");
        event.setOrganizer(org);

        List<GeometryUtil.Point2D> boundary = List.of(
                new GeometryUtil.Point2D(0, 0), new GeometryUtil.Point2D(100, 0),
                new GeometryUtil.Point2D(100, 80), new GeometryUtil.Point2D(0, 80),
                new GeometryUtil.Point2D(0, 0));
        AreaBreakdownResult area = new AreaBreakdownResult(
                true, 8000.0, 360.0, 800.0, 200.0, 1000.0, 7000.0,
                87.5, 10.0, 2.5, 12.5, 0.0, 0.0,
                Map.of(), List.of(), new GeometryUtil.Bounds(0, 0, 100, 80));

        VenueDtos.VenuePlanDto vp = new VenueDtos.VenuePlanDto(
                1L, 1L, WorkspaceMode.INTERACTIVE_MAP, "campus-quadrangle", null, false, null,
                null, null, null, "POLYGON ((0 0, 100 0, 100 80, 0 80, 0 0))", boundary,
                null, false, false, false, 1.0, null);
        VenueDtos.VenueWorkspaceDto venue = new VenueDtos.VenueWorkspaceDto(
                1L, event.getName(), 1000, false, vp, List.of(), area, List.of(), null);

        GridDtos.GridWorkspaceDto grid = new GridDtos.GridWorkspaceDto(
                1L, event.getName(), 1000, false, vp, List.of(), area, null, null, List.of(), List.of(), null, List.of());

        CapacityDtos.CapacityCalculationResult capCalc = new CapacityDtos.CapacityCalculationResult(
                true, 8000.0, 7000.0, 1000, 1200, 1500, 1500, 1500, 300, 50, 350, 2000, 350,
                GoverningConstraint.HOLDING_SPACE, List.of(GoverningConstraint.HOLDING_SPACE),
                "Holding space is tightest", -650, 285.7, true, 1, 1, 0, 0,
                CapacityReviewStatus.PENDING_REVIEW, List.of(), List.of());
        CapacityDtos.CapacityParametersView params = new CapacityDtos.CapacityParametersView(
                2000, "Permit", 50.0, 30, 50.0, 30, 50, null, null, null, null, CapacityReviewStatus.PENDING_REVIEW);
        CapacityDtos.CapacityPageView capView = new CapacityDtos.CapacityPageView(
                1L, event.getName(), 1000, false, false, area, params, capCalc);

        InfrastructureDtos.ConflictFindingDto cf = new InfrastructureDtos.ConflictFindingDto(
                1L, "k1", ConflictType.EMERGENCY_LANE_INTRUSION, "Emergency Lane Intrusion",
                FindingSeverity.CRITICAL, "Stall intrudes lane", "Move stall 2m west", "1.50 m",
                98.0, 44.0, List.of(1L), true, FindingReviewStatus.OPEN, "Open", null, null, null, null);
        InfrastructureDtos.InfrastructureWorkspaceDto infra = new InfrastructureDtos.InfrastructureWorkspaceDto(
                1L, event.getName(), 1000, false, false, vp, List.of(), area, null, List.of(), List.of(),
                List.of(cf), List.of(),
                new InfrastructureDtos.InfrastructureSummaryDto(1, 1, 1, 0, 0, 120, 0, 0, 1, 0, 0, 0, 1, 0),
                new InfrastructureDtos.HistoryStateDto(false, false, 1, 1, null, null, List.of()), null);

        NetworkDtos.BottleneckFindingDto bf = new NetworkDtos.BottleneckFindingDto(
                2L, "b1", BottleneckFindingType.NARROW_WALKWAY, "Narrow Walkway",
                FindingSeverity.HIGH, "Narrow 1.5m corridor, pinch", "Widen corridor", "1.50 m", "< 2.40 m",
                50.0, 40.0, List.of(), List.of(), true, FindingReviewStatus.OPEN, "Open", null, null, null, null);
        NetworkDtos.NetworkWorkspaceDto network = new NetworkDtos.NetworkWorkspaceDto(
                1L, event.getName(), 1000, false, false, vp, List.of(), area, null, List.of(), List.of(),
                List.of(), List.of(), List.of(bf), List.of(),
                new NetworkDtos.NetworkSummaryDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 30, 0, 1, 0, 0, 1, 0), null, null);

        OperationsDtos.QueueProjectionResult qp = new OperationsDtos.QueueProjectionResult(
                0, 1000, 120.0, "Gate", 1, 0, 0, 0, 0, null, 0, "—", 0.0, false, 0, 0, 0.0, 100, 0,
                List.of(), List.of(), List.of());
        OperationsDtos.StaffCoverageResult sc = new OperationsDtos.StaffCoverageResult(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        OperationsDtos.OperationsWorkspaceDto ops = new OperationsDtos.OperationsWorkspaceDto(
                1L, event.getName(), 1000, event.getStartDateTime(), event.getEndDateTime(), false, false,
                qp, List.of(), List.of(),
                new OperationsDtos.StaffingTargetsDto(2, 1, 2, 1, 500, 60, null),
                sc, List.of(), List.of(), List.of(), List.of(), List.of());

        EmergencyDtos.EmergencyValidationSummary evs = new EmergencyDtos.EmergencyValidationSummary(
                0, 0, 0, 0, 0, 0, 0, false, List.of(), List.of());
        EmergencyDtos.OutageAnalysisResult outage = new EmergencyDtos.OutageAnalysisResult(
                false, List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0, 0,
                0, 0, 0, 0, 100.0, 0, 0, 0, 100.0, 0, 0, 0, List.of(), List.of());
        EmergencyDtos.EmergencyWorkspaceDto emergency = new EmergencyDtos.EmergencyWorkspaceDto(
                1L, event.getName(), 1000, false, vp, List.of(), area, null, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), evs, outage);

        SimulationDtos.SimulationWorkspaceDto sim = new SimulationDtos.SimulationWorkspaceDto(
                1L, event.getName(), 1000, false, false, SimulationDtos.MANDATORY_DISCLAIMER,
                false, "No network", null, List.of(), List.of(), List.of());

        MonitoringDtos.ChannelSummaryDto ch = new MonitoringDtos.ChannelSummaryDto(
                OccupancyDataSource.MANUAL_STAFF, "Manual", "Manual", "badge-info", 0, 0, null, false, 0, 0, 15);
        MonitoringDtos.ControlCentreDto mon = new MonitoringDtos.ControlCentreDto(
                1L, event.getName(), "Planning", 1000, 350, "LIVE", OccupancyDataSource.MANUAL_STAFF,
                false, false, Instant.now(), ch, ch, ch, ch, List.of(), List.of(),
                new MonitoringDtos.QueuePressureSummaryDto(0, 0, 0, OccupancyDataSource.MANUAL_STAFF, null, null, false),
                new MonitoringDtos.StaffDutySummaryDto(0, 0, 0, 0),
                new MonitoringDtos.PreEventSafetySummaryDto(0, 0, 1, 1, "Pending", "badge-low"),
                List.of(), List.of(), List.of(),
                new MonitoringDtos.PassSummaryDto(0, 0, 0, 0, null),
                List.of(), List.of(), List.of(), null);

        return new ReportDtos.EventReportBundle(
                event, Instant.now(), List.of(), new ChecklistProgress(0, 0, 0, 0, 0, 0, 0, 0),
                List.of(), SafetyReviewStatus.COMMENTS_ISSUED, List.of(),
                venue, grid, capView, infra, network, ops, emergency, sim, null,
                mon, mon, mon, List.of(), 4, 10, 2, false);
    }
}
