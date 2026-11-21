package com.vyuha.network;

import com.vyuha.common.AuditFields;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.infrastructure.InfrastructureElement;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Vertex node in the pedestrian movement graph, optionally anchored to a FunctionalZone or InfrastructureElement.
 */
@Entity
@Table(name = "pathway_nodes")
public class PathwayNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_plan_id", nullable = false)
    private VenuePlan venuePlan;

    @Column(name = "node_uid", nullable = false, length = 36)
    private String nodeUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 40)
    private PathwayNodeType nodeType;

    @Column(nullable = false, length = 160)
    private String label;

    @Column(nullable = false)
    private double x;

    @Column(nullable = false)
    private double y;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_zone_id")
    private FunctionalZone linkedZone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_infra_id")
    private InfrastructureElement linkedInfra;

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

    public void setId(Long id) {
        this.id = id;
    }

    public VenuePlan getVenuePlan() {
        return venuePlan;
    }

    public void setVenuePlan(VenuePlan venuePlan) {
        this.venuePlan = venuePlan;
    }

    public String getNodeUid() {
        return nodeUid;
    }

    public void setNodeUid(String nodeUid) {
        this.nodeUid = nodeUid;
    }

    public PathwayNodeType getNodeType() {
        return nodeType;
    }

    public void setNodeType(PathwayNodeType nodeType) {
        this.nodeType = nodeType;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public FunctionalZone getLinkedZone() {
        return linkedZone;
    }

    public void setLinkedZone(FunctionalZone linkedZone) {
        this.linkedZone = linkedZone;
    }

    public InfrastructureElement getLinkedInfra() {
        return linkedInfra;
    }

    public void setLinkedInfra(InfrastructureElement linkedInfra) {
        this.linkedInfra = linkedInfra;
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
