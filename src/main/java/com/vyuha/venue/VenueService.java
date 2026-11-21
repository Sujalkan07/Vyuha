package com.vyuha.venue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.common.GeometryUtil;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.locationtech.jts.geom.Polygon;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates Phase 3 Venue Plan persistence, calibration workflow, polygon updates,
 * Undo/Redo layout synchronization, and Java-computed site-plan verification findings.
 */
@Service
public class VenueService {

    private final EventService eventService;
    private final VenuePlanRepository venuePlanRepository;
    private final ExclusionAreaRepository exclusionAreaRepository;
    private final GeometryUtil geometryUtil;
    private final CalibrationService calibrationService;
    private final AreaBreakdownService areaBreakdownService;
    private final SampleMapService sampleMapService;
    private final SiteBlueprintStorageService blueprintStorageService;
    private final ObjectMapper objectMapper;

    public VenueService(EventService eventService,
                        VenuePlanRepository venuePlanRepository,
                        ExclusionAreaRepository exclusionAreaRepository,
                        GeometryUtil geometryUtil,
                        CalibrationService calibrationService,
                        AreaBreakdownService areaBreakdownService,
                        SampleMapService sampleMapService,
                        SiteBlueprintStorageService blueprintStorageService,
                        ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.venuePlanRepository = venuePlanRepository;
        this.exclusionAreaRepository = exclusionAreaRepository;
        this.geometryUtil = geometryUtil;
        this.calibrationService = calibrationService;
        this.areaBreakdownService = areaBreakdownService;
        this.sampleMapService = sampleMapService;
        this.blueprintStorageService = blueprintStorageService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public VenueDtos.VenueWorkspaceDto getWorkspaceState(Long eventId, boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);
        List<ExclusionArea> exclusions = plan != null
                ? exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        return buildWorkspaceDto(event, plan, exclusions, canEdit);
    }

    @Transactional
    public VenueDtos.VenueWorkspaceDto updateMode(Long eventId,
                                                  VenueDtos.ModeUpdateRequest request,
                                                  boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = getOrCreatePlan(event);

        if (request.workspaceMode() != null) {
            plan.setWorkspaceMode(request.workspaceMode());
        }
        if (request.sampleMapKey() != null && !request.sampleMapKey().isBlank()) {
            String key = request.sampleMapKey().trim();
            if (!SampleMapService.CUSTOM_UPLOAD_KEY.equals(key) && !sampleMapService.isValidBuiltInKey(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown sample map key: " + key);
            }
            plan.setSampleMapKey(key);
        }
        if (request.gridSnapMeters() != null) {
            double snap = request.gridSnapMeters();
            if (!Double.isFinite(snap) || snap < 0.0 || snap > 25.0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Grid snap must be between 0 and 25 m.");
            }
            plan.setGridSnapMeters(snap);
        }

        VenuePlan saved = venuePlanRepository.save(plan);
        List<ExclusionArea> exclusions = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(saved.getId());
        return buildWorkspaceDto(event, saved, exclusions, canEdit);
    }

    @Transactional
    public VenueDtos.VenueWorkspaceDto importCustomVectorMap(Long eventId,
                                                             MultipartFile file,
                                                             Double targetWidthMeters,
                                                             boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = getOrCreatePlan(event);
        SampleMapService.ImportedVectorMap imported = sampleMapService.importCustomVectorFile(file, targetWidthMeters);
        plan.setWorkspaceMode(WorkspaceMode.INTERACTIVE_MAP);
        plan.setSampleMapKey(SampleMapService.CUSTOM_UPLOAD_KEY);
        plan.setCustomVectorFilename(imported.filename());
        plan.setCustomVectorJson(imported.geoJson());

        VenuePlan saved = venuePlanRepository.save(plan);
        List<ExclusionArea> exclusions = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(saved.getId());
        return buildWorkspaceDto(event, saved, exclusions, canEdit);
    }

    @Transactional
    public VenueDtos.VenueWorkspaceDto uploadBlueprint(Long eventId, MultipartFile file, boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = getOrCreatePlan(event);

        SiteBlueprintStorageService.StoredBlueprint stored = blueprintStorageService.storeBlueprint(
                eventId, file, plan.getBlueprintStoredFilename());

        plan.setWorkspaceMode(WorkspaceMode.BLUEPRINT_UPLOAD);
        plan.setBlueprintOriginalFilename(stored.originalFilename());
        plan.setBlueprintStoredFilename(stored.storedFilename());
        plan.setBlueprintContentType(stored.contentType());
        plan.setBlueprintFileSizeBytes(stored.fileSizeBytes());
        plan.setBlueprintWidthPx(stored.widthPx());
        plan.setBlueprintHeightPx(stored.heightPx());

        // Reset calibration when replacing the blueprint image so stale scale ratios are never reused.
        plan.setCalibrationJson(null);
        plan.setPixelsPerMeter(null);
        plan.setScaleVerified(false);
        plan.setDistortionWarning(false);
        plan.setOrthographicConfirmed(false);

        VenuePlan saved = venuePlanRepository.save(plan);
        List<ExclusionArea> exclusions = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(saved.getId());
        return buildWorkspaceDto(event, saved, exclusions, canEdit);
    }

    @Transactional(readOnly = true)
    public BlueprintDownload loadBlueprint(Long eventId) {
        VenuePlan plan = venuePlanRepository.findByEventId(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Venue plan not found."));
        Resource resource = blueprintStorageService.loadBlueprintResource(eventId, plan.getBlueprintStoredFilename());
        return new BlueprintDownload(
                plan.getBlueprintOriginalFilename(),
                plan.getBlueprintContentType(),
                plan.getBlueprintFileSizeBytes(),
                resource
        );
    }

    @Transactional
    public VenueDtos.VenueWorkspaceDto applyCalibration(Long eventId,
                                                        VenueDtos.CalibrationRequest request,
                                                        boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = getOrCreatePlan(event);
        if (plan.getBlueprintStoredFilename() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Upload a blueprint or site photo before running scale calibration.");
        }

        Double previousPpm = plan.getPixelsPerMeter();
        CalibrationData calibration;
        try {
            calibration = calibrationService.calibrate(request, plan.getBlueprintWidthPx(), plan.getBlueprintHeightPx());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }

        plan.setWorkspaceMode(WorkspaceMode.BLUEPRINT_UPLOAD);
        plan.setPixelsPerMeter(calibration.pixelsPerMeter());
        plan.setScaleVerified(calibration.scaleVerified());
        plan.setDistortionWarning(calibration.distortionWarning());
        plan.setOrthographicConfirmed(calibration.orthographicConfirmed());
        plan.setCalibrationJson(serializeCalibration(calibration));

        boolean shouldRescale = request.rescaleExistingGeometry() == null || request.rescaleExistingGeometry();
        List<ExclusionArea> exclusions = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        if (shouldRescale && previousPpm != null && previousPpm > 0.0
                && Math.abs(previousPpm - calibration.pixelsPerMeter()) > 1e-9) {
            double scaleFactor = previousPpm / calibration.pixelsPerMeter();
            if (plan.getBoundaryWkt() != null && !plan.getBoundaryWkt().isBlank()) {
                plan.setBoundaryWkt(calibrationService.rescalePolygonWkt(plan.getBoundaryWkt(), scaleFactor));
            }
            for (ExclusionArea ex : exclusions) {
                ex.setPolygonWkt(calibrationService.rescalePolygonWkt(ex.getPolygonWkt(), scaleFactor));
            }
            exclusionAreaRepository.saveAll(exclusions);
        }

        VenuePlan saved = venuePlanRepository.save(plan);
        return buildWorkspaceDto(event, saved, exclusions, canEdit);
    }

    @Transactional
    public VenueDtos.VenueWorkspaceDto saveBoundary(Long eventId,
                                                    VenueDtos.BoundaryUpdateRequest request,
                                                    boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = getOrCreatePlan(event);

        if (request == null || request.clear()) {
            plan.setBoundaryWkt(null);
        } else {
            Polygon poly = resolveIncomingPolygon(plan, request.points(), request.polygonWkt(), request.coordinateSpace());
            plan.setBoundaryWkt(geometryUtil.toWkt(poly));
        }

        VenuePlan saved = venuePlanRepository.save(plan);
        List<ExclusionArea> exclusions = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(saved.getId());
        return buildWorkspaceDto(event, saved, exclusions, canEdit);
    }

    @Transactional
    public VenueDtos.VenueWorkspaceDto createExclusion(Long eventId,
                                                       VenueDtos.ExclusionRequest request,
                                                       boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = getOrCreatePlan(event);
        Polygon poly = resolveIncomingPolygon(plan, request.points(), request.polygonWkt(), request.coordinateSpace());

        List<ExclusionArea> existing = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        int nextSort = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getSortOrder() + 1;

        ExclusionArea exclusion = new ExclusionArea();
        exclusion.setVenuePlan(plan);
        exclusion.setLabel(request.label().trim());
        exclusion.setExclusionType(request.exclusionType());
        exclusion.setNotes(trimToNull(request.notes()));
        exclusion.setPolygonWkt(geometryUtil.toWkt(poly));
        exclusion.setSortOrder(nextSort);
        exclusionAreaRepository.save(exclusion);

        List<ExclusionArea> updated = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        return buildWorkspaceDto(event, plan, updated, canEdit);
    }

    @Transactional
    public VenueDtos.VenueWorkspaceDto updateExclusion(Long eventId,
                                                       Long exclusionId,
                                                       VenueDtos.ExclusionRequest request,
                                                       boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = getOrCreatePlan(event);
        ExclusionArea exclusion = exclusionAreaRepository.findByIdAndVenuePlanId(exclusionId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exclusion polygon not found."));

        exclusion.setLabel(request.label().trim());
        exclusion.setExclusionType(request.exclusionType());
        exclusion.setNotes(trimToNull(request.notes()));
        if ((request.points() != null && !request.points().isEmpty())
                || (request.polygonWkt() != null && !request.polygonWkt().isBlank())) {
            Polygon poly = resolveIncomingPolygon(plan, request.points(), request.polygonWkt(), request.coordinateSpace());
            exclusion.setPolygonWkt(geometryUtil.toWkt(poly));
        }
        exclusionAreaRepository.save(exclusion);

        List<ExclusionArea> updated = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        return buildWorkspaceDto(event, plan, updated, canEdit);
    }

    @Transactional
    public VenueDtos.VenueWorkspaceDto deleteExclusion(Long eventId, Long exclusionId, boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = getOrCreatePlan(event);
        ExclusionArea exclusion = exclusionAreaRepository.findByIdAndVenuePlanId(exclusionId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exclusion polygon not found."));

        exclusionAreaRepository.delete(exclusion);
        List<ExclusionArea> updated = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        return buildWorkspaceDto(event, plan, updated, canEdit);
    }

    @Transactional
    public VenueDtos.VenueWorkspaceDto syncLayout(Long eventId,
                                                  VenueDtos.LayoutSyncRequest request,
                                                  boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = getOrCreatePlan(event);

        if (request == null || request.boundaryWkt() == null || request.boundaryWkt().isBlank()) {
            plan.setBoundaryWkt(null);
        } else {
            try {
                Polygon boundary = geometryUtil.parsePolygon(request.boundaryWkt());
                plan.setBoundaryWkt(geometryUtil.toWkt(boundary));
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
            }
        }
        VenuePlan savedPlan = venuePlanRepository.save(plan);

        exclusionAreaRepository.deleteByVenuePlanId(savedPlan.getId());
        exclusionAreaRepository.flush();

        List<ExclusionArea> toSave = new ArrayList<>();
        if (request != null && request.exclusions() != null) {
            int order = 1;
            for (VenueDtos.ExclusionSyncItem item : request.exclusions()) {
                if (item == null || item.polygonWkt() == null || item.polygonWkt().isBlank()) {
                    continue;
                }
                Polygon poly;
                try {
                    poly = geometryUtil.parsePolygon(item.polygonWkt());
                } catch (IllegalArgumentException ex) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
                }
                ExclusionArea ex = new ExclusionArea();
                ex.setVenuePlan(savedPlan);
                ex.setLabel(item.label() != null && !item.label().isBlank() ? item.label().trim() : "Exclusion " + order);
                ex.setExclusionType(item.exclusionType() != null ? item.exclusionType() : ExclusionType.RESTRICTED);
                ex.setNotes(trimToNull(item.notes()));
                ex.setPolygonWkt(geometryUtil.toWkt(poly));
                ex.setSortOrder(order++);
                toSave.add(ex);
            }
        }
        List<ExclusionArea> savedExclusions = exclusionAreaRepository.saveAll(toSave);
        return buildWorkspaceDto(event, savedPlan, savedExclusions, canEdit);
    }

    private Polygon resolveIncomingPolygon(VenuePlan plan,
                                           List<GeometryUtil.Point2D> points,
                                           String polygonWkt,
                                           String coordinateSpace) {
        try {
            if (points != null && !points.isEmpty()) {
                List<GeometryUtil.Point2D> metricPoints = points;
                if ("PIXELS".equalsIgnoreCase(coordinateSpace)) {
                    if (plan.getPixelsPerMeter() == null || plan.getPixelsPerMeter() <= 0.0) {
                        throw new IllegalArgumentException(
                                "Calibrate blueprint scale before tracing polygons in pixel coordinates.");
                    }
                    metricPoints = calibrationService.pixelsToMeters(points, plan.getPixelsPerMeter());
                }
                return geometryUtil.buildPolygon(metricPoints);
            }
            return geometryUtil.parsePolygon(polygonWkt);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @Transactional
    public VenuePlan getOrCreatePlan(Event event) {
        return venuePlanRepository.findByEventId(event.getId())
                .orElseGet(() -> {
                    VenuePlan plan = new VenuePlan();
                    plan.setEvent(event);
                    plan.setWorkspaceMode(WorkspaceMode.INTERACTIVE_MAP);
                    plan.setSampleMapKey(SampleMapService.DEFAULT_SAMPLE_MAP_KEY);
                    plan.setGridSnapMeters(1.0);
                    return venuePlanRepository.save(plan);
                });
    }

    private VenueDtos.VenueWorkspaceDto buildWorkspaceDto(Event event,
                                                          VenuePlan plan,
                                                          List<ExclusionArea> exclusions,
                                                          boolean canEdit) {
        WorkspaceMode mode = plan != null ? plan.getWorkspaceMode() : WorkspaceMode.INTERACTIVE_MAP;
        String sampleMapKey = plan != null && plan.getSampleMapKey() != null
                ? plan.getSampleMapKey()
                : SampleMapService.DEFAULT_SAMPLE_MAP_KEY;

        CalibrationData calibration = plan != null ? deserializeCalibration(plan.getCalibrationJson()) : null;
        String boundaryWkt = plan != null ? plan.getBoundaryWkt() : null;
        List<GeometryUtil.Point2D> boundaryPoints = boundaryWkt != null && !boundaryWkt.isBlank()
                ? geometryUtil.extractOuterRing(geometryUtil.parsePolygon(boundaryWkt))
                : List.of();

        AreaBreakdownResult breakdown = areaBreakdownService.calculate(boundaryWkt, exclusions);

        List<VenueDtos.ExclusionAreaDto> exclusionDtos = new ArrayList<>();
        for (int i = 0; i < exclusions.size(); i++) {
            ExclusionArea ex = exclusions.get(i);
            Polygon poly = geometryUtil.parsePolygon(ex.getPolygonWkt());
            List<GeometryUtil.Point2D> pts = geometryUtil.extractOuterRing(poly);

            double rawArea = geometryUtil.polygonalArea(poly);
            double clippedArea = 0.0;
            double outsideArea = 0.0;
            GeometryUtil.Point2D centroid = geometryUtil.centroid(poly);

            if (breakdown.hasBoundary() && i < breakdown.exclusions().size()) {
                AreaBreakdownResult.ExclusionBreakdownItem item = breakdown.exclusions().get(i);
                rawArea = item.rawAreaSqm();
                clippedArea = item.clippedAreaSqm();
                outsideArea = item.outsideBoundarySqm();
                if (item.centroid() != null) {
                    centroid = item.centroid();
                }
            }

            exclusionDtos.add(new VenueDtos.ExclusionAreaDto(
                    ex.getId(),
                    ex.getLabel(),
                    ex.getExclusionType(),
                    ex.getExclusionType().getDisplayName(),
                    ex.getExclusionType().isCorridor(),
                    ex.getExclusionType().getFillColor(),
                    ex.getExclusionType().getStrokeColor(),
                    ex.getPolygonWkt(),
                    pts,
                    ex.getNotes(),
                    rawArea,
                    clippedArea,
                    outsideArea,
                    centroid
            ));
        }

        boolean hasBlueprint = plan != null && plan.getBlueprintStoredFilename() != null;
        String blueprintUrl = hasBlueprint ? "/api/events/" + event.getId() + "/venue/blueprint" : null;

        VenueDtos.VenuePlanDto planDto = new VenueDtos.VenuePlanDto(
                plan != null ? plan.getId() : null,
                event.getId(),
                mode,
                sampleMapKey,
                plan != null ? plan.getCustomVectorFilename() : null,
                hasBlueprint,
                blueprintUrl,
                plan != null ? plan.getBlueprintOriginalFilename() : null,
                plan != null ? plan.getBlueprintWidthPx() : null,
                plan != null ? plan.getBlueprintHeightPx() : null,
                boundaryWkt,
                boundaryPoints,
                plan != null ? plan.getPixelsPerMeter() : null,
                plan != null && plan.isScaleVerified(),
                plan != null && plan.isDistortionWarning(),
                plan != null && plan.isOrthographicConfirmed(),
                plan != null ? plan.getGridSnapMeters() : 1.0,
                calibration
        );

        Object vectorGeoJson = null;
        if (SampleMapService.CUSTOM_UPLOAD_KEY.equals(sampleMapKey)
                && plan != null && plan.getCustomVectorJson() != null) {
            vectorGeoJson = sampleMapService.parseRawGeoJson(plan.getCustomVectorJson());
        } else {
            vectorGeoJson = sampleMapService.loadBuiltInGeoJson(sampleMapKey);
        }

        List<VenueDtos.VenueCheckFinding> findings = buildFindings(mode, hasBlueprint, planDto, breakdown, exclusionDtos);

        return new VenueDtos.VenueWorkspaceDto(
                event.getId(),
                event.getName(),
                event.getExpectedAttendance(),
                canEdit,
                planDto,
                List.copyOf(exclusionDtos),
                breakdown,
                findings,
                vectorGeoJson
        );
    }

    private List<VenueDtos.VenueCheckFinding> buildFindings(WorkspaceMode mode,
                                                            boolean hasBlueprint,
                                                            VenueDtos.VenuePlanDto planDto,
                                                            AreaBreakdownResult breakdown,
                                                            List<VenueDtos.ExclusionAreaDto> exclusions) {
        List<VenueDtos.VenueCheckFinding> findings = new ArrayList<>();

        if (mode == WorkspaceMode.BLUEPRINT_UPLOAD) {
            if (!hasBlueprint) {
                findings.add(new VenueDtos.VenueCheckFinding(
                        "BLUEPRINT_MISSING",
                        "INFO",
                        "No site blueprint uploaded",
                        "Upload an aerial photo or orthographic architectural plan to begin scale calibration.",
                        "0 px",
                        null,
                        null
                ));
            } else if (planDto.pixelsPerMeter() == null) {
                findings.add(new VenueDtos.VenueCheckFinding(
                        "CALIBRATION_REQUIRED",
                        "HIGH",
                        "Blueprint scale not yet calibrated",
                        "Draw a Primary Calibration Line across a known real-world span before tracing site boundaries.",
                        "Uncalibrated",
                        null,
                        null
                ));
            } else if (planDto.distortionWarning()) {
                String pctText = planDto.calibration() != null && planDto.calibration().mismatchPercent() != null
                        ? String.format(Locale.US, "%.2f%% mismatch", planDto.calibration().mismatchPercent())
                        : "Distortion flagged";
                findings.add(new VenueDtos.VenueCheckFinding(
                        "SCALE_DISTORTION_WARNING",
                        "HIGH",
                        "Scale mismatch / perspective distortion warning",
                        "Cross-axis verification exceeded 5% tolerance or aspect ratio appears skewed. Re-calibrate or upload a top-down orthographic plan.",
                        pctText,
                        null,
                        null
                ));
            } else if (!planDto.scaleVerified()) {
                findings.add(new VenueDtos.VenueCheckFinding(
                        "SCALE_UNVERIFIED",
                        "LOW",
                        "Scale verification line not drawn",
                        "Draw an optional perpendicular Verification Line to confirm uniform X/Y scale across the uploaded image.",
                        String.format(Locale.US, "%.2f px/m", planDto.pixelsPerMeter()),
                        null,
                        null
                ));
            }
        }

        if (!breakdown.hasBoundary()) {
            findings.add(new VenueDtos.VenueCheckFinding(
                    "BOUNDARY_MISSING",
                    "INFO",
                    "Outer venue boundary not drawn",
                    "Use Draw Boundary (or Use Map Perimeter) to define the enclosed gathering perimeter.",
                    "0.00 m²",
                    null,
                    null
            ));
            return List.copyOf(findings);
        }

        if (breakdown.overlapDeduplicatedSqm() > 0.01) {
            findings.add(new VenueDtos.VenueCheckFinding(
                    "EXCLUSION_OVERLAP_DEDUPLICATED",
                    "INFO",
                    "Overlapping exclusions deduplicated",
                    "Overlapping exclusion polygons were merged via JTS geometric union so excluded space is never double-counted.",
                    String.format(Locale.US, "%.2f m² overlap merged", breakdown.overlapDeduplicatedSqm()),
                    breakdown.boundaryBounds() != null ? (breakdown.boundaryBounds().minX() + breakdown.boundaryBounds().maxX()) / 2.0 : null,
                    breakdown.boundaryBounds() != null ? (breakdown.boundaryBounds().minY() + breakdown.boundaryBounds().maxY()) / 2.0 : null
            ));
        }

        for (VenueDtos.ExclusionAreaDto ex : exclusions) {
            if (ex.outsideBoundarySqm() > 0.01) {
                findings.add(new VenueDtos.VenueCheckFinding(
                        "EXCLUSION_CLIPPED_TO_BOUNDARY",
                        "LOW",
                        "Exclusion extends outside outer boundary: " + ex.label(),
                        "Part of this exclusion lies outside the venue perimeter and was clipped before subtracting from Gross Site Area.",
                        String.format(Locale.US, "%.2f m² outside", ex.outsideBoundarySqm()),
                        ex.centroid() != null ? ex.centroid().x() : null,
                        ex.centroid() != null ? ex.centroid().y() : null
                ));
            }
        }

        if (breakdown.usableAreaSqm() < 1.0) {
            findings.add(new VenueDtos.VenueCheckFinding(
                    "NO_USABLE_GATHERING_SPACE",
                    "HIGH",
                    "Net usable gathering area is near zero",
                    "Exclusions cover nearly the entire enclosed boundary, leaving insufficient open standing area.",
                    String.format(Locale.US, "%.2f m² usable", breakdown.usableAreaSqm()),
                    breakdown.boundaryBounds() != null ? (breakdown.boundaryBounds().minX() + breakdown.boundaryBounds().maxX()) / 2.0 : null,
                    breakdown.boundaryBounds() != null ? (breakdown.boundaryBounds().minY() + breakdown.boundaryBounds().maxY()) / 2.0 : null
            ));
        }

        return List.copyOf(findings);
    }

    private String serializeCalibration(CalibrationData calibration) {
        try {
            return objectMapper.writeValueAsString(calibration);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize calibration record.", ex);
        }
    }

    private CalibrationData deserializeCalibration(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CalibrationData.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record BlueprintDownload(
            String originalFilename,
            String contentType,
            Long fileSizeBytes,
            Resource resource
    ) {
    }
}
