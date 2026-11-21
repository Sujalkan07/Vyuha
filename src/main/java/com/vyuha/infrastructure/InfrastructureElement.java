package com.vyuha.infrastructure;

import com.vyuha.common.AuditFields;
import com.vyuha.venue.VenuePlan;
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
 * Positioned physical infrastructure element or access lane anchored to a VenuePlan.
 */
@Entity
@Table(name = "infrastructure_elements")
public class InfrastructureElement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_plan_id", nullable = false)
    private VenuePlan venuePlan;

    @Column(name = "element_uid", nullable = false, length = 36)
    private String elementUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "element_type", nullable = false, length = 40)
    private InfrastructureType elementType;

    @Column(nullable = false, length = 160)
    private String label;

    @Column(name = "center_x", nullable = false)
    private double centerX;

    @Column(name = "center_y", nullable = false)
    private double centerY;

    @Column(name = "width_m", nullable = false)
    private double widthM;

    @Column(name = "height_m", nullable = false)
    private double heightM;

    @Column(name = "rotation_deg", nullable = false)
    private double rotationDeg;

    @Lob
    @Column(name = "footprint_wkt", nullable = false, columnDefinition = "CLOB")
    private String footprintWkt;

    @Column(name = "throughput_persons_per_min")
    private Double throughputPersonsPerMin;

    @Column(nullable = false)
    private boolean active = true;

    @Lob
    @Column(name = "metadata_json", columnDefinition = "CLOB")
    private String metadataJson;

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

    public void setId(Long id) {
        this.id = id;
    }

    public VenuePlan getVenuePlan() {
        return venuePlan;
    }

    public void setVenuePlan(VenuePlan venuePlan) {
        this.venuePlan = venuePlan;
    }

    public String getElementUid() {
        return elementUid;
    }

    public void setElementUid(String elementUid) {
        this.elementUid = elementUid;
    }

    public InfrastructureType getElementType() {
        return elementType;
    }

    public void setElementType(InfrastructureType elementType) {
        this.elementType = elementType;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public double getCenterX() {
        return centerX;
    }

    public void setCenterX(double centerX) {
        this.centerX = centerX;
    }

    public double getCenterY() {
        return centerY;
    }

    public void setCenterY(double centerY) {
        this.centerY = centerY;
    }

    public double getWidthM() {
        return widthM;
    }

    public void setWidthM(double widthM) {
        this.widthM = widthM;
    }

    public double getHeightM() {
        return heightM;
    }

    public void setHeightM(double heightM) {
        this.heightM = heightM;
    }

    public double getRotationDeg() {
        return rotationDeg;
    }

    public void setRotationDeg(double rotationDeg) {
        this.rotationDeg = rotationDeg;
    }

    public String getFootprintWkt() {
        return footprintWkt;
    }

    public void setFootprintWkt(String footprintWkt) {
        this.footprintWkt = footprintWkt;
    }

    public Double getThroughputPersonsPerMin() {
        return throughputPersonsPerMin;
    }

    public void setThroughputPersonsPerMin(Double throughputPersonsPerMin) {
        this.throughputPersonsPerMin = throughputPersonsPerMin;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
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
