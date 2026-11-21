package com.vyuha.capacity;

import com.vyuha.auth.AppUser;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Event-level preliminary capacity model parameters and professional review status.
 */
@Entity
@Table(name = "capacity_reviews")
public class CapacityReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, unique = true)
    private Event event;

    @Column(name = "official_venue_limit")
    private Integer officialVenueLimit;

    @Column(name = "official_limit_source", length = 255)
    private String officialLimitSource;

    @Column(name = "entry_throughput_per_min")
    private Double entryThroughputPerMin;

    @Column(name = "ingress_window_minutes")
    private Integer ingressWindowMinutes;

    @Column(name = "exit_throughput_per_min")
    private Double exitThroughputPerMin;

    @Column(name = "egress_window_minutes")
    private Integer egressWindowMinutes;

    @Column(name = "holding_buffer_capacity", nullable = false)
    private int holdingBufferCapacity = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private CapacityReviewStatus reviewStatus = CapacityReviewStatus.PENDING_REVIEW;

    @Enumerated(EnumType.STRING)
    @Column(name = "governing_constraint", nullable = false, length = 40)
    private GoverningConstraint governingConstraint = GoverningConstraint.INCOMPLETE;

    @Column(name = "preliminary_capacity")
    private Integer preliminaryCapacity;

    @Column(length = 2000)
    private String notes;

    @Column(name = "reviewer_notes", length = 2000)
    private String reviewerNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by_id")
    private AppUser verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

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

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event;
    }

    public Integer getOfficialVenueLimit() {
        return officialVenueLimit;
    }

    public void setOfficialVenueLimit(Integer officialVenueLimit) {
        this.officialVenueLimit = officialVenueLimit;
    }

    public String getOfficialLimitSource() {
        return officialLimitSource;
    }

    public void setOfficialLimitSource(String officialLimitSource) {
        this.officialLimitSource = officialLimitSource;
    }

    public Double getEntryThroughputPerMin() {
        return entryThroughputPerMin;
    }

    public void setEntryThroughputPerMin(Double entryThroughputPerMin) {
        this.entryThroughputPerMin = entryThroughputPerMin;
    }

    public Integer getIngressWindowMinutes() {
        return ingressWindowMinutes;
    }

    public void setIngressWindowMinutes(Integer ingressWindowMinutes) {
        this.ingressWindowMinutes = ingressWindowMinutes;
    }

    public Double getExitThroughputPerMin() {
        return exitThroughputPerMin;
    }

    public void setExitThroughputPerMin(Double exitThroughputPerMin) {
        this.exitThroughputPerMin = exitThroughputPerMin;
    }

    public Integer getEgressWindowMinutes() {
        return egressWindowMinutes;
    }

    public void setEgressWindowMinutes(Integer egressWindowMinutes) {
        this.egressWindowMinutes = egressWindowMinutes;
    }

    public int getHoldingBufferCapacity() {
        return holdingBufferCapacity;
    }

    public void setHoldingBufferCapacity(int holdingBufferCapacity) {
        this.holdingBufferCapacity = holdingBufferCapacity;
    }

    public CapacityReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(CapacityReviewStatus reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public GoverningConstraint getGoverningConstraint() {
        return governingConstraint;
    }

    public void setGoverningConstraint(GoverningConstraint governingConstraint) {
        this.governingConstraint = governingConstraint;
    }

    public Integer getPreliminaryCapacity() {
        return preliminaryCapacity;
    }

    public void setPreliminaryCapacity(Integer preliminaryCapacity) {
        this.preliminaryCapacity = preliminaryCapacity;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getReviewerNotes() {
        return reviewerNotes;
    }

    public void setReviewerNotes(String reviewerNotes) {
        this.reviewerNotes = reviewerNotes;
    }

    public AppUser getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(AppUser verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
