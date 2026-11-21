package com.vyuha.operations;

import com.vyuha.common.AuditFields;
import com.vyuha.event.Event;
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
 * Planner-configured minimum staffing targets used by the automated under-staffing detector.
 */
@Entity
@Table(name = "staffing_targets")
public class StaffingTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, unique = true)
    private Event event;

    @Column(name = "min_security_per_active_gate", nullable = false)
    private int minSecurityPerActiveGate = 2;

    @Column(name = "min_staff_per_public_zone", nullable = false)
    private int minStaffPerPublicZone = 2;

    @Column(name = "min_medical_staff_total", nullable = false)
    private int minMedicalStaffTotal = 2;

    @Column(name = "min_supervisors_total", nullable = false)
    private int minSupervisorsTotal = 1;

    @Column(name = "attendees_per_staff_ratio", nullable = false)
    private int attendeesPerStaffRatio = 150;

    @Column(name = "shift_window_minutes", nullable = false)
    private int shiftWindowMinutes = 60;

    @Column(length = 500)
    private String notes;

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

    public int getMinSecurityPerActiveGate() {
        return minSecurityPerActiveGate;
    }

    public void setMinSecurityPerActiveGate(int minSecurityPerActiveGate) {
        this.minSecurityPerActiveGate = minSecurityPerActiveGate;
    }

    public int getMinStaffPerPublicZone() {
        return minStaffPerPublicZone;
    }

    public void setMinStaffPerPublicZone(int minStaffPerPublicZone) {
        this.minStaffPerPublicZone = minStaffPerPublicZone;
    }

    public int getMinMedicalStaffTotal() {
        return minMedicalStaffTotal;
    }

    public void setMinMedicalStaffTotal(int minMedicalStaffTotal) {
        this.minMedicalStaffTotal = minMedicalStaffTotal;
    }

    public int getMinSupervisorsTotal() {
        return minSupervisorsTotal;
    }

    public void setMinSupervisorsTotal(int minSupervisorsTotal) {
        this.minSupervisorsTotal = minSupervisorsTotal;
    }

    public int getAttendeesPerStaffRatio() {
        return attendeesPerStaffRatio;
    }

    public void setAttendeesPerStaffRatio(int attendeesPerStaffRatio) {
        this.attendeesPerStaffRatio = attendeesPerStaffRatio;
    }

    public int getShiftWindowMinutes() {
        return shiftWindowMinutes;
    }

    public void setShiftWindowMinutes(int shiftWindowMinutes) {
        this.shiftWindowMinutes = shiftWindowMinutes;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
