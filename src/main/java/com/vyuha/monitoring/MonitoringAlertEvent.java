package com.vyuha.monitoring;

import com.vyuha.auth.AppUser;
import com.vyuha.common.AuditFields;
import com.vyuha.event.Event;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.infrastructure.FindingSeverity;
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
 * Operational or practice-simulation alert generated from capacity thresholds, stale counts,
 * or duplicate pass-scan attempts.
 */
@Entity
@Table(name = "monitoring_alert_events")
public class MonitoringAlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private FunctionalZone zone;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_source", nullable = false, length = 30)
    private OccupancyDataSource dataSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FindingSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 40)
    private MonitoringAlertType alertType;

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acknowledged_by_id")
    private AppUser acknowledgedByUser;

    @Column(name = "acknowledged_by", length = 120)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Embedded
    private AuditFields audit = new AuditFields();

    @PrePersist
    public void prePersist() {
        if (this.triggeredAt == null) {
            this.triggeredAt = Instant.now();
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

    public FunctionalZone getZone() {
        return zone;
    }

    public void setZone(FunctionalZone zone) {
        this.zone = zone;
    }

    public OccupancyDataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(OccupancyDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public FindingSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(FindingSeverity severity) {
        this.severity = severity;
    }

    public MonitoringAlertType getAlertType() {
        return alertType;
    }

    public void setAlertType(MonitoringAlertType alertType) {
        this.alertType = alertType;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public void setTriggeredAt(Instant triggeredAt) {
        this.triggeredAt = triggeredAt;
    }

    public AppUser getAcknowledgedByUser() {
        return acknowledgedByUser;
    }

    public void setAcknowledgedByUser(AppUser acknowledgedByUser) {
        this.acknowledgedByUser = acknowledgedByUser;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public void setAcknowledgedBy(String acknowledgedBy) {
        this.acknowledgedBy = acknowledgedBy;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
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
