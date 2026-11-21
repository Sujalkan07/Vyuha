package com.vyuha.emergency;

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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Designated emergency exit, external assembly point, or ambulance/fire vehicle access route.
 */
@Entity
@Table(name = "emergency_assets")
public class EmergencyAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 30)
    private EmergencyAssetType assetType;

    @Column(nullable = false, length = 160)
    private String label;

    @Lob
    @Column(name = "geometry_wkt", nullable = false, columnDefinition = "CLOB")
    private String geometryWkt;

    @Column(name = "min_width_m")
    private Double minWidthM;

    @Column(name = "capacity_notes", length = 500)
    private String capacityNotes;

    @Column(length = 500)
    private String notes;

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

    public void setId(Long id) {
        this.id = id;
    }

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event;
    }

    public EmergencyAssetType getAssetType() {
        return assetType;
    }

    public void setAssetType(EmergencyAssetType assetType) {
        this.assetType = assetType;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getGeometryWkt() {
        return geometryWkt;
    }

    public void setGeometryWkt(String geometryWkt) {
        this.geometryWkt = geometryWkt;
    }

    public Double getMinWidthM() {
        return minWidthM;
    }

    public void setMinWidthM(Double minWidthM) {
        this.minWidthM = minWidthM;
    }

    public String getCapacityNotes() {
        return capacityNotes;
    }

    public void setCapacityNotes(String capacityNotes) {
        this.capacityNotes = capacityNotes;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
