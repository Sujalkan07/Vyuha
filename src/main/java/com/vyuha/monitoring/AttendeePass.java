package com.vyuha.monitoring;

import com.vyuha.auth.AppUser;
import com.vyuha.common.AuditFields;
import com.vyuha.event.Event;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.infrastructure.InfrastructureElement;
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
 * Unique attendee entry/exit pass identified by a human-readable code (VYU-XXXX-XXXX)
 * and rendered offline via ZXing QR codes.
 */
@Entity
@Table(name = "attendee_passes")
public class AttendeePass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "pass_code", nullable = false, length = 32)
    private String passCode;

    @Column(name = "holder_label", length = 160)
    private String holderLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PassStatus status = PassStatus.ISSUED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_gate_id")
    private InfrastructureElement lastGate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_zone_id")
    private FunctionalZone lastZone;

    @Column(name = "last_scanned_at")
    private Instant lastScannedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_scanned_by_id")
    private AppUser lastScannedBy;

    @Column(name = "last_scanned_by_name", length = 120)
    private String lastScannedByName;

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

    public String getPassCode() {
        return passCode;
    }

    public void setPassCode(String passCode) {
        this.passCode = passCode;
    }

    public String getHolderLabel() {
        return holderLabel;
    }

    public void setHolderLabel(String holderLabel) {
        this.holderLabel = holderLabel;
    }

    public PassStatus getStatus() {
        return status;
    }

    public void setStatus(PassStatus status) {
        this.status = status;
    }

    public InfrastructureElement getLastGate() {
        return lastGate;
    }

    public void setLastGate(InfrastructureElement lastGate) {
        this.lastGate = lastGate;
    }

    public FunctionalZone getLastZone() {
        return lastZone;
    }

    public void setLastZone(FunctionalZone lastZone) {
        this.lastZone = lastZone;
    }

    public Instant getLastScannedAt() {
        return lastScannedAt;
    }

    public void setLastScannedAt(Instant lastScannedAt) {
        this.lastScannedAt = lastScannedAt;
    }

    public AppUser getLastScannedBy() {
        return lastScannedBy;
    }

    public void setLastScannedBy(AppUser lastScannedBy) {
        this.lastScannedBy = lastScannedBy;
    }

    public String getLastScannedByName() {
        return lastScannedByName;
    }

    public void setLastScannedByName(String lastScannedByName) {
        this.lastScannedByName = lastScannedByName;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
