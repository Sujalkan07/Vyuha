package com.vyuha.simulation;

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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Persisted execution record and deterministic metrics summary for a crowd simulation run.
 */
@Entity
@Table(name = "simulation_runs")
public class SimulationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private LayoutSnapshot snapshot;

    @Column(length = 160)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "scenario_type", nullable = false, length = 40)
    private SimulationScenarioType scenarioType;

    @Column(name = "random_seed", nullable = false)
    private long randomSeed;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "agent_count", nullable = false)
    private int agentCount;

    @Lob
    @Column(name = "config_json", nullable = false, columnDefinition = "CLOB")
    private String configJson;

    @Lob
    @Column(name = "metrics_summary_json", nullable = false, columnDefinition = "CLOB")
    private String metricsSummaryJson;

    @Column(name = "frame_stream_path", length = 255)
    private String frameStreamPath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private AppUser createdBy;

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

    public LayoutSnapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(LayoutSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public SimulationScenarioType getScenarioType() {
        return scenarioType;
    }

    public void setScenarioType(SimulationScenarioType scenarioType) {
        this.scenarioType = scenarioType;
    }

    public long getRandomSeed() {
        return randomSeed;
    }

    public void setRandomSeed(long randomSeed) {
        this.randomSeed = randomSeed;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public int getAgentCount() {
        return agentCount;
    }

    public void setAgentCount(int agentCount) {
        this.agentCount = agentCount;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public String getMetricsSummaryJson() {
        return metricsSummaryJson;
    }

    public void setMetricsSummaryJson(String metricsSummaryJson) {
        this.metricsSummaryJson = metricsSummaryJson;
    }

    public String getFrameStreamPath() {
        return frameStreamPath;
    }

    public void setFrameStreamPath(String frameStreamPath) {
        this.frameStreamPath = frameStreamPath;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(AppUser createdBy) {
        this.createdBy = createdBy;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
