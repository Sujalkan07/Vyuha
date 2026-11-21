package com.vyuha.network;

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
 * Directed or bidirectional movement corridor edge connecting two PathwayNode vertices.
 */
@Entity
@Table(name = "pathway_edges")
public class PathwayEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_plan_id", nullable = false)
    private VenuePlan venuePlan;

    @Column(name = "edge_uid", nullable = false, length = 36)
    private String edgeUid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_node_id", nullable = false)
    private PathwayNode fromNode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_node_id", nullable = false)
    private PathwayNode toNode;

    @Column(length = 160)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "network_layer", nullable = false, length = 40)
    private NetworkLayer networkLayer;

    @Column(name = "width_m", nullable = false)
    private double widthM;

    @Column(name = "length_m", nullable = false)
    private double lengthM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PathwayDirection direction = PathwayDirection.BIDIRECTIONAL;

    @Lob
    @Column(name = "polyline_wkt", nullable = false, columnDefinition = "CLOB")
    private String polylineWkt;

    @Column(nullable = false)
    private boolean active = true;

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

    public String getEdgeUid() {
        return edgeUid;
    }

    public void setEdgeUid(String edgeUid) {
        this.edgeUid = edgeUid;
    }

    public PathwayNode getFromNode() {
        return fromNode;
    }

    public void setFromNode(PathwayNode fromNode) {
        this.fromNode = fromNode;
    }

    public PathwayNode getToNode() {
        return toNode;
    }

    public void setToNode(PathwayNode toNode) {
        this.toNode = toNode;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public NetworkLayer getNetworkLayer() {
        return networkLayer;
    }

    public void setNetworkLayer(NetworkLayer networkLayer) {
        this.networkLayer = networkLayer;
    }

    public double getWidthM() {
        return widthM;
    }

    public void setWidthM(double widthM) {
        this.widthM = widthM;
    }

    public double getLengthM() {
        return lengthM;
    }

    public void setLengthM(double lengthM) {
        this.lengthM = lengthM;
    }

    public PathwayDirection getDirection() {
        return direction;
    }

    public void setDirection(PathwayDirection direction) {
        this.direction = direction;
    }

    public String getPolylineWkt() {
        return polylineWkt;
    }

    public void setPolylineWkt(String polylineWkt) {
        this.polylineWkt = polylineWkt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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
