package com.vyuha.grid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.capacity.CapacityReview;
import com.vyuha.capacity.CapacityReviewRepository;
import com.vyuha.capacity.CapacityReviewStatus;
import com.vyuha.common.GeometryUtil;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import com.vyuha.venue.AreaBreakdownService;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionAreaRepository;
import com.vyuha.venue.VenueDtos;
import com.vyuha.venue.VenuePlan;
import com.vyuha.venue.VenuePlanRepository;
import com.vyuha.venue.VenueService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates Phase 4 Grid Config & Functional Zone persistence, Undo/Redo layout sync,
 * clipped grid generation, and automated zone integrity checks.
 */
@Service
public class GridZoneService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final EventService eventService;
    private final VenueService venueService;
    private final VenuePlanRepository venuePlanRepository;
    private final ExclusionAreaRepository exclusionAreaRepository;
    private final VenueGridConfigRepository gridConfigRepository;
    private final FunctionalZoneRepository zoneRepository;
    private final CapacityReviewRepository capacityReviewRepository;
    private final GeometryUtil geometryUtil;
    private final AreaBreakdownService areaBreakdownService;
    private final GridGeneratorService gridGeneratorService;
    private final ZoneValidationService zoneValidationService;
    private final ObjectMapper objectMapper;

    public GridZoneService(EventService eventService,
                           VenueService venueService,
                           VenuePlanRepository venuePlanRepository,
                           ExclusionAreaRepository exclusionAreaRepository,
                           VenueGridConfigRepository gridConfigRepository,
                           FunctionalZoneRepository zoneRepository,
                           CapacityReviewRepository capacityReviewRepository,
                           GeometryUtil geometryUtil,
                           AreaBreakdownService areaBreakdownService,
                           GridGeneratorService gridGeneratorService,
                           ZoneValidationService zoneValidationService,
                           ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.venueService = venueService;
        this.venuePlanRepository = venuePlanRepository;
        this.exclusionAreaRepository = exclusionAreaRepository;
        this.gridConfigRepository = gridConfigRepository;
        this.zoneRepository = zoneRepository;
        this.capacityReviewRepository = capacityReviewRepository;
        this.geometryUtil = geometryUtil;
        this.areaBreakdownService = areaBreakdownService;
        this.gridGeneratorService = gridGeneratorService;
        this.zoneValidationService = zoneValidationService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public GridDtos.GridWorkspaceDto getGridWorkspace(Long eventId, boolean canEdit) {
        VenueDtos.VenueWorkspaceDto base = venueService.getWorkspaceState(eventId, canEdit);
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);
        List<ExclusionArea> exclusions = plan != null
                ? exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        VenueGridConfig config = plan != null
                ? gridConfigRepository.findByVenuePlanId(plan.getId()).orElse(null)
                : null;
        List<FunctionalZone> zones = plan != null
                ? zoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        return buildGridWorkspaceDto(base, plan, exclusions, config, zones);
    }

    @Transactional
    public GridDtos.GridWorkspaceDto updateGridConfig(Long eventId,
                                                      GridDtos.GridConfigRequest request,
                                                      boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        VenueGridConfig config = getOrCreateGridConfig(plan);

        double cellSize = request.cellSizeMeters() != null ? request.cellSizeMeters() : 5.0;
        double originX = request.originX() != null ? request.originX() : 0.0;
        double originY = request.originY() != null ? request.originY() : 0.0;
        double rotationDeg = request.rotationDeg() != null ? request.rotationDeg() : 0.0;

        try {
            gridGeneratorService.validateConfig(cellSize, originX, originY, rotationDeg);
            if (plan.getBoundaryWkt() != null && !plan.getBoundaryWkt().isBlank()) {
                Polygon boundary = geometryUtil.parsePolygon(plan.getBoundaryWkt());
                gridGeneratorService.generateClippedGrid(boundary, null, cellSize, originX, originY, rotationDeg);
            }
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }

        boolean changed = Math.abs(config.getCellSizeMeters() - cellSize) > 1e-9
                || Math.abs(config.getOriginX() - originX) > 1e-9
                || Math.abs(config.getOriginY() - originY) > 1e-9
                || Math.abs(config.getRotationDeg() - rotationDeg) > 1e-9;

        config.setCellSizeMeters(cellSize);
        config.setOriginX(originX);
        config.setOriginY(originY);
        config.setRotationDeg(rotationDeg);
        VenueGridConfig savedConfig = gridConfigRepository.save(config);

        List<FunctionalZone> zones = zoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        if (changed && !zones.isEmpty()) {
            for (FunctionalZone z : zones) {
                z.setGridCellRefsJson(null);
            }
            zones = zoneRepository.saveAll(zones);
        }

        List<ExclusionArea> exclusions = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        VenueDtos.VenueWorkspaceDto base = venueService.getWorkspaceState(eventId, canEdit);
        return buildGridWorkspaceDto(base, plan, exclusions, savedConfig, zones);
    }

    @Transactional
    public GridDtos.GridWorkspaceDto createZone(Long eventId,
                                                GridDtos.ZoneCreateRequest request,
                                                boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        Polygon boundary = requireBoundary(plan);
        VenueGridConfig config = getOrCreateGridConfig(plan);

        ResolvedGeometry resolved = resolveZoneGeometry(
                boundary, config, request.gridCellRefs(), request.points(), request.polygonWkt());

        String zoneCode = resolveZoneCodeForCreate(plan.getId(), request.zoneCode(), request.zoneType());
        List<FunctionalZone> existing = zoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        int nextSort = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getSortOrder() + 1;

        FunctionalZone zone = new FunctionalZone();
        zone.setVenuePlan(plan);
        zone.setZoneCode(zoneCode);
        zone.setName(request.name().trim());
        zone.setZoneType(request.zoneType());
        zone.setColorHex(request.zoneType().getFillColor());
        zone.setPolygonWkt(geometryUtil.toWkt(resolved.geometry()));
        zone.setGridCellRefsJson(serializeCellRefs(resolved.cellRefs()));
        zone.setNotes(trimToNull(request.notes()));
        zone.setSortOrder(nextSort);
        zoneRepository.save(zone);

        return refreshAndReturnWorkspace(eventId, plan, config, canEdit, true);
    }

    @Transactional
    public GridDtos.GridWorkspaceDto updateZone(Long eventId,
                                                Long zoneId,
                                                GridDtos.ZoneUpdateRequest request,
                                                boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        VenueGridConfig config = getOrCreateGridConfig(plan);

        FunctionalZone zone = zoneRepository.findByIdAndVenuePlanId(zoneId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Functional zone not found."));

        String zoneCode = normalizeZoneCode(request.zoneCode());
        if (zoneCode == null) {
            zoneCode = zone.getZoneCode();
        } else if (zoneRepository.existsByVenuePlanIdAndZoneCodeIgnoreCaseAndIdNot(plan.getId(), zoneCode, zoneId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Zone code \"" + zoneCode + "\" is already in use.");
        }

        boolean geometryOrTypeChanged = zone.getZoneType() != request.zoneType();
        zone.setZoneCode(zoneCode);
        zone.setName(request.name().trim());
        zone.setZoneType(request.zoneType());
        zone.setColorHex(request.zoneType().getFillColor());
        zone.setNotes(trimToNull(request.notes()));

        if ((request.points() != null && !request.points().isEmpty())
                || (request.polygonWkt() != null && !request.polygonWkt().isBlank())) {
            Polygon boundary = requireBoundary(plan);
            ResolvedGeometry resolved = resolveZoneGeometry(boundary, config, null, request.points(), request.polygonWkt());
            zone.setPolygonWkt(geometryUtil.toWkt(resolved.geometry()));
            zone.setGridCellRefsJson(null);
            geometryOrTypeChanged = true;
        }
        zoneRepository.save(zone);

        return refreshAndReturnWorkspace(eventId, plan, config, canEdit, geometryOrTypeChanged);
    }

    @Transactional
    public GridDtos.GridWorkspaceDto mutateZoneCells(Long eventId,
                                                     Long zoneId,
                                                     GridDtos.ZoneCellsMutationRequest request,
                                                     boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        Polygon boundary = requireBoundary(plan);
        VenueGridConfig config = getOrCreateGridConfig(plan);

        FunctionalZone zone = zoneRepository.findByIdAndVenuePlanId(zoneId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Functional zone not found."));

        String action = request.action().trim().toUpperCase(Locale.ROOT);
        if (!"ADD".equals(action) && !"REMOVE".equals(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mutation action must be ADD or REMOVE.");
        }

        List<String> existingRefs = deserializeCellRefs(zone.getGridCellRefsJson());
        try {
            if (!existingRefs.isEmpty()) {
                Set<String> merged = new LinkedHashSet<>(existingRefs);
                if ("ADD".equals(action)) {
                    merged.addAll(request.gridCellRefs());
                } else {
                    for (String r : request.gridCellRefs()) {
                        GridGeneratorService.CellIndex idx = GridGeneratorService.parseCellRef(r);
                        if (idx != null) {
                            merged.remove(GridGeneratorService.formatCellRef(idx.col(), idx.row()));
                        }
                    }
                }
                if (merged.isEmpty()) {
                    throw new IllegalArgumentException("Zone has no remaining cells. Delete the zone instead.");
                }
                GridGeneratorService.CellUnionResult unioned = gridGeneratorService.unionSelectedCells(
                        boundary, config.getCellSizeMeters(), config.getOriginX(),
                        config.getOriginY(), config.getRotationDeg(), new ArrayList<>(merged));
                zone.setPolygonWkt(geometryUtil.toWkt(unioned.geometry()));
                zone.setGridCellRefsJson(serializeCellRefs(unioned.validCellRefs()));
            } else {
                Geometry currentGeom = geometryUtil.parsePolygonal(zone.getPolygonWkt());
                GridGeneratorService.CellUnionResult delta = gridGeneratorService.unionSelectedCells(
                        boundary, config.getCellSizeMeters(), config.getOriginX(),
                        config.getOriginY(), config.getRotationDeg(), request.gridCellRefs());
                Geometry nextGeom = "ADD".equals(action)
                        ? geometryUtil.unionPolygons(List.of(currentGeom, delta.geometry()))
                        : currentGeom.difference(delta.geometry());
                geometryUtil.validatePolygonalGeometry(nextGeom);
                zone.setPolygonWkt(geometryUtil.toWkt(nextGeom));
            }
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }

        zoneRepository.save(zone);
        return refreshAndReturnWorkspace(eventId, plan, config, canEdit, true);
    }

    @Transactional
    public GridDtos.GridWorkspaceDto deleteZone(Long eventId, Long zoneId, boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        VenueGridConfig config = getOrCreateGridConfig(plan);

        FunctionalZone zone = zoneRepository.findByIdAndVenuePlanId(zoneId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Functional zone not found."));
        zoneRepository.delete(zone);
        zoneRepository.flush();

        return refreshAndReturnWorkspace(eventId, plan, config, canEdit, true);
    }

    @Transactional
    public GridDtos.GridWorkspaceDto syncZoneLayout(Long eventId,
                                                    GridDtos.ZoneLayoutSyncRequest request,
                                                    boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        VenueGridConfig config = getOrCreateGridConfig(plan);

        if (request != null && request.gridConfig() != null) {
            GridDtos.GridConfigRequest gc = request.gridConfig();
            double cellSize = gc.cellSizeMeters() != null ? gc.cellSizeMeters() : config.getCellSizeMeters();
            double originX = gc.originX() != null ? gc.originX() : 0.0;
            double originY = gc.originY() != null ? gc.originY() : 0.0;
            double rotationDeg = gc.rotationDeg() != null ? gc.rotationDeg() : 0.0;
            try {
                gridGeneratorService.validateConfig(cellSize, originX, originY, rotationDeg);
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
            }
            config.setCellSizeMeters(cellSize);
            config.setOriginX(originX);
            config.setOriginY(originY);
            config.setRotationDeg(rotationDeg);
            config = gridConfigRepository.save(config);
        }

        List<FunctionalZone> existingZones = zoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        Map<Long, FunctionalZone> existingById = new HashMap<>();
        Map<String, FunctionalZone> existingByCode = new HashMap<>();
        for (FunctionalZone ez : existingZones) {
            existingById.put(ez.getId(), ez);
            existingByCode.put(ez.getZoneCode().toUpperCase(Locale.ROOT), ez);
        }

        List<GridDtos.ZoneSyncItem> incoming = request != null && request.zones() != null
                ? request.zones()
                : List.of();

        Set<Long> matchedIds = new HashSet<>();
        List<FunctionalZone> matchedUpdates = new ArrayList<>();
        List<GridDtos.ZoneSyncItem> unmatchedNew = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();

        int order = 1;
        for (GridDtos.ZoneSyncItem item : incoming) {
            if (item == null || item.polygonWkt() == null || item.polygonWkt().isBlank() || item.zoneType() == null) {
                continue;
            }
            try {
                geometryUtil.parsePolygonal(item.polygonWkt());
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
            }

            String code = normalizeZoneCode(item.zoneCode());
            if (code == null) {
                code = item.zoneType().getCodePrefix() + "-" + order;
            }
            if (!seenCodes.add(code)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate zone code in layout: " + code);
            }

            FunctionalZone target = null;
            if (item.id() != null && existingById.containsKey(item.id()) && !matchedIds.contains(item.id())) {
                target = existingById.get(item.id());
            } else if (existingByCode.containsKey(code) && !matchedIds.contains(existingByCode.get(code).getId())) {
                target = existingByCode.get(code);
            }

            if (target != null) {
                matchedIds.add(target.getId());
                applySyncItem(target, item, code, order);
                matchedUpdates.add(target);
            } else {
                unmatchedNew.add(new GridDtos.ZoneSyncItem(
                        null, code, item.name(), item.zoneType(), item.notes(), item.polygonWkt(), item.gridCellRefs()));
            }
            order++;
        }

        List<FunctionalZone> toDelete = existingZones.stream()
                .filter(ez -> !matchedIds.contains(ez.getId()))
                .toList();
        if (!toDelete.isEmpty()) {
            zoneRepository.deleteAll(toDelete);
            zoneRepository.flush();
        }

        // Temporarily clear codes if codes swapped between matched zones before saving final codes
        if (!matchedUpdates.isEmpty()) {
            zoneRepository.saveAll(matchedUpdates);
            zoneRepository.flush();
        }

        List<FunctionalZone> toCreate = new ArrayList<>();
        int nextOrder = matchedUpdates.size() + 1;
        for (GridDtos.ZoneSyncItem item : unmatchedNew) {
            FunctionalZone created = new FunctionalZone();
            created.setVenuePlan(plan);
            applySyncItem(created, item, item.zoneCode(), nextOrder++);
            toCreate.add(created);
        }
        if (!toCreate.isEmpty()) {
            zoneRepository.saveAll(toCreate);
        }

        return refreshAndReturnWorkspace(eventId, plan, config, canEdit, true);
    }

    private void applySyncItem(FunctionalZone target,
                               GridDtos.ZoneSyncItem item,
                               String code,
                               int sortOrder) {
        Geometry geom = geometryUtil.parsePolygonal(item.polygonWkt());
        target.setZoneCode(code);
        target.setName(item.name() != null && !item.name().isBlank() ? item.name().trim() : code);
        target.setZoneType(item.zoneType());
        target.setColorHex(item.zoneType().getFillColor());
        target.setPolygonWkt(geometryUtil.toWkt(geom));
        target.setGridCellRefsJson(serializeCellRefs(item.gridCellRefs()));
        target.setNotes(trimToNull(item.notes()));
        target.setSortOrder(sortOrder);
    }

    private GridDtos.GridWorkspaceDto refreshAndReturnWorkspace(Long eventId,
                                                              VenuePlan plan,
                                                              VenueGridConfig config,
                                                              boolean canEdit,
                                                              boolean demoteCapacityVerification) {
        List<ExclusionArea> exclusions = exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<FunctionalZone> zones = zoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());

        Polygon boundary = plan.getBoundaryWkt() != null && !plan.getBoundaryWkt().isBlank()
                ? geometryUtil.parsePolygon(plan.getBoundaryWkt())
                : null;
        GridDtos.ZoneValidationResult validation = zoneValidationService.validate(boundary, exclusions, zones);

        Set<Long> isolatedIds = new HashSet<>(validation.isolatedZoneIds());
        boolean isoChanged = false;
        for (FunctionalZone z : zones) {
            boolean shouldBeIsolated = isolatedIds.contains(z.getId());
            if (z.isIsolated() != shouldBeIsolated) {
                z.setIsolated(shouldBeIsolated);
                isoChanged = true;
            }
        }
        if (isoChanged) {
            zones = zoneRepository.saveAll(zones);
        }

        if (demoteCapacityVerification) {
            capacityReviewRepository.findByEventId(eventId).ifPresent(review -> {
                if (review.getReviewStatus() == CapacityReviewStatus.REVIEWER_VERIFIED) {
                    review.setReviewStatus(CapacityReviewStatus.PENDING_REVIEW);
                    review.setVerifiedBy(null);
                    review.setVerifiedAt(null);
                    capacityReviewRepository.save(review);
                }
            });
        }

        VenueDtos.VenueWorkspaceDto base = venueService.getWorkspaceState(eventId, canEdit);
        return buildGridWorkspaceDto(base, plan, exclusions, config, zones, validation);
    }

    private GridDtos.GridWorkspaceDto buildGridWorkspaceDto(VenueDtos.VenueWorkspaceDto base,
                                                            VenuePlan plan,
                                                            List<ExclusionArea> exclusions,
                                                            VenueGridConfig config,
                                                            List<FunctionalZone> zones) {
        Polygon boundary = plan != null && plan.getBoundaryWkt() != null && !plan.getBoundaryWkt().isBlank()
                ? geometryUtil.parsePolygon(plan.getBoundaryWkt())
                : null;
        GridDtos.ZoneValidationResult validation = zoneValidationService.validate(boundary, exclusions, zones);
        return buildGridWorkspaceDto(base, plan, exclusions, config, zones, validation);
    }

    private GridDtos.GridWorkspaceDto buildGridWorkspaceDto(VenueDtos.VenueWorkspaceDto base,
                                                            VenuePlan plan,
                                                            List<ExclusionArea> exclusions,
                                                            VenueGridConfig config,
                                                            List<FunctionalZone> zones,
                                                            GridDtos.ZoneValidationResult validation) {
        double cellSize = config != null ? config.getCellSizeMeters() : 5.0;
        double originX = config != null ? config.getOriginX() : 0.0;
        double originY = config != null ? config.getOriginY() : 0.0;
        double rotationDeg = config != null ? config.getRotationDeg() : 0.0;

        Polygon boundary = plan != null && plan.getBoundaryWkt() != null && !plan.getBoundaryWkt().isBlank()
                ? geometryUtil.parsePolygon(plan.getBoundaryWkt())
                : null;
        Geometry usableGeom = boundary != null
                ? areaBreakdownService.computeUsableGeometry(boundary, exclusions)
                : geometryUtil.getGeometryFactory().createGeometryCollection();

        GridDtos.GeneratedGridResult gridResult = gridGeneratorService.generateClippedGrid(
                boundary, usableGeom, cellSize, originX, originY, rotationDeg);

        GridDtos.GridConfigDto configDto = new GridDtos.GridConfigDto(
                config != null ? config.getId() : null,
                cellSize,
                originX,
                originY,
                rotationDeg,
                gridResult.totalCells(),
                gridResult.partialCells(),
                gridResult.totalClippedAreaSqm(),
                gridResult.totalUsableAreaSqm()
        );

        List<GridDtos.FunctionalZoneDto> zoneDtos = new ArrayList<>(zones.size());
        for (int i = 0; i < zones.size(); i++) {
            FunctionalZone zone = zones.get(i);
            Geometry rawGeom = geometryUtil.parsePolygonal(zone.getPolygonWkt());
            double rawArea = geometryUtil.polygonalArea(rawGeom);
            double clippedArea = i < validation.perZoneClippedAreasSqm().size()
                    ? validation.perZoneClippedAreasSqm().get(i)
                    : rawArea;
            double usableArea = i < validation.perZoneUsableAreasSqm().size()
                    ? validation.perZoneUsableAreasSqm().get(i)
                    : clippedArea;
            List<GeometryUtil.PolygonShape> shapes = geometryUtil.extractPolygonShapes(rawGeom);
            GeometryUtil.Point2D labelPt = geometryUtil.interiorPoint(rawGeom);
            boolean isIso = validation.isolatedZoneIds().contains(zone.getId());

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
                    deserializeCellRefs(zone.getGridCellRefsJson()),
                    rawArea,
                    clippedArea,
                    usableArea,
                    isIso,
                    labelPt,
                    zone.getNotes()
            ));
        }

        return new GridDtos.GridWorkspaceDto(
                base.eventId(),
                base.eventName(),
                base.expectedAttendance(),
                base.canEdit(),
                base.venuePlan(),
                base.exclusions(),
                base.areaBreakdown(),
                base.vectorMapGeoJson(),
                configDto,
                gridResult.cells(),
                List.copyOf(zoneDtos),
                validation.coverage(),
                validation.findings()
        );
    }

    private ResolvedGeometry resolveZoneGeometry(Polygon boundary,
                                                 VenueGridConfig config,
                                                 List<String> gridCellRefs,
                                                 List<GeometryUtil.Point2D> points,
                                                 String polygonWkt) {
        try {
            Geometry geom;
            List<String> validRefs = List.of();
            if (gridCellRefs != null && !gridCellRefs.isEmpty()) {
                GridGeneratorService.CellUnionResult res = gridGeneratorService.unionSelectedCells(
                        boundary, config.getCellSizeMeters(), config.getOriginX(),
                        config.getOriginY(), config.getRotationDeg(), gridCellRefs);
                geom = res.geometry();
                validRefs = res.validCellRefs();
            } else if (points != null && !points.isEmpty()) {
                geom = geometryUtil.buildPolygon(points);
            } else if (polygonWkt != null && !polygonWkt.isBlank()) {
                geom = geometryUtil.parsePolygonal(polygonWkt);
            } else {
                throw new IllegalArgumentException("Provide selected grid cells or polygon vertices for the zone.");
            }

            if (geometryUtil.polygonalArea(boundary.intersection(geom)) < GeometryUtil.MIN_POLYGON_AREA_SQM) {
                throw new IllegalArgumentException("Zone geometry must lie inside the outer venue boundary.");
            }
            return new ResolvedGeometry(geom, validRefs);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private Polygon requireBoundary(VenuePlan plan) {
        if (plan.getBoundaryWkt() == null || plan.getBoundaryWkt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Draw the outer venue boundary in Site Plan mode before defining zones.");
        }
        return geometryUtil.parsePolygon(plan.getBoundaryWkt());
    }

    private VenueGridConfig getOrCreateGridConfig(VenuePlan plan) {
        return gridConfigRepository.findByVenuePlanId(plan.getId())
                .orElseGet(() -> {
                    VenueGridConfig cfg = new VenueGridConfig();
                    cfg.setVenuePlan(plan);
                    cfg.setCellSizeMeters(5.0);
                    cfg.setOriginX(0.0);
                    cfg.setOriginY(0.0);
                    cfg.setRotationDeg(0.0);
                    return gridConfigRepository.save(cfg);
                });
    }

    private String resolveZoneCodeForCreate(Long venuePlanId, String requestedCode, ZoneType type) {
        String normalized = normalizeZoneCode(requestedCode);
        if (normalized != null) {
            if (zoneRepository.existsByVenuePlanIdAndZoneCodeIgnoreCase(venuePlanId, normalized)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Zone code \"" + normalized + "\" already exists in this venue plan.");
            }
            return normalized;
        }
        int seq = 1;
        while (seq < 1000) {
            String candidate = type.getCodePrefix() + "-" + seq;
            if (!zoneRepository.existsByVenuePlanIdAndZoneCodeIgnoreCase(venuePlanId, candidate)) {
                return candidate;
            }
            seq++;
        }
        return type.getCodePrefix() + "-" + System.currentTimeMillis() % 10000;
    }

    private String normalizeZoneCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private String serializeCellRefs(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(refs);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize grid cell references.", ex);
        }
    }

    private List<String> deserializeCellRefs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, STRING_LIST_TYPE);
            return parsed != null ? List.copyOf(parsed) : List.of();
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ResolvedGeometry(Geometry geometry, List<String> cellRefs) {
    }
}
