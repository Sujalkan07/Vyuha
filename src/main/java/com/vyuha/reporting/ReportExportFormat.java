package com.vyuha.reporting;

/**
 * Supported Phase 12 structured export file formats.
 */
public enum ReportExportFormat {
    PDF("Printable PDF Report", "application/pdf", ".pdf"),
    XLSX("Excel Workbook (.xlsx)", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
    CSV("CSV Dataset (.csv)", "text/csv; charset=UTF-8", ".csv"),
    PNG("Venue Snapshot (.png)", "image/png", ".png");

    private final String displayName;
    private final String contentType;
    private final String fileExtension;

    ReportExportFormat(String displayName, String contentType, String fileExtension) {
        this.displayName = displayName;
        this.contentType = contentType;
        this.fileExtension = fileExtension;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getContentType() {
        return contentType;
    }

    public String getFileExtension() {
        return fileExtension;
    }
}
