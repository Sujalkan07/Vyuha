package com.vyuha.operations;

import com.vyuha.common.AuditFields;
import com.vyuha.event.Event;
import com.vyuha.infrastructure.InfrastructureElement;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
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
 * Planner-defined attendee arrival bucket for queue accumulation and overflow analysis.
 */
@Entity
@Table(name = "arrival_wave_intervals")
public class ArrivalWaveInterval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_gate_id")
    private InfrastructureElement targetGate;

    @Column(length = 120)
    private String label;

    @Column(name = "start_minute", nullable = false)
    private int startMinute;

    @Column(name = "end_minute", nullable = false)
    private int endMinute;

    @Column(name = "expected_arrivals", nullable = false)
    private int expectedArrivals;

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

    public InfrastructureElement getTargetGate() {
        return targetGate;
    }

    public void setTargetGate(InfrastructureElement targetGate) {
        this.targetGate = targetGate;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getStartMinute() {
        return startMinute;
    }

    public void setStartMinute(int startMinute) {
        this.startMinute = startMinute;
    }

    public int getEndMinute() {
        return endMinute;
    }

    public void setEndMinute(int endMinute) {
        this.endMinute = endMinute;
    }

    public int getExpectedArrivals() {
        return expectedArrivals;
    }

    public void setExpectedArrivals(int expectedArrivals) {
        this.expectedArrivals = expectedArrivals;
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
