package com.vyuha.venue;

import com.vyuha.common.AuditFields;
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
 * Polygon exclusion or reserved walkway drawn inside a venue plan in metric WKT coordinates.
 */
@Entity
@Table(name = "exclusion_areas")
public class ExclusionArea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_plan_id", nullable = false)
    private VenuePlan venuePlan;

    @Column(nullable = false, length = 160)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "exclusion_type", nullable = false, length = 40)
    private ExclusionType exclusionType;

    @Lob
    @Column(name = "polygon_wkt", nullable = false, columnDefinition = "CLOB")
    private String polygonWkt;

    @Column(length = 500)
    private String notes;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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

    public VenuePlan getVenuePlan() {
        return venuePlan;
    }

    public void setVenuePlan(VenuePlan venuePlan) {
        this.venuePlan = venuePlan;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public ExclusionType getExclusionType() {
        return exclusionType;
    }

    public void setExclusionType(ExclusionType exclusionType) {
        this.exclusionType = exclusionType;
    }

    public String getPolygonWkt() {
        return polygonWkt;
    }

    public void setPolygonWkt(String polygonWkt) {
        this.polygonWkt = polygonWkt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
