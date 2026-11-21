package com.vyuha.monitoring;

import com.vyuha.common.AuditFields;
import com.vyuha.event.Event;
import com.vyuha.grid.FunctionalZone;
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
 * Threshold and staleness rule for live occupancy alerting (event default when zone is null,
 * or zone-specific override when zone is set).
 */
@Entity
@Table(name = "monitoring_alert_rules")
public class MonitoringAlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private FunctionalZone zone;

    @Column(name = "warning_threshold_pct", nullable = false)
    private double warningThresholdPct = 80.0;

    @Column(name = "critical_threshold_pct", nullable = false)
    private double criticalThresholdPct = 95.0;

    @Column(name = "stale_after_minutes", nullable = false)
    private int staleAfterMinutes = 15;

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

    public FunctionalZone getZone() {
        return zone;
    }

    public void setZone(FunctionalZone zone) {
        this.zone = zone;
    }

    public double getWarningThresholdPct() {
        return warningThresholdPct;
    }

    public void setWarningThresholdPct(double warningThresholdPct) {
        this.warningThresholdPct = warningThresholdPct;
    }

    public double getCriticalThresholdPct() {
        return criticalThresholdPct;
    }

    public void setCriticalThresholdPct(double criticalThresholdPct) {
        this.criticalThresholdPct = criticalThresholdPct;
    }

    public int getStaleAfterMinutes() {
        return staleAfterMinutes;
    }

    public void setStaleAfterMinutes(int staleAfterMinutes) {
        this.staleAfterMinutes = staleAfterMinutes;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
