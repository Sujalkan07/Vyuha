package com.vyuha.capacity;

import com.vyuha.auth.AppUser;
import com.vyuha.common.AuditFields;
import com.vyuha.grid.FunctionalZone;
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
 * Planner-entered density assumption for a FunctionalZone with mandatory source citation
 * and safety-reviewer verification status. Density is nullable and never silently defaulted.
 */
@Entity
@Table(name = "zone_density_assumptions")
public class ZoneDensityAssumption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "capacity_review_id", nullable = false)
    private CapacityReview capacityReview;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "functional_zone_id", nullable = false)
    private FunctionalZone functionalZone;

    @Column(name = "density_persons_per_sqm")
    private Double densityPersonsPerSqm;

    @Column(name = "source_citation", length = 300)
    private String sourceCitation;

    @Column(name = "approved_by_name", length = 150)
    private String approvedByName;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private AssumptionReviewStatus reviewStatus = AssumptionReviewStatus.PENDING_REVIEW;

    @Column(name = "reviewer_note", length = 1000)
    private String reviewerNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by_id")
    private AppUser verifiedBy;

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

    public CapacityReview getCapacityReview() {
        return capacityReview;
    }

    public void setCapacityReview(CapacityReview capacityReview) {
        this.capacityReview = capacityReview;
    }

    public FunctionalZone getFunctionalZone() {
        return functionalZone;
    }

    public void setFunctionalZone(FunctionalZone functionalZone) {
        this.functionalZone = functionalZone;
    }

    public Double getDensityPersonsPerSqm() {
        return densityPersonsPerSqm;
    }

    public void setDensityPersonsPerSqm(Double densityPersonsPerSqm) {
        this.densityPersonsPerSqm = densityPersonsPerSqm;
    }

    public String getSourceCitation() {
        return sourceCitation;
    }

    public void setSourceCitation(String sourceCitation) {
        this.sourceCitation = sourceCitation;
    }

    public String getApprovedByName() {
        return approvedByName;
    }

    public void setApprovedByName(String approvedByName) {
        this.approvedByName = approvedByName;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public AssumptionReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(AssumptionReviewStatus reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public String getReviewerNote() {
        return reviewerNote;
    }

    public void setReviewerNote(String reviewerNote) {
        this.reviewerNote = reviewerNote;
    }

    public AppUser getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(AppUser verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
