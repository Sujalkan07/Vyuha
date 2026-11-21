package com.vyuha.event;

import com.vyuha.auth.AppUser;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Append-only formal safety review log entry issued by a Safety Reviewer or Admin,
 * with optional organizer response annotation.
 */
@Entity
@Table(name = "safety_review_records")
public class SafetyReviewRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private AppUser reviewer;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 40)
    private SafetyReviewStatus reviewStatus;

    @Column(name = "findings_summary", nullable = false, length = 3000)
    private String findingsSummary;

    @Column(name = "organizer_response", length = 2000)
    private String organizerResponse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responded_by_id")
    private AppUser respondedBy;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Embedded
    private AuditFields audit = new AuditFields();

    @PrePersist
    public void prePersist() {
        if (reviewedAt == null) {
            reviewedAt = Instant.now();
        }
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

    public AppUser getReviewer() {
        return reviewer;
    }

    public void setReviewer(AppUser reviewer) {
        this.reviewer = reviewer;
    }

    public SafetyReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(SafetyReviewStatus reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public String getFindingsSummary() {
        return findingsSummary;
    }

    public void setFindingsSummary(String findingsSummary) {
        this.findingsSummary = findingsSummary;
    }

    public String getOrganizerResponse() {
        return organizerResponse;
    }

    public void setOrganizerResponse(String organizerResponse) {
        this.organizerResponse = organizerResponse;
    }

    public AppUser getRespondedBy() {
        return respondedBy;
    }

    public void setRespondedBy(AppUser respondedBy) {
        this.respondedBy = respondedBy;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(Instant respondedAt) {
        this.respondedAt = respondedAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
