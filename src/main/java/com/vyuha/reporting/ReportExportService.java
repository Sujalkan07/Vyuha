package com.vyuha.reporting;

import com.vyuha.auth.AppUser;
import com.vyuha.auth.UserRepository;
import com.vyuha.event.SafetyReviewStatus;
import com.vyuha.simulation.SimulationDtos;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates Phase 12 report summary views, PDF/XLSX/CSV/PNG export generation,
 * and audit persistence in report_exports.
 */
@Service
public class ReportExportService {

    private final ReportBundleBuilderService bundleBuilderService;
    private final PdfReportGenerator pdfReportGenerator;
    private final ExcelReportGenerator excelReportGenerator;
    private final CsvExportGenerator csvExportGenerator;
    private final ReportCanvasRenderer canvasRenderer;
    private final ReportExportRecordRepository exportRecordRepository;
    private final UserRepository userRepository;

    public ReportExportService(ReportBundleBuilderService bundleBuilderService,
                               PdfReportGenerator pdfReportGenerator,
                               ExcelReportGenerator excelReportGenerator,
                               CsvExportGenerator csvExportGenerator,
                               ReportCanvasRenderer canvasRenderer,
                               ReportExportRecordRepository exportRecordRepository,
                               UserRepository userRepository) {
        this.bundleBuilderService = bundleBuilderService;
        this.pdfReportGenerator = pdfReportGenerator;
        this.excelReportGenerator = excelReportGenerator;
        this.csvExportGenerator = csvExportGenerator;
        this.canvasRenderer = canvasRenderer;
        this.exportRecordRepository = exportRecordRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ReportDtos.ReportPageSummary getReportSummary(Long eventId) {
        ReportDtos.EventReportBundle bundle = bundleBuilderService.buildBundle(eventId);
        List<ReportDtos.ReportExportAuditDto> recent = exportRecordRepository
                .findTop20ByEventIdOrderByAuditCreatedAtDescIdDesc(eventId).stream()
                .map(this::toAuditDto)
                .toList();

        SafetyReviewStatus latestRev = bundle.latestSafetyReviewStatus();
        String revLabel = latestRev != null ? latestRev.getDisplayName() : "Review Pending";
        String revBadge = latestRev != null ? latestRev.getBadgeClass() : "badge-low";

        var capStatus = bundle.capacityView().calculation().effectiveReviewStatus();
        String capBadge = switch (capStatus) {
            case REVIEWER_VERIFIED -> "badge-verified";
            case FLAGGED -> "badge-high";
            default -> "badge-low";
        };

        boolean hasComparison = bundle.featuredComparison() != null;
        int simRuns = bundle.simulationWorkspace().runs().size();
        String simLabel = hasComparison
                ? "Comparison Ready"
                : (simRuns > 0 ? simRuns + " Run(s)" : "Not Run");
        String simBadge = hasComparison ? "badge-verified" : (simRuns > 0 ? "badge-info" : "badge-neutral");

        return new ReportDtos.ReportPageSummary(
                eventId,
                bundle.event().getName(),
                revLabel,
                revBadge,
                bundle.readySectionCount(),
                bundle.totalSectionCount(),
                bundle.activeCriticalOrHighFindings(),
                capStatus.getDisplayName(),
                capBadge,
                simLabel,
                simBadge,
                hasComparison,
                bundle.demoCampusFestival(),
                bundle.sectionReadiness(),
                CsvExportGenerator.DATASET_OPTIONS,
                recent
        );
    }

    @Transactional
    public ReportDtos.GeneratedExportFile exportPdf(Long eventId, Long userId, String actorName) {
        ReportDtos.EventReportBundle bundle = bundleBuilderService.buildBundle(eventId);
        byte[] bytes = pdfReportGenerator.generatePdf(bundle);
        String filename = slug(bundle.event().getName()) + "-safety-report.pdf";
        String note = String.format(Locale.US, "%d/%d sections ready; %d critical/high findings",
                bundle.readySectionCount(), bundle.totalSectionCount(), bundle.activeCriticalOrHighFindings());
        recordAudit(bundle, userId, actorName, ReportExportFormat.PDF, "full-report", filename, bytes.length, note);
        return new ReportDtos.GeneratedExportFile(
                ReportExportFormat.PDF, "full-report", filename,
                ReportExportFormat.PDF.getContentType(), bytes, note);
    }

    @Transactional
    public ReportDtos.GeneratedExportFile exportXlsx(Long eventId, Long userId, String actorName) {
        ReportDtos.EventReportBundle bundle = bundleBuilderService.buildBundle(eventId);
        byte[] bytes = excelReportGenerator.generateWorkbook(bundle);
        String filename = slug(bundle.event().getName()) + "-planning-workbook.xlsx";
        String note = "12-sheet workbook covering area, capacity, conflicts, bottlenecks, operations, simulation & live telemetry";
        recordAudit(bundle, userId, actorName, ReportExportFormat.XLSX, "full-workbook", filename, bytes.length, note);
        return new ReportDtos.GeneratedExportFile(
                ReportExportFormat.XLSX, "full-workbook", filename,
                ReportExportFormat.XLSX.getContentType(), bytes, note);
    }

    @Transactional
    public ReportDtos.GeneratedExportFile exportCsv(Long eventId, String dataset, Long userId, String actorName) {
        ReportDtos.EventReportBundle bundle = bundleBuilderService.buildBundle(eventId);
        String scope = csvExportGenerator.normalizeScope(dataset);
        byte[] bytes = csvExportGenerator.generateCsv(bundle, scope);
        String filename = slug(bundle.event().getName()) + "-" + scope + ".csv";
        String note = "CSV dataset export (" + scope + ")";
        recordAudit(bundle, userId, actorName, ReportExportFormat.CSV, scope, filename, bytes.length, note);
        return new ReportDtos.GeneratedExportFile(
                ReportExportFormat.CSV, scope, filename,
                ReportExportFormat.CSV.getContentType(), bytes, note);
    }

    @Transactional
    public ReportDtos.GeneratedExportFile exportSnapshotPng(Long eventId, String view, Long userId, String actorName) {
        ReportDtos.EventReportBundle bundle = bundleBuilderService.buildBundle(eventId);
        String scope = "comparison".equalsIgnoreCase(view) && bundle.featuredComparison() != null
                ? "comparison" : "layout";
        byte[] bytes;
        if ("comparison".equals(scope)) {
            SimulationDtos.SimulationComparisonDto cmp = bundle.featuredComparison();
            bytes = canvasRenderer.renderComparisonPng(cmp);
        } else {
            bytes = canvasRenderer.renderCurrentLayoutPng(bundle);
        }
        String filename = slug(bundle.event().getName()) + "-" + scope + "-snapshot.png";
        String note = "High-resolution PNG snapshot (" + scope + ")";
        recordAudit(bundle, userId, actorName, ReportExportFormat.PNG, scope, filename, bytes.length, note);
        return new ReportDtos.GeneratedExportFile(
                ReportExportFormat.PNG, scope, filename,
                ReportExportFormat.PNG.getContentType(), bytes, note);
    }

    private void recordAudit(ReportDtos.EventReportBundle bundle,
                             Long userId,
                             String actorName,
                             ReportExportFormat format,
                             String scope,
                             String filename,
                             long sizeBytes,
                             String note) {
        AppUser user = userId != null ? userRepository.findById(userId).orElse(null) : null;
        ReportExportRecord rec = new ReportExportRecord();
        rec.setEvent(bundle.event());
        rec.setExportedBy(user);
        rec.setExportedByName(user != null ? user.getFullName()
                : (actorName != null && !actorName.isBlank() ? actorName : "Authorized User"));
        rec.setExportFormat(format);
        rec.setExportScope(scope);
        rec.setFilename(filename);
        rec.setFileSizeBytes(sizeBytes);
        rec.setSummaryNote(note);
        exportRecordRepository.save(rec);
    }

    private ReportDtos.ReportExportAuditDto toAuditDto(ReportExportRecord r) {
        return new ReportDtos.ReportExportAuditDto(
                r.getId(),
                r.getExportFormat(),
                r.getExportFormat().getDisplayName(),
                r.getExportScope(),
                r.getFilename(),
                r.getFileSizeBytes(),
                r.getExportedByName(),
                r.getAudit().getCreatedAt(),
                r.getSummaryNote()
        );
    }

    private String slug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "vyuha-event";
        }
        String cleaned = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "vyuha-event" : cleaned;
    }
}
