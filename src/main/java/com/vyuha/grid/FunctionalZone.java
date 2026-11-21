package com.vyuha.grid;

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
 * Named functional zone painted via clipped grid cells or traced as a freeform polygon.
 */
@Entity
@Table(name = "functional_zones")
public class FunctionalZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_plan_id", nullable = false)
    private VenuePlan venuePlan;

    @Column(name = "zone_code", nullable = false, length = 32)
    private String zoneCode;

    @Column(nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "zone_type", nullable = false, length = 40)
    private ZoneType zoneType;

    @Column(name = "color_hex", nullable = false, length = 16)
    private String colorHex;

    @Lob
    @Column(name = "polygon_wkt", nullable = false, columnDefinition = "CLOB")
    private String polygonWkt;

    @Lob
    @Column(name = "grid_cell_refs_json", columnDefinition = "CLOB")
    private String gridCellRefsJson;

    @Column(name = "is_isolated", nullable = false)
    private boolean isolated;

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

    public String getZoneCode() {
        return zoneCode;
    }

    public void setZoneCode(String zoneCode) {
        this.zoneCode = zoneCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ZoneType getZoneType() {
        return zoneType;
    }

    public void setZoneType(ZoneType zoneType) {
        this.zoneType = zoneType;
    }

    public String getColorHex() {
        return colorHex;
    }

    public void setColorHex(String colorHex) {
        this.colorHex = colorHex;
    }

    public String getPolygonWkt() {
        return polygonWkt;
    }

    public void setPolygonWkt(String polygonWkt) {
        this.polygonWkt = polygonWkt;
    }

    public String getGridCellRefsJson() {
        return gridCellRefsJson;
    }

    public void setGridCellRefsJson(String gridCellRefsJson) {
        this.gridCellRefsJson = gridCellRefsJson;
    }

    public boolean isIsolated() {
        return isolated;
    }

    public void setIsolated(boolean isolated) {
        this.isolated = isolated;
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
