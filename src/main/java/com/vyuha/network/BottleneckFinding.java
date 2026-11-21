package com.vyuha.network;

import com.vyuha.auth.AppUser;
import com.vyuha.common.AuditFields;
import com.vyuha.infrastructure.FindingReviewStatus;
import com.vyuha.infrastructure.FindingSeverity;
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
import java.time.Instant;

/**
 * Automated bottleneck and movement-network screening finding with safety reviewer history preservation.
 */
@Entity
@Table(name = "bottleneck_findings")
public class BottleneckFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_plan_id", nullable = false)
    private VenuePlan venuePlan;

    @Column(name = "finding_key", nullable = false, length = 180)
    private String findingKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", nullable = false, length = 50)
    private BottleneckFindingType findingType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FindingSeverity severity;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(name = "measured_value", length = 160)
    private String measuredValue;

    @Column(name = "threshold_note", length = 250)
    private String thresholdNote;

    @Column(name = "location_x")
    private Double locationX;

    @Column(name = "location_y")
    private Double locationY;

    @Column(name = "involved_node_ids", length = 300)
    private String involvedNodeIds;

    @Column(name = "involved_edge_ids", length = 300)
    private String involvedEdgeIds;

    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private FindingReviewStatus reviewStatus = FindingReviewStatus.OPEN;

    @Column(name = "reviewer_note", length = 1000)
    private String reviewerNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id")
    private AppUser reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

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

    public String getFindingKey() {
        return findingKey;
    }

    public void setFindingKey(String findingKey) {
        this.findingKey = findingKey;
    }

    public BottleneckFindingType getFindingType() {
        return findingType;
    }

    public void setFindingType(BottleneckFindingType findingType) {
        this.findingType = findingType;
    }

    public FindingSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(FindingSeverity severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMeasuredValue() {
        return measuredValue;
    }

    public void setMeasuredValue(String measuredValue) {
        this.measuredValue = measuredValue;
    }

    public String getThresholdNote() {
        return thresholdNote;
    }

    public void setThresholdNote(String thresholdNote) {
        this.thresholdNote = thresholdNote;
    }

    public Double getLocationX() {
        return locationX;
    }

    public void setLocationX(Double locationX) {
        this.locationX = locationX;
    }

    public Double getLocationY() {
        return locationY;
    }

    public void setLocationY(Double locationY) {
        this.locationY = locationY;
    }

    public String getInvolvedNodeIds() {
        return involvedNodeIds;
    }

    public void setInvolvedNodeIds(String involvedNodeIds) {
        this.involvedNodeIds = involvedNodeIds;
    }

    public String getInvolvedEdgeIds() {
        return involvedEdgeIds;
    }

    public void setInvolvedEdgeIds(String involvedEdgeIds) {
        this.involvedEdgeIds = involvedEdgeIds;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public FindingReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(FindingReviewStatus reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public String getReviewerNote() {
        return reviewerNote;
    }

    public void setReviewerNote(String reviewerNote) {
        this.reviewerNote = reviewerNote;
    }

    public AppUser getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(AppUser reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
