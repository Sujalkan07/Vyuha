package com.vyuha.venue;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 1:1 calibrated spatial site plan anchored to an Event.
 */
@Entity
@Table(name = "venue_plans")
public class VenuePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, unique = true)
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(name = "workspace_mode", nullable = false, length = 30)
    private WorkspaceMode workspaceMode = WorkspaceMode.INTERACTIVE_MAP;

    @Column(name = "sample_map_key", length = 80)
    private String sampleMapKey = "campus-quadrangle";

    @Column(name = "custom_vector_filename", length = 255)
    private String customVectorFilename;

    @Lob
    @Column(name = "custom_vector_json", columnDefinition = "CLOB")
    private String customVectorJson;

    @Column(name = "blueprint_original_filename", length = 255)
    private String blueprintOriginalFilename;

    @Column(name = "blueprint_stored_filename", length = 255)
    private String blueprintStoredFilename;

    @Column(name = "blueprint_content_type", length = 120)
    private String blueprintContentType;

    @Column(name = "blueprint_file_size_bytes")
    private Long blueprintFileSizeBytes;

    @Column(name = "blueprint_width_px")
    private Integer blueprintWidthPx;

    @Column(name = "blueprint_height_px")
    private Integer blueprintHeightPx;

    @Lob
    @Column(name = "boundary_wkt", columnDefinition = "CLOB")
    private String boundaryWkt;

    @Lob
    @Column(name = "calibration_json", columnDefinition = "CLOB")
    private String calibrationJson;

    @Column(name = "pixels_per_meter")
    private Double pixelsPerMeter;

    @Column(name = "scale_verified", nullable = false)
    private boolean scaleVerified;

    @Column(name = "distortion_warning", nullable = false)
    private boolean distortionWarning;

    @Column(name = "orthographic_confirmed", nullable = false)
    private boolean orthographicConfirmed;

    @Column(name = "grid_snap_meters", nullable = false)
    private double gridSnapMeters = 1.0;

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

    public WorkspaceMode getWorkspaceMode() {
        return workspaceMode;
    }

    public void setWorkspaceMode(WorkspaceMode workspaceMode) {
        this.workspaceMode = workspaceMode;
    }

    public String getSampleMapKey() {
        return sampleMapKey;
    }

    public void setSampleMapKey(String sampleMapKey) {
        this.sampleMapKey = sampleMapKey;
    }

    public String getCustomVectorFilename() {
        return customVectorFilename;
    }

    public void setCustomVectorFilename(String customVectorFilename) {
        this.customVectorFilename = customVectorFilename;
    }

    public String getCustomVectorJson() {
        return customVectorJson;
    }

    public void setCustomVectorJson(String customVectorJson) {
        this.customVectorJson = customVectorJson;
    }

    public String getBlueprintOriginalFilename() {
        return blueprintOriginalFilename;
    }

    public void setBlueprintOriginalFilename(String blueprintOriginalFilename) {
        this.blueprintOriginalFilename = blueprintOriginalFilename;
    }

    public String getBlueprintStoredFilename() {
        return blueprintStoredFilename;
    }

    public void setBlueprintStoredFilename(String blueprintStoredFilename) {
        this.blueprintStoredFilename = blueprintStoredFilename;
    }

    public String getBlueprintContentType() {
        return blueprintContentType;
    }

    public void setBlueprintContentType(String blueprintContentType) {
        this.blueprintContentType = blueprintContentType;
    }

    public Long getBlueprintFileSizeBytes() {
        return blueprintFileSizeBytes;
    }

    public void setBlueprintFileSizeBytes(Long blueprintFileSizeBytes) {
        this.blueprintFileSizeBytes = blueprintFileSizeBytes;
    }

    public Integer getBlueprintWidthPx() {
        return blueprintWidthPx;
    }

    public void setBlueprintWidthPx(Integer blueprintWidthPx) {
        this.blueprintWidthPx = blueprintWidthPx;
    }

    public Integer getBlueprintHeightPx() {
        return blueprintHeightPx;
    }

    public void setBlueprintHeightPx(Integer blueprintHeightPx) {
        this.blueprintHeightPx = blueprintHeightPx;
    }

    public String getBoundaryWkt() {
        return boundaryWkt;
    }

    public void setBoundaryWkt(String boundaryWkt) {
        this.boundaryWkt = boundaryWkt;
    }

    public String getCalibrationJson() {
        return calibrationJson;
    }

    public void setCalibrationJson(String calibrationJson) {
        this.calibrationJson = calibrationJson;
    }

    public Double getPixelsPerMeter() {
        return pixelsPerMeter;
    }

    public void setPixelsPerMeter(Double pixelsPerMeter) {
        this.pixelsPerMeter = pixelsPerMeter;
    }

    public boolean isScaleVerified() {
        return scaleVerified;
    }

    public void setScaleVerified(boolean scaleVerified) {
        this.scaleVerified = scaleVerified;
    }

    public boolean isDistortionWarning() {
        return distortionWarning;
    }

    public void setDistortionWarning(boolean distortionWarning) {
        this.distortionWarning = distortionWarning;
    }

    public boolean isOrthographicConfirmed() {
        return orthographicConfirmed;
    }

    public void setOrthographicConfirmed(boolean orthographicConfirmed) {
        this.orthographicConfirmed = orthographicConfirmed;
    }

    public double getGridSnapMeters() {
        return gridSnapMeters;
    }

    public void setGridSnapMeters(double gridSnapMeters) {
        this.gridSnapMeters = gridSnapMeters;
    }

    public AuditFields getAudit() {
        return audit;
    }
}
