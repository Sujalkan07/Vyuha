package com.vyuha.emergency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.common.GeometryUtil;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.FunctionalZoneRepository;
import com.vyuha.grid.GridDtos;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureElementRepository;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.NetworkDtos;
import com.vyuha.network.PathwayEdge;
import com.vyuha.network.PathwayEdgeRepository;
import com.vyuha.network.PathwayNode;
import com.vyuha.network.PathwayNodeRepository;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionAreaRepository;
import com.vyuha.venue.VenueDtos;
import com.vyuha.venue.VenuePlan;
import com.vyuha.venue.VenuePlanRepository;
import com.vyuha.venue.VenueService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates Phase 8 Emergency Preparedness assets, prioritized contacts directory,
 * ambulance/fire lane validation, and interactive outage failure-mode testing.
 */
@Service
public class EmergencyService {

    public static final double MIN_ASSET_WIDTH_M = 0.3;
    public static final double MAX_ASSET_WIDTH_M = 50.0;

    private final EventService eventService;
    private final VenueService venueService;
    private final VenuePlanRepository venuePlanRepository;
    private final FunctionalZoneRepository functionalZoneRepository;
    private final InfrastructureElementRepository infrastructureElementRepository;
    private final ExclusionAreaRepository exclusionAreaRepository;
    private final PathwayNodeRepository pathwayNodeRepository;
    private final PathwayEdgeRepository pathwayEdgeRepository;
    private final EmergencyAssetRepository emergencyAssetRepository;
    private final EmergencyContactRepository emergencyContactRepository;
    private final EmergencyValidationService validationService;
    private final OutageAnalysisService outageAnalysisService;
    private final GeometryUtil geometryUtil;
    private final ObjectMapper objectMapper;

    public EmergencyService(EventService eventService,
                            VenueService venueService,
                            VenuePlanRepository venuePlanRepository,
                            FunctionalZoneRepository functionalZoneRepository,
                            InfrastructureElementRepository infrastructureElementRepository,
                            ExclusionAreaRepository exclusionAreaRepository,
                            PathwayNodeRepository pathwayNodeRepository,
                            PathwayEdgeRepository pathwayEdgeRepository,
                            EmergencyAssetRepository emergencyAssetRepository,
                            EmergencyContactRepository emergencyContactRepository,
                            EmergencyValidationService validationService,
                            OutageAnalysisService outageAnalysisService,
                            GeometryUtil geometryUtil,
                            ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.venueService = venueService;
        this.venuePlanRepository = venuePlanRepository;
        this.functionalZoneRepository = functionalZoneRepository;
        this.infrastructureElementRepository = infrastructureElementRepository;
        this.exclusionAreaRepository = exclusionAreaRepository;
        this.pathwayNodeRepository = pathwayNodeRepository;
        this.pathwayEdgeRepository = pathwayEdgeRepository;
        this.emergencyAssetRepository = emergencyAssetRepository;
        this.emergencyContactRepository = emergencyContactRepository;
        this.validationService = validationService;
        this.outageAnalysisService = outageAnalysisService;
        this.geometryUtil = geometryUtil;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public EmergencyDtos.EmergencyWorkspaceDto getWorkspace(Long eventId, boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        return buildWorkspaceDto(event, canEdit);
    }

    @Transactional(readOnly = true)
    public EmergencyDtos.OutageAnalysisResult runOutageTest(Long eventId,
                                                            EmergencyDtos.OutageTestRequest request) {
        eventService.getEventOrThrow(eventId);
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);

        List<FunctionalZone> zones = plan != null
                ? functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<InfrastructureElement> elements = plan != null
                ? infrastructureElementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<PathwayNode> nodes = plan != null
                ? pathwayNodeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<PathwayEdge> edges = plan != null
                ? pathwayEdgeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<EmergencyAsset> assets = emergencyAssetRepository.findByEventIdOrderByAssetTypeAscIdAsc(eventId);

        Set<Long> disabledInfraIds = toIdSet(request != null ? request.disabledExitInfraIds() : null);
        Set<Long> disabledNodeIds = toIdSet(request != null ? request.disabledNodeIds() : null);
        Set<Long> disabledEdgeIds = toIdSet(request != null ? request.disabledEdgeIds() : null);
        Set<Long> disabledAssetIds = toIdSet(request != null ? request.disabledAssetIds() : null);
        boolean emergencyExitsOnly = request != null && Boolean.TRUE.equals(request.emergencyExitsOnly());

        return outageAnalysisService.analyzeOutage(
                zones, elements, nodes, edges, assets,
                disabledInfraIds, disabledNodeIds, disabledEdgeIds, disabledAssetIds, emergencyExitsOnly);
    }

    @Transactional
    public EmergencyDtos.EmergencyWorkspaceDto createAsset(Long eventId,
                                                           EmergencyDtos.EmergencyAssetRequest request,
                                                           boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        Geometry geometry = resolveAndValidateAssetGeometry(request);
        Double widthM = resolveAndValidateWidth(request.assetType(), request.minWidthM());

        EmergencyAsset asset = new EmergencyAsset();
        asset.setEvent(event);
        asset.setAssetType(request.assetType());
        asset.setLabel(truncate(request.label().trim(), 160));
        asset.setGeometryWkt(geometryUtil.toWkt(geometry));
        asset.setMinWidthM(widthM);
        asset.setCapacityNotes(truncate(trimToNull(request.capacityNotes()), 500));
        asset.setNotes(truncate(trimToNull(request.notes()), 500));

        emergencyAssetRepository.saveAndFlush(asset);
        return buildWorkspaceDto(event, canEdit);
    }

    @Transactional
    public EmergencyDtos.EmergencyWorkspaceDto updateAsset(Long eventId,
                                                           Long assetId,
                                                           EmergencyDtos.EmergencyAssetRequest request,
                                                           boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        EmergencyAsset asset = emergencyAssetRepository.findByIdAndEventId(assetId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Emergency asset not found."));

        Geometry geometry = resolveAndValidateAssetGeometry(request);
        Double widthM = resolveAndValidateWidth(request.assetType(), request.minWidthM());

        asset.setAssetType(request.assetType());
        asset.setLabel(truncate(request.label().trim(), 160));
        asset.setGeometryWkt(geometryUtil.toWkt(geometry));
        asset.setMinWidthM(widthM);
        asset.setCapacityNotes(truncate(trimToNull(request.capacityNotes()), 500));
        asset.setNotes(truncate(trimToNull(request.notes()), 500));

        emergencyAssetRepository.saveAndFlush(asset);
        return buildWorkspaceDto(event, canEdit);
    }

    @Transactional
    public EmergencyDtos.EmergencyWorkspaceDto deleteAsset(Long eventId,
                                                           Long assetId,
                                                           boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        EmergencyAsset asset = emergencyAssetRepository.findByIdAndEventId(assetId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Emergency asset not found."));
        emergencyAssetRepository.delete(asset);
        emergencyAssetRepository.flush();
        return buildWorkspaceDto(event, canEdit);
    }

    @Transactional
    public EmergencyDtos.EmergencyWorkspaceDto createContact(Long eventId,
                                                             EmergencyDtos.EmergencyContactRequest request,
                                                             boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        EmergencyContact contact = new EmergencyContact();
        contact.setEvent(event);
        applyContactRequest(contact, request);
        emergencyContactRepository.saveAndFlush(contact);
        return buildWorkspaceDto(event, canEdit);
    }

    @Transactional
    public EmergencyDtos.EmergencyWorkspaceDto updateContact(Long eventId,
                                                             Long contactId,
                                                             EmergencyDtos.EmergencyContactRequest request,
                                                             boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        EmergencyContact contact = emergencyContactRepository.findByIdAndEventId(contactId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Emergency contact not found."));
        applyContactRequest(contact, request);
        emergencyContactRepository.saveAndFlush(contact);
        return buildWorkspaceDto(event, canEdit);
    }

    @Transactional
    public EmergencyDtos.EmergencyWorkspaceDto deleteContact(Long eventId,
                                                             Long contactId,
                                                             boolean canEdit) {
        Event event = eventService.getEventOrThrow(eventId);
        EmergencyContact contact = emergencyContactRepository.findByIdAndEventId(contactId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Emergency contact not found."));
        emergencyContactRepository.delete(contact);
        emergencyContactRepository.flush();
        return buildWorkspaceDto(event, canEdit);
    }

    private EmergencyDtos.EmergencyWorkspaceDto buildWorkspaceDto(Event event, boolean canEdit) {
        Long eventId = event.getId();
        VenueDtos.VenueWorkspaceDto base = venueService.getWorkspaceState(eventId, canEdit);
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);

        Polygon boundary = null;
        if (plan != null && plan.getBoundaryWkt() != null && !plan.getBoundaryWkt().isBlank()) {
            try {
                boundary = geometryUtil.parsePolygon(plan.getBoundaryWkt());
            } catch (IllegalArgumentException ignored) {
                boundary = null;
            }
        }

        List<ExclusionArea> exclusions = plan != null
                ? exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<FunctionalZone> zones = plan != null
                ? functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<InfrastructureElement> elements = plan != null
                ? infrastructureElementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<PathwayNode> nodes = plan != null
                ? pathwayNodeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<PathwayEdge> edges = plan != null
                ? pathwayEdgeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<EmergencyAsset> assets = emergencyAssetRepository.findByEventIdOrderByAssetTypeAscIdAsc(eventId);
        List<EmergencyContact> contacts = emergencyContactRepository.findByEventIdOrderByPriorityOrderAscIdAsc(eventId);

        EmergencyDtos.EmergencyValidationSummary validation = validationService.validate(
                boundary, exclusions, elements, nodes, edges, assets, contacts);
        EmergencyDtos.OutageAnalysisResult baselineOutage = outageAnalysisService.analyzeOutage(
                zones, elements, nodes, edges, assets, Set.of(), Set.of(), Set.of(), Set.of(), false);

        return new EmergencyDtos.EmergencyWorkspaceDto(
                base.eventId(),
                base.eventName(),
                base.expectedAttendance(),
                canEdit,
                base.venuePlan(),
                base.exclusions(),
                base.areaBreakdown(),
                base.vectorMapGeoJson(),
                toZoneDtos(zones),
                toElementDtos(elements),
                toNodeDtos(nodes),
                toEdgeDtos(edges),
                toAssetDtos(assets),
                toContactDtos(contacts),
                validation,
                baselineOutage
        );
    }

    private void applyContactRequest(EmergencyContact contact, EmergencyDtos.EmergencyContactRequest request) {
        if (request.agencyName() == null || request.agencyName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agency name is required.");
        }
        if (request.contactName() == null || request.contactName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contact name is required.");
        }
        if (request.phone() == null || request.phone().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone number is required.");
        }
        if (request.role() == null || request.role().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contact role is required.");
        }
        int priority = request.priorityOrder() != null ? request.priorityOrder() : 1;
        priority = Math.max(1, Math.min(99, priority));

        contact.setAgencyName(truncate(request.agencyName().trim(), 160));
        contact.setContactName(truncate(request.contactName().trim(), 150));
        contact.setPhone(truncate(request.phone().trim(), 50));
        contact.setRole(truncate(request.role().trim(), 120));
        contact.setPriorityOrder(priority);
        contact.setNotes(truncate(trimToNull(request.notes()), 500));
    }

    private Geometry resolveAndValidateAssetGeometry(EmergencyDtos.EmergencyAssetRequest request) {
        EmergencyAssetType type = request.assetType();
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Emergency asset type is required.");
        }
        try {
            Geometry geom = null;
            if (request.geometryWkt() != null && !request.geometryWkt().isBlank()) {
                geom = geometryUtil.parseGeometry(request.geometryWkt());
            } else if (request.points() != null && !request.points().isEmpty()) {
                List<GeometryUtil.Point2D> pts = request.points();
                if (pts.size() == 1) {
                    geom = geometryUtil.buildPoint(pts.get(0).x(), pts.get(0).y());
                } else if (type == EmergencyAssetType.ASSEMBLY_POINT) {
                    geom = geometryUtil.buildPolygon(pts);
                } else if (type.isVehicleRoute()) {
                    geom = geometryUtil.buildLineString(pts);
                } else {
                    geom = pts.size() >= 3 ? geometryUtil.buildPolygon(pts) : geometryUtil.buildPoint(pts.get(0).x(), pts.get(0).y());
                }
            } else if (request.x() != null && request.y() != null) {
                geom = geometryUtil.buildPoint(request.x(), request.y());
            }

            if (geom == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Provide valid geometry coordinates or WKT for this emergency asset.");
            }

            switch (type) {
                case EMERGENCY_EXIT -> {
                    if (!(geom instanceof Point) && !(geom instanceof Polygon)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Emergency Exit geometry must be a Point or Polygon.");
                    }
                }
                case ASSEMBLY_POINT -> {
                    if (!(geom instanceof Polygon) && !(geom instanceof MultiPolygon) && !(geom instanceof Point)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Assembly Point geometry must be a Polygon or Point.");
                    }
                }
                case AMBULANCE_ROUTE, FIRE_ROUTE -> {
                    if (!(geom instanceof LineString) && !(geom instanceof Polygon) && !(geom instanceof MultiPolygon)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                type.getDisplayName() + " geometry must be a LineString path or Polygon corridor.");
                    }
                }
            }
            return geom;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private Double resolveAndValidateWidth(EmergencyAssetType type, Double requestedWidthM) {
        if (requestedWidthM == null) {
            return type.getDefaultMinWidthM();
        }
        if (!Double.isFinite(requestedWidthM) || requestedWidthM < MIN_ASSET_WIDTH_M || requestedWidthM > MAX_ASSET_WIDTH_M) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Clear width must be between 0.3 m and 50.0 m.");
        }
        return geometryUtil.round2(requestedWidthM);
    }

    private List<GridDtos.FunctionalZoneDto> toZoneDtos(List<FunctionalZone> zones) {
        List<GridDtos.FunctionalZoneDto> dtos = new ArrayList<>(zones.size());
        for (FunctionalZone zone : zones) {
            try {
                Geometry rawGeom = geometryUtil.parsePolygonal(zone.getPolygonWkt());
                double area = geometryUtil.polygonalArea(rawGeom);
                List<GeometryUtil.PolygonShape> shapes = geometryUtil.extractPolygonShapes(rawGeom);
                GeometryUtil.Point2D labelPt = geometryUtil.interiorPoint(rawGeom);
                dtos.add(new GridDtos.FunctionalZoneDto(
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
            } catch (IllegalArgumentException ignored) {
                // Skip malformed zone geometry
            }
        }
        return List.copyOf(dtos);
    }

    private List<InfrastructureDtos.InfrastructureElementDto> toElementDtos(List<InfrastructureElement> elements) {
        List<InfrastructureDtos.InfrastructureElementDto> dtos = new ArrayList<>(elements.size());
        for (InfrastructureElement el : elements) {
            InfrastructureType type = el.getElementType();
            InfrastructureDtos.ElementMetadata meta = InfrastructureDtos.ElementMetadata.parse(
                    objectMapper, el.getMetadataJson());
            Polygon footprint = parseOrBuildFootprint(el);
            List<GeometryUtil.Point2D> footprintPts = geometryUtil.extractOuterRing(footprint);
            double clearanceBufferM = type.isExitPortal()
                    ? meta.effectiveClearanceBufferM()
                    : InfrastructureDtos.DEFAULT_EXIT_CLEARANCE_METERS;
            dtos.add(new InfrastructureDtos.InfrastructureElementDto(
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
                    List.of()
            ));
        }
        return List.copyOf(dtos);
    }

    private List<NetworkDtos.PathwayNodeDto> toNodeDtos(List<PathwayNode> nodes) {
        List<NetworkDtos.PathwayNodeDto> dtos = new ArrayList<>(nodes.size());
        for (PathwayNode n : nodes) {
            FunctionalZone lz = n.getLinkedZone();
            InfrastructureElement li = n.getLinkedInfra();
            dtos.add(new NetworkDtos.PathwayNodeDto(
                    n.getId(),
                    n.getNodeUid(),
                    n.getNodeType(),
                    n.getNodeType().getDisplayName(),
                    n.getNodeType().getShortCode(),
                    n.getLabel(),
                    n.getX(),
                    n.getY(),
                    lz != null ? lz.getId() : null,
                    lz != null ? lz.getZoneCode() : null,
                    lz != null ? lz.getName() : null,
                    li != null ? li.getId() : null,
                    li != null ? li.getLabel() : null,
                    n.getNotes(),
                    n.getNodeType().getFillColor(),
                    n.getNodeType().getStrokeColor()
            ));
        }
        return List.copyOf(dtos);
    }

    private List<NetworkDtos.PathwayEdgeDto> toEdgeDtos(List<PathwayEdge> edges) {
        List<NetworkDtos.PathwayEdgeDto> dtos = new ArrayList<>(edges.size());
        for (PathwayEdge e : edges) {
            PathwayNode fn = e.getFromNode();
            PathwayNode tn = e.getToNode();
            List<GeometryUtil.Point2D> pts = extractOrInitEdgePoints(e);
            GeometryUtil.Point2D mid = computeEdgeMidpoint(e, pts);
            dtos.add(new NetworkDtos.PathwayEdgeDto(
                    e.getId(),
                    e.getEdgeUid(),
                    fn.getId(),
                    tn.getId(),
                    fn.getNodeUid(),
                    tn.getNodeUid(),
                    fn.getLabel(),
                    tn.getLabel(),
                    e.getLabel(),
                    e.getNetworkLayer(),
                    e.getNetworkLayer().getDisplayName(),
                    e.getNetworkLayer().getShortLabel(),
                    e.getWidthM(),
                    e.getLengthM(),
                    e.getDirection(),
                    e.getDirection().getDisplayName(),
                    e.isActive(),
                    e.getNotes(),
                    e.getNetworkLayer().getColorHex(),
                    e.getNetworkLayer().isDashed(),
                    e.getPolylineWkt(),
                    pts,
                    mid
            ));
        }
        return List.copyOf(dtos);
    }

    private List<EmergencyDtos.EmergencyAssetDto> toAssetDtos(List<EmergencyAsset> assets) {
        List<EmergencyDtos.EmergencyAssetDto> dtos = new ArrayList<>(assets.size());
        for (EmergencyAsset asset : assets) {
            EmergencyAssetType type = asset.getAssetType();
            String geometryKind = type.getPrimaryGeometryKind();
            List<GeometryUtil.Point2D> points = List.of();
            List<GeometryUtil.PolygonShape> shapes = List.of();
            GeometryUtil.Point2D anchorPoint = null;
            Double lengthM = null;
            Double areaSqm = null;

            try {
                Geometry geom = geometryUtil.parseGeometry(asset.getGeometryWkt());
                if (geom instanceof Point pt) {
                    geometryKind = "POINT";
                    GeometryUtil.Point2D p = new GeometryUtil.Point2D(
                            geometryUtil.round2(pt.getX()), geometryUtil.round2(pt.getY()));
                    points = List.of(p);
                    anchorPoint = p;
                } else if (geom instanceof LineString line) {
                    geometryKind = "LINESTRING";
                    points = geometryUtil.extractLinePoints(line);
                    anchorPoint = geometryUtil.lineMidpoint(line);
                    lengthM = geometryUtil.round2(line.getLength());
                } else if (geom instanceof Polygon || geom instanceof MultiPolygon) {
                    geometryKind = "POLYGON";
                    shapes = geometryUtil.extractPolygonShapes(geom);
                    if (!shapes.isEmpty()) {
                        points = shapes.get(0).exterior();
                    }
                    anchorPoint = geometryUtil.interiorPoint(geom);
                    areaSqm = geometryUtil.round2(geometryUtil.polygonalArea(geom));
                }
            } catch (IllegalArgumentException ignored) {
                // Keep fallback metadata
            }

            dtos.add(new EmergencyDtos.EmergencyAssetDto(
                    asset.getId(),
                    type,
                    type.getDisplayName(),
                    type.getShortCode(),
                    type.getFillColor(),
                    type.getStrokeColor(),
                    type.isVehicleRoute(),
                    asset.getLabel(),
                    asset.getGeometryWkt(),
                    geometryKind,
                    points,
                    shapes,
                    anchorPoint,
                    asset.getMinWidthM(),
                    lengthM,
                    areaSqm,
                    asset.getCapacityNotes(),
                    asset.getNotes()
            ));
        }
        return List.copyOf(dtos);
    }

    private List<EmergencyDtos.EmergencyContactDto> toContactDtos(List<EmergencyContact> contacts) {
        return contacts.stream()
                .map(c -> new EmergencyDtos.EmergencyContactDto(
                        c.getId(),
                        c.getAgencyName(),
                        c.getContactName(),
                        c.getPhone(),
                        c.getRole(),
                        c.getPriorityOrder(),
                        c.getNotes()
                ))
                .toList();
    }

    private Polygon parseOrBuildFootprint(InfrastructureElement el) {
        if (el.getFootprintWkt() != null && !el.getFootprintWkt().isBlank()) {
            try {
                return geometryUtil.parsePolygon(el.getFootprintWkt());
            } catch (IllegalArgumentException ignored) {
                // Fall back to rotated rectangle
            }
        }
        return geometryUtil.buildRotatedRectangle(
                el.getCenterX(), el.getCenterY(), el.getWidthM(), el.getHeightM(), el.getRotationDeg());
    }

    private List<GeometryUtil.Point2D> extractOrInitEdgePoints(PathwayEdge edge) {
        if (edge.getPolylineWkt() != null && !edge.getPolylineWkt().isBlank()) {
            try {
                LineString line = geometryUtil.parseLineString(edge.getPolylineWkt());
                List<GeometryUtil.Point2D> pts = geometryUtil.extractLinePoints(line);
                if (pts.size() >= 2) {
                    return pts;
                }
            } catch (IllegalArgumentException ignored) {
                // Fall back to endpoint chord
            }
        }
        return List.of(
                new GeometryUtil.Point2D(edge.getFromNode().getX(), edge.getFromNode().getY()),
                new GeometryUtil.Point2D(edge.getToNode().getX(), edge.getToNode().getY())
        );
    }

    private GeometryUtil.Point2D computeEdgeMidpoint(PathwayEdge edge, List<GeometryUtil.Point2D> pts) {
        if (edge.getPolylineWkt() != null && !edge.getPolylineWkt().isBlank()) {
            try {
                return geometryUtil.lineMidpoint(geometryUtil.parseLineString(edge.getPolylineWkt()));
            } catch (IllegalArgumentException ignored) {
                // Fall back
            }
        }
        GeometryUtil.Point2D start = pts.get(0);
        GeometryUtil.Point2D end = pts.get(pts.size() - 1);
        return new GeometryUtil.Point2D((start.x() + end.x()) / 2.0, (start.y() + end.y()) / 2.0);
    }

    private Set<Long> toIdSet(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<Long> set = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                set.add(id);
            }
        }
        return set;
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
