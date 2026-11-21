package com.vyuha.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.auth.AppUser;
import com.vyuha.auth.UserRepository;
import com.vyuha.common.GeometryUtil;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.FunctionalZoneRepository;
import com.vyuha.grid.GridDtos;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionAreaRepository;
import com.vyuha.venue.VenueDtos;
import com.vyuha.venue.VenuePlan;
import com.vyuha.venue.VenuePlanRepository;
import com.vyuha.venue.VenueService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates Phase 5 Infrastructure Placement, server-backed Undo/Redo layout revisions,
 * automated spatial conflict evaluation, and finding review workflows.
 */
@Service
public class InfrastructureService {

    private final EventService eventService;
    private final VenueService venueService;
    private final VenuePlanRepository venuePlanRepository;
    private final ExclusionAreaRepository exclusionAreaRepository;
    private final FunctionalZoneRepository functionalZoneRepository;
    private final InfrastructureElementRepository elementRepository;
    private final ConflictFindingRepository findingRepository;
    private final UserRepository userRepository;
    private final GeometryUtil geometryUtil;
    private final ConflictDetectionService conflictDetectionService;
    private final LayoutCommandHistory layoutCommandHistory;
    private final ObjectMapper objectMapper;

    public InfrastructureService(EventService eventService,
                                 VenueService venueService,
                                 VenuePlanRepository venuePlanRepository,
                                 ExclusionAreaRepository exclusionAreaRepository,
                                 FunctionalZoneRepository functionalZoneRepository,
                                 InfrastructureElementRepository elementRepository,
                                 ConflictFindingRepository findingRepository,
                                 UserRepository userRepository,
                                 GeometryUtil geometryUtil,
                                 ConflictDetectionService conflictDetectionService,
                                 LayoutCommandHistory layoutCommandHistory,
                                 ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.venueService = venueService;
        this.venuePlanRepository = venuePlanRepository;
        this.exclusionAreaRepository = exclusionAreaRepository;
        this.functionalZoneRepository = functionalZoneRepository;
        this.elementRepository = elementRepository;
        this.findingRepository = findingRepository;
        this.userRepository = userRepository;
        this.geometryUtil = geometryUtil;
        this.conflictDetectionService = conflictDetectionService;
        this.layoutCommandHistory = layoutCommandHistory;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public InfrastructureDtos.InfrastructureWorkspaceDto getWorkspace(Long eventId,
                                                                      boolean canEdit,
                                                                      boolean canReviewFindings) {
        VenueDtos.VenueWorkspaceDto base = venueService.getWorkspaceState(eventId, canEdit);
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);
        List<ExclusionArea> exclusions = plan != null
                ? exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<FunctionalZone> zones = plan != null
                ? functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<InfrastructureElement> elements = plan != null
                ? elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<ConflictFinding> storedFindings = plan != null
                ? findingRepository.findByVenuePlanIdOrderByActiveDescIdAsc(plan.getId())
                : List.of();
        return buildWorkspaceDto(base, plan, exclusions, zones, elements, storedFindings,
                canEdit, canReviewFindings, null);
    }

    @Transactional
    public InfrastructureDtos.InfrastructureWorkspaceDto createElement(Long eventId,
                                                                       InfrastructureDtos.CreateElementRequest request,
                                                                       Long actorUserId,
                                                                       boolean canEdit,
                                                                       boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        AppUser actor = resolveUser(actorUserId);

        List<InfrastructureElement> existing = elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        layoutCommandHistory.ensureBaselineRevision(plan, existing, actor);

        InfrastructureType type = request.elementType();
        double centerX = requireFinite(request.centerX(), "Center X");
        double centerY = requireFinite(request.centerY(), "Center Y");
        double widthM = request.widthM() != null ? request.widthM() : type.getDefaultWidthM();
        double heightM = request.heightM() != null ? request.heightM() : type.getDefaultHeightM();
        double rotationDeg = request.rotationDeg() != null
                ? geometryUtil.normalizeRotationDeg(request.rotationDeg()) : 0.0;
        validateDimensions(widthM, heightM);

        Double throughput = resolveThroughput(type, request.throughputPersonsPerMin(), type.getDefaultThroughputPerMin());
        boolean active = request.active() == null || request.active();
        String label = resolveLabel(request.label(), type, existing);
        String metaJson = buildMetadataJson(type, request.clearanceBufferM(), request.linearAligned(), request.notes());
        int nextSort = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getSortOrder() + 1;

        Polygon footprint = buildFootprint(centerX, centerY, widthM, heightM, rotationDeg);

        InfrastructureElement element = new InfrastructureElement();
        element.setVenuePlan(plan);
        element.setElementUid(UUID.randomUUID().toString());
        element.setElementType(type);
        element.setLabel(label);
        element.setCenterX(geometryUtil.round2(centerX));
        element.setCenterY(geometryUtil.round2(centerY));
        element.setWidthM(geometryUtil.round2(widthM));
        element.setHeightM(geometryUtil.round2(heightM));
        element.setRotationDeg(rotationDeg);
        element.setFootprintWkt(geometryUtil.toWkt(footprint));
        element.setThroughputPersonsPerMin(throughput);
        element.setActive(active);
        element.setMetadataJson(metaJson);
        element.setSortOrder(nextSort);
        InfrastructureElement saved = elementRepository.saveAndFlush(element);

        List<InfrastructureElement> allElements = elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        layoutCommandHistory.recordRevision(plan, "PLACE", "Place " + saved.getLabel(), allElements, actor);

        return syncAndBuildWorkspace(eventId, plan, allElements, canEdit, canReviewFindings, saved.getId());
    }

    @Transactional
    public InfrastructureDtos.InfrastructureWorkspaceDto updateElement(Long eventId,
                                                                       Long elementId,
                                                                       InfrastructureDtos.UpdateElementRequest request,
                                                                       Long actorUserId,
                                                                       boolean canEdit,
                                                                       boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        AppUser actor = resolveUser(actorUserId);

        List<InfrastructureElement> existing = elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        layoutCommandHistory.ensureBaselineRevision(plan, existing, actor);

        InfrastructureElement element = elementRepository.findByIdAndVenuePlanId(elementId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Infrastructure element not found."));

        double centerX = requireFinite(request.centerX(), "Center X");
        double centerY = requireFinite(request.centerY(), "Center Y");
        double widthM = requireFinite(request.widthM(), "Width");
        double heightM = requireFinite(request.heightM(), "Height");
        double rotationDeg = geometryUtil.normalizeRotationDeg(requireFinite(request.rotationDeg(), "Rotation"));
        validateDimensions(widthM, heightM);

        InfrastructureType type = element.getElementType();
        String label = (request.label() != null && !request.label().isBlank())
                ? request.label().trim() : element.getLabel();
        Double throughput = resolveThroughput(type, request.throughputPersonsPerMin(), element.getThroughputPersonsPerMin());
        boolean active = request.active() != null ? request.active() : element.isActive();

        InfrastructureDtos.ElementMetadata prevMeta = InfrastructureDtos.ElementMetadata.parse(
                objectMapper, element.getMetadataJson());
        Double clearance = request.clearanceBufferM() != null
                ? request.clearanceBufferM() : prevMeta.clearanceBufferM();
        Boolean linearAligned = request.linearAligned() != null
                ? request.linearAligned() : prevMeta.linearAligned();
        String notes = request.notes() != null ? trimToNull(request.notes()) : prevMeta.notes();
        String metaJson = buildMetadataJson(type, clearance, linearAligned, notes);

        String cmdType = inferCommandType(request.commandType(), element, centerX, centerY, widthM, heightM, rotationDeg);
        Polygon footprint = buildFootprint(centerX, centerY, widthM, heightM, rotationDeg);

        element.setLabel(label);
        element.setCenterX(geometryUtil.round2(centerX));
        element.setCenterY(geometryUtil.round2(centerY));
        element.setWidthM(geometryUtil.round2(widthM));
        element.setHeightM(geometryUtil.round2(heightM));
        element.setRotationDeg(rotationDeg);
        element.setFootprintWkt(geometryUtil.toWkt(footprint));
        element.setThroughputPersonsPerMin(throughput);
        element.setActive(active);
        element.setMetadataJson(metaJson);
        InfrastructureElement saved = elementRepository.saveAndFlush(element);

        List<InfrastructureElement> allElements = elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        layoutCommandHistory.recordRevision(plan, cmdType, formatCommandSummary(cmdType, saved.getLabel()), allElements, actor);

        return syncAndBuildWorkspace(eventId, plan, allElements, canEdit, canReviewFindings, saved.getId());
    }

    @Transactional
    public InfrastructureDtos.InfrastructureWorkspaceDto duplicateElement(Long eventId,
                                                                          Long elementId,
                                                                          Long actorUserId,
                                                                          boolean canEdit,
                                                                          boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        AppUser actor = resolveUser(actorUserId);

        List<InfrastructureElement> existing = elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        layoutCommandHistory.ensureBaselineRevision(plan, existing, actor);

        InfrastructureElement source = elementRepository.findByIdAndVenuePlanId(elementId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Infrastructure element not found."));

        double newCenterX = geometryUtil.round2(source.getCenterX() + 2.0);
        double newCenterY = geometryUtil.round2(source.getCenterY() + 2.0);
        int nextSort = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getSortOrder() + 1;
        String copyLabel = buildCopyLabel(source.getLabel());
        Polygon footprint = buildFootprint(
                newCenterX, newCenterY, source.getWidthM(), source.getHeightM(), source.getRotationDeg());

        InfrastructureElement copy = new InfrastructureElement();
        copy.setVenuePlan(plan);
        copy.setElementUid(UUID.randomUUID().toString());
        copy.setElementType(source.getElementType());
        copy.setLabel(copyLabel);
        copy.setCenterX(newCenterX);
        copy.setCenterY(newCenterY);
        copy.setWidthM(source.getWidthM());
        copy.setHeightM(source.getHeightM());
        copy.setRotationDeg(source.getRotationDeg());
        copy.setFootprintWkt(geometryUtil.toWkt(footprint));
        copy.setThroughputPersonsPerMin(source.getThroughputPersonsPerMin());
        copy.setActive(source.isActive());
        copy.setMetadataJson(source.getMetadataJson());
        copy.setSortOrder(nextSort);
        InfrastructureElement saved = elementRepository.saveAndFlush(copy);

        List<InfrastructureElement> allElements = elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        layoutCommandHistory.recordRevision(plan, "DUPLICATE", "Duplicate " + source.getLabel(), allElements, actor);

        return syncAndBuildWorkspace(eventId, plan, allElements, canEdit, canReviewFindings, saved.getId());
    }

    @Transactional
    public InfrastructureDtos.InfrastructureWorkspaceDto deleteElement(Long eventId,
                                                                       Long elementId,
                                                                       Long actorUserId,
                                                                       boolean canEdit,
                                                                       boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        AppUser actor = resolveUser(actorUserId);

        List<InfrastructureElement> existing = elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        layoutCommandHistory.ensureBaselineRevision(plan, existing, actor);

        InfrastructureElement target = elementRepository.findByIdAndVenuePlanId(elementId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Infrastructure element not found."));
        String label = target.getLabel();

        elementRepository.delete(target);
        elementRepository.flush();

        List<InfrastructureElement> remaining = elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        layoutCommandHistory.recordRevision(plan, "DELETE", "Delete " + label, remaining, actor);

        return syncAndBuildWorkspace(eventId, plan, remaining, canEdit, canReviewFindings, null);
    }

    @Transactional
    public InfrastructureDtos.InfrastructureWorkspaceDto undo(Long eventId,
                                                              boolean canEdit,
                                                              boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        LayoutCommandHistory.UndoRedoOutcome outcome = layoutCommandHistory.undo(plan);
        List<InfrastructureElement> restored = applySnapshotToPlan(plan, outcome.elements());
        return syncAndBuildWorkspace(eventId, plan, restored, canEdit, canReviewFindings, null);
    }

    @Transactional
    public InfrastructureDtos.InfrastructureWorkspaceDto redo(Long eventId,
                                                              boolean canEdit,
                                                              boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        LayoutCommandHistory.UndoRedoOutcome outcome = layoutCommandHistory.redo(plan);
        List<InfrastructureElement> restored = applySnapshotToPlan(plan, outcome.elements());
        return syncAndBuildWorkspace(eventId, plan, restored, canEdit, canReviewFindings, null);
    }

    @Transactional
    public InfrastructureDtos.InfrastructureWorkspaceDto runConflictCheck(Long eventId,
                                                                          boolean canEdit,
                                                                          boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        List<InfrastructureElement> elements = elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        return syncAndBuildWorkspace(eventId, plan, elements, canEdit, canReviewFindings, null);
    }

    @Transactional
    public InfrastructureDtos.InfrastructureWorkspaceDto reviewFinding(Long eventId,
                                                                       Long findingId,
                                                                       Long reviewerUserId,
                                                                       InfrastructureDtos.ReviewFindingRequest request,
                                                                       boolean canEdit,
                                                                       boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        if (reviewerUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Reviewer account not found.");
        }
        AppUser reviewer = userRepository.findById(reviewerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Reviewer account not found."));

        ConflictFinding finding = findingRepository.findByIdAndVenuePlanId(findingId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conflict finding not found."));

        finding.setReviewStatus(request.reviewStatus());
        finding.setReviewerNote(trimToNull(request.reviewerNote()));
        finding.setReviewedBy(reviewer);
        finding.setReviewedAt(Instant.now());
        findingRepository.saveAndFlush(finding);

        List<InfrastructureElement> elements = elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<ExclusionArea> exclusions = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<FunctionalZone> zones = functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<ConflictFinding> storedFindings = findingRepository.findByVenuePlanIdOrderByActiveDescIdAsc(plan.getId());
        VenueDtos.VenueWorkspaceDto base = venueService.getWorkspaceState(eventId, canEdit);

        return buildWorkspaceDto(base, plan, exclusions, zones, elements, storedFindings,
                canEdit, canReviewFindings, null);
    }

    private List<InfrastructureElement> applySnapshotToPlan(VenuePlan plan,
                                                              List<InfrastructureDtos.ElementSnapshot> snapshots) {
        List<InfrastructureElement> existing = elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        Map<String, InfrastructureElement> byUid = new HashMap<>();
        for (InfrastructureElement el : existing) {
            byUid.put(el.getElementUid(), el);
        }

        Set<String> targetUids = new HashSet<>();
        for (InfrastructureDtos.ElementSnapshot snap : snapshots) {
            if (snap != null && snap.elementUid() != null) {
                targetUids.add(snap.elementUid());
            }
        }

        List<InfrastructureElement> toDelete = existing.stream()
                .filter(el -> !targetUids.contains(el.getElementUid()))
                .toList();
        if (!toDelete.isEmpty()) {
            elementRepository.deleteAll(toDelete);
            elementRepository.flush();
        }

        List<InfrastructureElement> toSave = new ArrayList<>(snapshots.size());
        int sortIndex = 1;
        for (InfrastructureDtos.ElementSnapshot snap : snapshots) {
            if (snap == null || snap.elementType() == null || snap.elementUid() == null) {
                continue;
            }
            InfrastructureElement target = byUid.get(snap.elementUid());
            if (target == null) {
                target = new InfrastructureElement();
                target.setVenuePlan(plan);
                target.setElementUid(snap.elementUid());
            }
            target.setElementType(snap.elementType());
            target.setLabel(snap.label());
            target.setCenterX(snap.centerX());
            target.setCenterY(snap.centerY());
            target.setWidthM(snap.widthM());
            target.setHeightM(snap.heightM());
            target.setRotationDeg(snap.rotationDeg());
            Polygon footprint = buildFootprint(
                    snap.centerX(), snap.centerY(), snap.widthM(), snap.heightM(), snap.rotationDeg());
            target.setFootprintWkt(geometryUtil.toWkt(footprint));
            target.setThroughputPersonsPerMin(snap.throughputPersonsPerMin());
            target.setActive(snap.active());
            target.setMetadataJson(snap.metadataJson());
            target.setSortOrder(snap.sortOrder() > 0 ? snap.sortOrder() : sortIndex);
            sortIndex++;
            toSave.add(target);
        }

        elementRepository.saveAllAndFlush(toSave);
        return elementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
    }

    private InfrastructureDtos.InfrastructureWorkspaceDto syncAndBuildWorkspace(Long eventId,
                                                                               VenuePlan plan,
                                                                               List<InfrastructureElement> elements,
                                                                               boolean canEdit,
                                                                               boolean canReviewFindings,
                                                                               Long lastModifiedElementId) {
        List<ExclusionArea> exclusions = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<FunctionalZone> zones = functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        Polygon boundary = plan.getBoundaryWkt() != null && !plan.getBoundaryWkt().isBlank()
                ? geometryUtil.parsePolygon(plan.getBoundaryWkt())
                : null;

        List<ConflictDetectionService.DetectedConflict> detected =
                conflictDetectionService.detect(boundary, exclusions, zones, elements);
        syncFindings(plan, detected);

        List<ConflictFinding> storedFindings = findingRepository.findByVenuePlanIdOrderByActiveDescIdAsc(plan.getId());
        VenueDtos.VenueWorkspaceDto base = venueService.getWorkspaceState(eventId, canEdit);
        return buildWorkspaceDto(base, plan, exclusions, zones, elements, storedFindings,
                canEdit, canReviewFindings, lastModifiedElementId);
    }

    private void syncFindings(VenuePlan plan, List<ConflictDetectionService.DetectedConflict> detected) {
        List<ConflictFinding> existing = findingRepository.findByVenuePlanIdOrderByActiveDescIdAsc(plan.getId());
        Map<String, ConflictFinding> existingByKey = new HashMap<>();
        for (ConflictFinding f : existing) {
            existingByKey.put(f.getFindingKey(), f);
        }

        Set<String> activeKeys = new HashSet<>();
        List<ConflictFinding> toSave = new ArrayList<>();
        for (ConflictDetectionService.DetectedConflict dc : detected) {
            activeKeys.add(dc.findingKey());
            ConflictFinding finding = existingByKey.get(dc.findingKey());
            if (finding == null) {
                finding = new ConflictFinding();
                finding.setVenuePlan(plan);
                finding.setFindingKey(dc.findingKey());
                finding.setReviewStatus(FindingReviewStatus.OPEN);
            } else if (!finding.isActive()) {
                finding.setReviewStatus(FindingReviewStatus.OPEN);
            }
            finding.setConflictType(dc.conflictType());
            finding.setSeverity(dc.severity());
            finding.setTitle(truncate(dc.title(), 200));
            finding.setDescription(truncate(dc.description(), 1000));
            finding.setMeasuredValue(truncate(dc.measuredValue(), 120));
            finding.setLocationX(dc.locationX() != null ? geometryUtil.round2(dc.locationX()) : null);
            finding.setLocationY(dc.locationY() != null ? geometryUtil.round2(dc.locationY()) : null);
            finding.setInvolvedElementIds(joinIds(dc.involvedElementIds()));
            finding.setActive(true);
            finding.setResolvedAt(null);
            toSave.add(finding);
        }

        List<ConflictFinding> toDelete = new ArrayList<>();
        Instant now = Instant.now();
        for (ConflictFinding old : existing) {
            if (activeKeys.contains(old.getFindingKey())) {
                continue;
            }
            boolean hasReviewHistory = old.getReviewerNote() != null
                    || old.getReviewedBy() != null
                    || old.getReviewStatus() != FindingReviewStatus.OPEN;
            if (hasReviewHistory) {
                if (old.isActive()) {
                    old.setActive(false);
                    old.setResolvedAt(now);
                    toSave.add(old);
                }
            } else {
                toDelete.add(old);
            }
        }

        if (!toDelete.isEmpty()) {
            findingRepository.deleteAll(toDelete);
        }
        if (!toSave.isEmpty()) {
            findingRepository.saveAll(toSave);
        }
        findingRepository.flush();
    }

    private InfrastructureDtos.InfrastructureWorkspaceDto buildWorkspaceDto(VenueDtos.VenueWorkspaceDto base,
                                                                            VenuePlan plan,
                                                                            List<ExclusionArea> exclusions,
                                                                            List<FunctionalZone> zones,
                                                                            List<InfrastructureElement> elements,
                                                                            List<ConflictFinding> storedFindings,
                                                                            boolean canEdit,
                                                                            boolean canReviewFindings,
                                                                            Long lastModifiedElementId) {
        List<GridDtos.FunctionalZoneDto> zoneDtos = new ArrayList<>(zones.size());
        for (FunctionalZone zone : zones) {
            Geometry rawGeom = geometryUtil.parsePolygonal(zone.getPolygonWkt());
            double area = geometryUtil.polygonalArea(rawGeom);
            List<GeometryUtil.PolygonShape> shapes = geometryUtil.extractPolygonShapes(rawGeom);
            GeometryUtil.Point2D labelPt = geometryUtil.interiorPoint(rawGeom);
            zoneDtos.add(new GridDtos.FunctionalZoneDto(
                    zone.getId(),
                    zone.getZoneCode(),
                    zone.getName(),
                    zone.getZoneType(),
                    zone.getZoneType().getDisplayName(),
                    zone.getZoneType().getCapacityBucket(),
                    zone.getZoneType().isWalkable(),
                    zone.getZoneType().isHatched(),
                    zone.getColorHex(),
                    zone.getZoneType().getStrokeColor(),
                    zone.getPolygonWkt(),
                    shapes,
                    List.of(),
                    area,
                    area,
                    area,
                    zone.isIsolated(),
                    labelPt,
                    zone.getNotes()
            ));
        }

        List<InfrastructureDtos.InfrastructureElementDto> elementDtos = new ArrayList<>(elements.size());
        int activeCount = 0;
        int entryCount = 0;
        int exitPortalCount = 0;
        int emergencyLaneCount = 0;
        double entryThroughput = 0.0;
        double exitThroughput = 0.0;
        double emergencyExitThroughput = 0.0;

        for (InfrastructureElement el : elements) {
            InfrastructureType type = el.getElementType();
            InfrastructureDtos.ElementMetadata meta = InfrastructureDtos.ElementMetadata.parse(
                    objectMapper, el.getMetadataJson());
            Polygon footprint = parseOrBuildFootprint(el);
            List<GeometryUtil.Point2D> footprintPts = geometryUtil.extractOuterRing(footprint);
            double clearanceBufferM = type.isExitPortal()
                    ? meta.effectiveClearanceBufferM()
                    : InfrastructureDtos.DEFAULT_EXIT_CLEARANCE_METERS;
            List<GeometryUtil.Point2D> apronPts = List.of();
            if (type.isExitPortal()) {
                Geometry apronGeom = footprint.buffer(clearanceBufferM);
                if (apronGeom instanceof Polygon apronPoly) {
                    apronPts = geometryUtil.extractOuterRing(apronPoly);
                }
            }

            if (el.isActive()) {
                activeCount++;
                if (type == InfrastructureType.ENTRY_GATE) {
                    entryCount++;
                    if (el.getThroughputPersonsPerMin() != null) {
                        entryThroughput += el.getThroughputPersonsPerMin();
                    }
                } else if (type == InfrastructureType.EXIT_GATE) {
                    exitPortalCount++;
                    if (el.getThroughputPersonsPerMin() != null) {
                        exitThroughput += el.getThroughputPersonsPerMin();
                    }
                } else if (type == InfrastructureType.EMERGENCY_EXIT) {
                    exitPortalCount++;
                    if (el.getThroughputPersonsPerMin() != null) {
                        emergencyExitThroughput += el.getThroughputPersonsPerMin();
                    }
                } else if (type.isEmergencyLane()) {
                    emergencyLaneCount++;
                }
            }

            elementDtos.add(new InfrastructureDtos.InfrastructureElementDto(
                    el.getId(),
                    el.getElementUid(),
                    type,
                    type.getDisplayName(),
                    type.getShortCode(),
                    el.getLabel(),
                    el.getCenterX(),
                    el.getCenterY(),
                    el.getWidthM(),
                    el.getHeightM(),
                    el.getRotationDeg(),
                    geometryUtil.round2(el.getWidthM() * el.getHeightM()),
                    el.getThroughputPersonsPerMin(),
                    type.isSupportsThroughput(),
                    type.isExitPortal(),
                    type.isEmergencyLane(),
                    type.isRigidStructure(),
                    el.isActive(),
                    clearanceBufferM,
                    meta.isLinearAligned(),
                    meta.notes(),
                    type.getFillColor(),
                    type.getStrokeColor(),
                    el.getFootprintWkt(),
                    footprintPts,
                    apronPts
            ));
        }

        List<InfrastructureDtos.ConflictFindingDto> activeFindings = new ArrayList<>();
        List<InfrastructureDtos.ConflictFindingDto> historyFindings = new ArrayList<>();
        int criticalCount = 0;
        int highCount = 0;
        int lowCount = 0;
        int infoCount = 0;
        int openCount = 0;

        for (ConflictFinding cf : storedFindings) {
            InfrastructureDtos.ConflictFindingDto dto = toFindingDto(cf);
            if (cf.isActive()) {
                activeFindings.add(dto);
                switch (cf.getSeverity()) {
                    case CRITICAL -> criticalCount++;
                    case HIGH -> highCount++;
                    case LOW -> lowCount++;
                    case INFO -> infoCount++;
                }
                if (cf.getReviewStatus() == FindingReviewStatus.OPEN) {
                    openCount++;
                }
            } else {
                historyFindings.add(dto);
            }
        }

        activeFindings.sort(Comparator
                .comparingInt((InfrastructureDtos.ConflictFindingDto f) -> f.severity().sortRank())
                .thenComparing(InfrastructureDtos.ConflictFindingDto::findingKey));
        historyFindings.sort(Comparator
                .comparing(InfrastructureDtos.ConflictFindingDto::resolvedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(InfrastructureDtos.ConflictFindingDto::findingKey));

        InfrastructureDtos.InfrastructureSummaryDto summary = new InfrastructureDtos.InfrastructureSummaryDto(
                elements.size(),
                activeCount,
                entryCount,
                exitPortalCount,
                emergencyLaneCount,
                geometryUtil.round2(entryThroughput),
                geometryUtil.round2(exitThroughput),
                geometryUtil.round2(emergencyExitThroughput),
                criticalCount,
                highCount,
                lowCount,
                infoCount,
                openCount,
                historyFindings.size()
        );

        InfrastructureDtos.HistoryStateDto history = layoutCommandHistory.getHistoryState(
                plan != null ? plan.getId() : null);

        return new InfrastructureDtos.InfrastructureWorkspaceDto(
                base.eventId(),
                base.eventName(),
                base.expectedAttendance(),
                canEdit,
                canReviewFindings,
                base.venuePlan(),
                base.exclusions(),
                base.areaBreakdown(),
                base.vectorMapGeoJson(),
                List.copyOf(zoneDtos),
                List.copyOf(elementDtos),
                List.copyOf(activeFindings),
                List.copyOf(historyFindings),
                summary,
                history,
                lastModifiedElementId
        );
    }

    private InfrastructureDtos.ConflictFindingDto toFindingDto(ConflictFinding cf) {
        return new InfrastructureDtos.ConflictFindingDto(
                cf.getId(),
                cf.getFindingKey(),
                cf.getConflictType(),
                cf.getConflictType().getDisplayName(),
                cf.getSeverity(),
                cf.getTitle(),
                cf.getDescription(),
                cf.getMeasuredValue(),
                cf.getLocationX(),
                cf.getLocationY(),
                parseIds(cf.getInvolvedElementIds()),
                cf.isActive(),
                cf.getReviewStatus(),
                cf.getReviewStatus().getDisplayName(),
                cf.getReviewerNote(),
                cf.getReviewedBy() != null ? cf.getReviewedBy().getFullName() : null,
                cf.getReviewedAt(),
                cf.getResolvedAt()
        );
    }

    private Polygon buildFootprint(double centerX, double centerY, double widthM, double heightM, double rotationDeg) {
        try {
            return geometryUtil.buildRotatedRectangle(centerX, centerY, widthM, heightM, rotationDeg);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private Polygon parseOrBuildFootprint(InfrastructureElement el) {
        if (el.getFootprintWkt() != null && !el.getFootprintWkt().isBlank()) {
            try {
                return geometryUtil.parsePolygon(el.getFootprintWkt());
            } catch (IllegalArgumentException ignored) {
                // Rebuild below
            }
        }
        return geometryUtil.buildRotatedRectangle(
                el.getCenterX(), el.getCenterY(), el.getWidthM(), el.getHeightM(), el.getRotationDeg());
    }

    private double requireFinite(Double value, String label) {
        if (value == null || !Double.isFinite(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must be a finite number.");
        }
        return value;
    }

    private void validateDimensions(double widthM, double heightM) {
        if (widthM < 0.3 || widthM > 200.0 || heightM < 0.3 || heightM > 200.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Element width and height must be between 0.3 m and 200.0 m.");
        }
    }

    private Double resolveThroughput(InfrastructureType type, Double requested, Double fallback) {
        if (!type.isSupportsThroughput()) {
            return null;
        }
        Double candidate = requested != null ? requested : fallback;
        if (candidate == null) {
            return type.getDefaultThroughputPerMin();
        }
        if (!Double.isFinite(candidate) || candidate < 0.0 || candidate > 1000.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Throughput must be between 0 and 1000 persons/min.");
        }
        return geometryUtil.round2(candidate);
    }

    private String resolveLabel(String requested, InfrastructureType type, List<InfrastructureElement> existing) {
        if (requested != null && !requested.isBlank()) {
            return truncate(requested.trim(), 160);
        }
        long sameTypeCount = existing.stream().filter(e -> e.getElementType() == type).count();
        return type.getDisplayName() + " " + (sameTypeCount + 1);
    }

    private String buildCopyLabel(String sourceLabel) {
        String base = sourceLabel != null ? sourceLabel.trim() : "Element";
        if (base.endsWith("(Copy)")) {
            return truncate(base, 160);
        }
        return truncate(base + " (Copy)", 160);
    }

    private String buildMetadataJson(InfrastructureType type,
                                     Double clearanceBufferM,
                                     Boolean linearAligned,
                                     String notes) {
        Double effectiveClearance = type.isExitPortal()
                ? (clearanceBufferM != null ? geometryUtil.round2(clearanceBufferM)
                        : InfrastructureDtos.DEFAULT_EXIT_CLEARANCE_METERS)
                : null;
        Boolean effectiveLinear = type == InfrastructureType.BARRICADE ? Boolean.TRUE.equals(linearAligned) : null;
        String cleanNotes = trimToNull(notes);
        if (effectiveClearance == null && effectiveLinear == null && cleanNotes == null) {
            return null;
        }
        return InfrastructureDtos.ElementMetadata.write(
                objectMapper, new InfrastructureDtos.ElementMetadata(effectiveClearance, effectiveLinear, cleanNotes));
    }

    private String inferCommandType(String explicit,
                                    InfrastructureElement prev,
                                    double cx,
                                    double cy,
                                    double w,
                                    double h,
                                    double rot) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toUpperCase(Locale.ROOT);
        }
        boolean moved = Math.abs(prev.getCenterX() - cx) > 1e-4 || Math.abs(prev.getCenterY() - cy) > 1e-4;
        boolean resized = Math.abs(prev.getWidthM() - w) > 1e-4 || Math.abs(prev.getHeightM() - h) > 1e-4;
        boolean rotated = Math.abs(prev.getRotationDeg() - rot) > 1e-4;
        if (rotated && !moved && !resized) return "ROTATE";
        if (resized && !moved && !rotated) return "RESIZE";
        if (moved && !resized && !rotated) return "MOVE";
        return "UPDATE";
    }

    private String formatCommandSummary(String cmdType, String label) {
        return switch (cmdType) {
            case "MOVE" -> "Move " + label;
            case "RESIZE" -> "Resize " + label;
            case "ROTATE" -> "Rotate " + label;
            default -> "Update " + label;
        };
    }

    private AppUser resolveUser(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    private String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private List<Long> parseIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(Long.parseLong(trimmed));
                }
            } catch (NumberFormatException ignored) {
                // Skip malformed token
            }
        }
        return List.copyOf(result);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }
}
