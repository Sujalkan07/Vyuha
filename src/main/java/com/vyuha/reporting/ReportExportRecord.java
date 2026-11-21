package com.vyuha.reporting;

import com.vyuha.auth.AppUser;
import com.vyuha.common.AuditFields;
import com.vyuha.event.Event;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Audit record tracking generated PDF, XLSX, CSV, and PNG exports for an event.
 */
@Entity
@Table(name = "report_exports")
public class ReportExportRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exported_by_id")
    private AppUser exportedBy;

    @Column(name = "exported_by_name", nullable = false, length = 120)
    private String exportedByName;

    @Enumerated(EnumType.STRING)
    @Column(name = "export_format", nullable = false, length = 16)
    private ReportExportFormat exportFormat;

    @Column(name = "export_scope", nullable = false, length = 60)
    private String exportScope;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "summary_note", length = 500)
    private String summaryNote;

    @Embedded
    private AuditFields audit = new AuditFields();

    @PrePersist
    public void prePersist() {
        if (audit == null) {
            audit = new AuditFields();
        }
        audit.onCreate();
    }

    @PreUpdate
    public void preUpdate() {
        if (audit == null) {
            audit = new AuditFields();
        }
        audit.onUpdate();
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event;
    }

    public AppUser getExportedBy() {
        return exportedBy;
    }

    public void setExportedBy(AppUser exportedBy) {
        this.exportedBy = exportedBy;
    }

    public String getExportedByName() {
        return exportedByName;
    }

    public void setExportedByName(String exportedByName) {
        this.exportedByName = exportedByName;
    }

    public ReportExportFormat getExportFormat() {
        return exportFormat;
    }

    public void setExportFormat(ReportExportFormat exportFormat) {
        this.exportFormat = exportFormat;
    }

    public String getExportScope() {
        return exportScope;
    }

    public void setExportScope(String exportScope) {
        this.exportScope = exportScope;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getSummaryNote() {
        return summaryNote;
    }

    public void setSummaryNote(String summaryNote) {
        this.summaryNote = summaryNote;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
