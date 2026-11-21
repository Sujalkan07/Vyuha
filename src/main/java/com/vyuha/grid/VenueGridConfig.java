package com.vyuha.grid;

import com.vyuha.common.AuditFields;
import com.vyuha.venue.VenuePlan;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 1:1 grid overlay parameters (cell size, origin offset, rotation) attached to a VenuePlan.
 */
@Entity
@Table(name = "venue_grid_configs")
public class VenueGridConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_plan_id", nullable = false, unique = true)
    private VenuePlan venuePlan;

    @Column(name = "cell_size_meters", nullable = false)
    private double cellSizeMeters = 5.0;

    @Column(name = "origin_x", nullable = false)
    private double originX = 0.0;

    @Column(name = "origin_y", nullable = false)
    private double originY = 0.0;

    @Column(name = "rotation_deg", nullable = false)
    private double rotationDeg = 0.0;

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

    public double getCellSizeMeters() {
        return cellSizeMeters;
    }

    public void setCellSizeMeters(double cellSizeMeters) {
        this.cellSizeMeters = cellSizeMeters;
    }

    public double getOriginX() {
        return originX;
    }

    public void setOriginX(double originX) {
        this.originX = originX;
    }

    public double getOriginY() {
        return originY;
    }

    public void setOriginY(double originY) {
        this.originY = originY;
    }

    public double getRotationDeg() {
        return rotationDeg;
    }

    public void setRotationDeg(double rotationDeg) {
        this.rotationDeg = rotationDeg;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
