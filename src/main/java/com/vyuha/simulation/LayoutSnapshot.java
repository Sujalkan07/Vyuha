package com.vyuha.simulation;

import com.vyuha.auth.AppUser;
import com.vyuha.common.AuditFields;
import com.vyuha.event.Event;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Versioned frozen snapshot of an event's venue layout, zones, infrastructure,
 * pathways, arrival waves, and emergency assets used for reproducible simulations.
 */
@Entity
@Table(name = "layout_snapshots")
public class LayoutSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(nullable = false, length = 160)
    private String label;

    @Column(length = 500)
    private String notes;

    @Lob
    @Column(name = "serialized_layout_json", nullable = false, columnDefinition = "CLOB")
    private String serializedLayoutJson;

    @Column(name = "thumbnail_png_path", length = 255)
    private String thumbnailPngPath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private AppUser createdBy;

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

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getSerializedLayoutJson() {
        return serializedLayoutJson;
    }

    public void setSerializedLayoutJson(String serializedLayoutJson) {
        this.serializedLayoutJson = serializedLayoutJson;
    }

    public String getThumbnailPngPath() {
        return thumbnailPngPath;
    }

    public void setThumbnailPngPath(String thumbnailPngPath) {
        this.thumbnailPngPath = thumbnailPngPath;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(AppUser createdBy) {
        this.createdBy = createdBy;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
