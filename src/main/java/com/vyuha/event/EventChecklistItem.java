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
 * Category-tailored or custom planning checklist item belonging to an event.
 */
@Entity
@Table(name = "event_checklist_items")
public class EventChecklistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "item_key", nullable = false, length = 80)
    private String itemKey;

    @Column(name = "section_name", nullable = false, length = 100)
    private String sectionName;

    @Column(nullable = false, length = 220)
    private String title;

    @Column(name = "guidance_note", length = 1000)
    private String guidanceNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ChecklistItemStatus status = ChecklistItemStatus.NOT_STARTED;

    @Column(name = "assignee_name", length = 120)
    private String assigneeName;

    @Column(name = "evidence_notes", length = 1500)
    private String evidenceNotes;

    @Column(name = "reviewer_signed_off", nullable = false)
    private boolean reviewerSignedOff = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signed_off_by_id")
    private AppUser signedOffBy;

    @Column(name = "signed_off_at")
    private Instant signedOffAt;

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

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event;
    }

    public String getItemKey() {
        return itemKey;
    }

    public void setItemKey(String itemKey) {
        this.itemKey = itemKey;
    }

    public String getSectionName() {
        return sectionName;
    }

    public void setSectionName(String sectionName) {
        this.sectionName = sectionName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getGuidanceNote() {
        return guidanceNote;
    }

    public void setGuidanceNote(String guidanceNote) {
        this.guidanceNote = guidanceNote;
    }

    public ChecklistItemStatus getStatus() {
        return status;
    }

    public void setStatus(ChecklistItemStatus status) {
        this.status = status;
    }

    public String getAssigneeName() {
        return assigneeName;
    }

    public void setAssigneeName(String assigneeName) {
        this.assigneeName = assigneeName;
    }

    public String getEvidenceNotes() {
        return evidenceNotes;
    }

    public void setEvidenceNotes(String evidenceNotes) {
        this.evidenceNotes = evidenceNotes;
    }

    public boolean isReviewerSignedOff() {
        return reviewerSignedOff;
    }

    public void setReviewerSignedOff(boolean reviewerSignedOff) {
        this.reviewerSignedOff = reviewerSignedOff;
    }

    public AppUser getSignedOffBy() {
        return signedOffBy;
    }

    public void setSignedOffBy(AppUser signedOffBy) {
        this.signedOffBy = signedOffBy;
    }

    public Instant getSignedOffAt() {
        return signedOffAt;
    }

    public void setSignedOffAt(Instant signedOffAt) {
        this.signedOffAt = signedOffAt;
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
