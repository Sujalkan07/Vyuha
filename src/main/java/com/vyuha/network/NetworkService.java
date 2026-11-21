package com.vyuha.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.auth.AppUser;
import com.vyuha.auth.UserRepository;
import com.vyuha.capacity.CapacityReview;
import com.vyuha.capacity.CapacityReviewRepository;
import com.vyuha.common.GeometryUtil;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.FunctionalZoneRepository;
import com.vyuha.grid.GridDtos;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.FindingReviewStatus;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureElementRepository;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionAreaRepository;
import com.vyuha.venue.VenueDtos;
import com.vyuha.venue.VenuePlan;
import com.vyuha.venue.VenuePlanRepository;
import com.vyuha.venue.VenueService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates Phase 6 Pedestrian Movement Network nodes/edges, layout synchronization,
 * automated bottleneck screening, and safety reviewer finding persistence.
 */
@Service
public class NetworkService {

    private final EventService eventService;
    private final VenueService venueService;
    private final VenuePlanRepository venuePlanRepository;
    private final ExclusionAreaRepository exclusionAreaRepository;
    private final FunctionalZoneRepository functionalZoneRepository;
    private final InfrastructureElementRepository infrastructureElementRepository;
    private final CapacityReviewRepository capacityReviewRepository;
    private final PathwayNodeRepository nodeRepository;
    private final PathwayEdgeRepository edgeRepository;
    private final BottleneckFindingRepository findingRepository;
    private final UserRepository userRepository;
    private final GeometryUtil geometryUtil;
    private final BottleneckScreeningService screeningService;
    private final ObjectMapper objectMapper;

    public NetworkService(EventService eventService,
                          VenueService venueService,
                          VenuePlanRepository venuePlanRepository,
                          ExclusionAreaRepository exclusionAreaRepository,
                          FunctionalZoneRepository functionalZoneRepository,
                          InfrastructureElementRepository infrastructureElementRepository,
                          CapacityReviewRepository capacityReviewRepository,
                          PathwayNodeRepository nodeRepository,
                          PathwayEdgeRepository edgeRepository,
                          BottleneckFindingRepository findingRepository,
                          UserRepository userRepository,
                          GeometryUtil geometryUtil,
                          BottleneckScreeningService screeningService,
                          ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.venueService = venueService;
        this.venuePlanRepository = venuePlanRepository;
        this.exclusionAreaRepository = exclusionAreaRepository;
        this.functionalZoneRepository = functionalZoneRepository;
        this.infrastructureElementRepository = infrastructureElementRepository;
        this.capacityReviewRepository = capacityReviewRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.findingRepository = findingRepository;
        this.userRepository = userRepository;
        this.geometryUtil = geometryUtil;
        this.screeningService = screeningService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public NetworkDtos.NetworkWorkspaceDto getWorkspace(Long eventId,
                                                        boolean canEdit,
                                                        boolean canReviewFindings) {
        VenueDtos.VenueWorkspaceDto base = venueService.getWorkspaceState(eventId, canEdit);
        VenuePlan plan = venuePlanRepository.findByEventId(eventId).orElse(null);
        List<FunctionalZone> zones = plan != null
                ? functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<InfrastructureElement> elements = plan != null
                ? infrastructureElementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<PathwayNode> nodes = plan != null
                ? nodeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<PathwayEdge> edges = plan != null
                ? edgeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<BottleneckFinding> storedFindings = plan != null
                ? findingRepository.findByVenuePlanIdOrderByActiveDescIdAsc(plan.getId())
                : List.of();

        Integer ingressWindow = capacityReviewRepository.findByEventId(eventId)
                .map(CapacityReview::getIngressWindowMinutes)
                .orElse(null);
        BottleneckScreeningService.ScreeningResult screeningResult = screeningService.screen(
                zones, elements, nodes, edges, base.expectedAttendance(), ingressWindow, null);

        return buildWorkspaceDto(base, zones, elements, nodes, edges, storedFindings,
                screeningResult, canEdit, canReviewFindings, null, null);
    }

    @Transactional
    public NetworkDtos.NetworkWorkspaceDto autoSeedNodes(Long eventId,
                                                         boolean canEdit,
                                                         boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        List<FunctionalZone> zones = functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<InfrastructureElement> elements = infrastructureElementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<PathwayNode> existingNodes = nodeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());

        Set<Long> linkedInfraIds = new HashSet<>();
        Set<Long> linkedZoneIds = new HashSet<>();
        int nextSort = 1;
        for (PathwayNode n : existingNodes) {
            if (n.getLinkedInfra() != null) {
                linkedInfraIds.add(n.getLinkedInfra().getId());
            }
            if (n.getLinkedZone() != null) {
                linkedZoneIds.add(n.getLinkedZone().getId());
            }
            if (n.getSortOrder() >= nextSort) {
                nextSort = n.getSortOrder() + 1;
            }
        }

        List<PathwayNode> toCreate = new ArrayList<>();
        for (InfrastructureElement el : elements) {
            if (!el.isActive() || linkedInfraIds.contains(el.getId())) {
                continue;
            }
            PathwayNodeType mapped = mapInfraToNodeType(el.getElementType());
            if (mapped == null) {
                continue;
            }
            PathwayNode node = new PathwayNode();
            node.setVenuePlan(plan);
            node.setNodeUid(UUID.randomUUID().toString());
            node.setNodeType(mapped);
            node.setLabel(truncate(el.getLabel(), 160));
            node.setX(geometryUtil.round2(el.getCenterX()));
            node.setY(geometryUtil.round2(el.getCenterY()));
            node.setLinkedInfra(el);
            node.setSortOrder(nextSort++);
            toCreate.add(node);
        }

        for (FunctionalZone zone : zones) {
            if (linkedZoneIds.contains(zone.getId()) || !isSeedableZone(zone.getZoneType())) {
                continue;
            }
            Geometry geom;
            try {
                geom = geometryUtil.parsePolygonal(zone.getPolygonWkt());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            GeometryUtil.Point2D pt = geometryUtil.interiorPoint(geom);
            if (pt == null) {
                continue;
            }
            PathwayNode node = new PathwayNode();
            node.setVenuePlan(plan);
            node.setNodeUid(UUID.randomUUID().toString());
            node.setNodeType(PathwayNodeType.ZONE_HUB);
            node.setLabel(truncate(zone.getName(), 160));
            node.setX(geometryUtil.round2(pt.x()));
            node.setY(geometryUtil.round2(pt.y()));
            node.setLinkedZone(zone);
            node.setSortOrder(nextSort++);
            toCreate.add(node);
        }

        if (!toCreate.isEmpty()) {
            nodeRepository.saveAllAndFlush(toCreate);
        }

        return syncAndBuildWorkspace(event, plan, canEdit, canReviewFindings, null, null);
    }

    @Transactional
    public NetworkDtos.NetworkWorkspaceDto createNode(Long eventId,
                                                      NetworkDtos.CreateNodeRequest request,
                                                      boolean canEdit,
                                                      boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        double x = requireFinite(request.x(), "X coordinate");
        double y = requireFinite(request.y(), "Y coordinate");

        FunctionalZone linkedZone = resolveZoneForPlan(plan.getId(), request.linkedZoneId());
        InfrastructureElement linkedInfra = resolveInfraForPlan(plan.getId(), request.linkedInfraId());

        List<PathwayNode> existing = nodeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        int nextSort = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getSortOrder() + 1;
        String label = resolveNodeLabel(request.label(), request.nodeType(), existing);

        PathwayNode node = new PathwayNode();
        node.setVenuePlan(plan);
        node.setNodeUid(UUID.randomUUID().toString());
        node.setNodeType(request.nodeType());
        node.setLabel(label);
        node.setX(geometryUtil.round2(x));
        node.setY(geometryUtil.round2(y));
        node.setLinkedZone(linkedZone);
        node.setLinkedInfra(linkedInfra);
        node.setNotes(trimToNull(request.notes()));
        node.setSortOrder(nextSort);
        PathwayNode saved = nodeRepository.saveAndFlush(node);

        return syncAndBuildWorkspace(event, plan, canEdit, canReviewFindings, saved.getId(), null);
    }

    @Transactional
    public NetworkDtos.NetworkWorkspaceDto updateNode(Long eventId,
                                                      Long nodeId,
                                                      NetworkDtos.UpdateNodeRequest request,
                                                      boolean canEdit,
                                                      boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);

        PathwayNode node = nodeRepository.findByIdAndVenuePlanId(nodeId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pathway node not found."));

        double x = requireFinite(request.x(), "X coordinate");
        double y = requireFinite(request.y(), "Y coordinate");
        if (request.nodeType() != null) {
            node.setNodeType(request.nodeType());
        }
        if (request.label() != null && !request.label().isBlank()) {
            node.setLabel(truncate(request.label().trim(), 160));
        }
        node.setX(geometryUtil.round2(x));
        node.setY(geometryUtil.round2(y));
        if (Boolean.TRUE.equals(request.updateLinks())) {
            node.setLinkedZone(resolveZoneForPlan(plan.getId(), request.linkedZoneId()));
            node.setLinkedInfra(resolveInfraForPlan(plan.getId(), request.linkedInfraId()));
        } else {
            if (request.linkedZoneId() != null) {
                node.setLinkedZone(resolveZoneForPlan(plan.getId(), request.linkedZoneId()));
            }
            if (request.linkedInfraId() != null) {
                node.setLinkedInfra(resolveInfraForPlan(plan.getId(), request.linkedInfraId()));
            }
        }
        if (request.notes() != null) {
            node.setNotes(trimToNull(request.notes()));
        }
        PathwayNode saved = nodeRepository.saveAndFlush(node);

        // Update incident edges' endpoints and recompute lengths
        List<PathwayEdge> incidentEdges = edgeRepository.findByFromNodeIdOrToNodeId(saved.getId(), saved.getId());
        if (!incidentEdges.isEmpty()) {
            for (PathwayEdge edge : incidentEdges) {
                List<GeometryUtil.Point2D> pts = new ArrayList<>(extractOrInitEdgePoints(edge));
                if (edge.getFromNode().getId().equals(saved.getId())) {
                    pts.set(0, new GeometryUtil.Point2D(saved.getX(), saved.getY()));
                }
                if (edge.getToNode().getId().equals(saved.getId())) {
                    pts.set(pts.size() - 1, new GeometryUtil.Point2D(saved.getX(), saved.getY()));
                }
                LineString updatedLine = buildValidEdgeLine(pts);
                edge.setPolylineWkt(geometryUtil.toWkt(updatedLine));
                edge.setLengthM(geometryUtil.round2(updatedLine.getLength()));
            }
            edgeRepository.saveAllAndFlush(incidentEdges);
        }

        return syncAndBuildWorkspace(event, plan, canEdit, canReviewFindings, saved.getId(), null);
    }

    @Transactional
    public NetworkDtos.NetworkWorkspaceDto deleteNode(Long eventId,
                                                      Long nodeId,
                                                      boolean canEdit,
                                                      boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);

        PathwayNode node = nodeRepository.findByIdAndVenuePlanId(nodeId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pathway node not found."));

        List<PathwayEdge> incidentEdges = edgeRepository.findByFromNodeIdOrToNodeId(node.getId(), node.getId());
        if (!incidentEdges.isEmpty()) {
            edgeRepository.deleteAll(incidentEdges);
            edgeRepository.flush();
        }
        nodeRepository.delete(node);
        nodeRepository.flush();

        return syncAndBuildWorkspace(event, plan, canEdit, canReviewFindings, null, null);
    }

    @Transactional
    public NetworkDtos.NetworkWorkspaceDto createEdge(Long eventId,
                                                      NetworkDtos.CreateEdgeRequest request,
                                                      boolean canEdit,
                                                      boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);

        if (request.fromNodeId().equals(request.toNodeId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pathway edge cannot connect a node to itself.");
        }
        PathwayNode fromNode = nodeRepository.findByIdAndVenuePlanId(request.fromNodeId(), plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Start pathway node not found in venue."));
        PathwayNode toNode = nodeRepository.findByIdAndVenuePlanId(request.toNodeId(), plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "End pathway node not found in venue."));

        NetworkLayer layer = request.networkLayer();
        double widthM = validateWidth(request.widthM() != null ? request.widthM() : layer.getDefaultWidthM());
        PathwayDirection direction = request.direction() != null ? request.direction() : PathwayDirection.BIDIRECTIONAL;
        boolean active = request.active() == null || request.active();

        List<GeometryUtil.Point2D> pts = assembleEdgePoints(fromNode, request.waypoints(), toNode);
        LineString line = buildValidEdgeLine(pts);

        List<PathwayEdge> existing = edgeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        int nextSort = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getSortOrder() + 1;
        String label = (request.label() != null && !request.label().isBlank())
                ? truncate(request.label().trim(), 160)
                : truncate(fromNode.getLabel() + " -> " + toNode.getLabel(), 160);

        PathwayEdge edge = new PathwayEdge();
        edge.setVenuePlan(plan);
        edge.setEdgeUid(UUID.randomUUID().toString());
        edge.setFromNode(fromNode);
        edge.setToNode(toNode);
        edge.setLabel(label);
        edge.setNetworkLayer(layer);
        edge.setWidthM(geometryUtil.round2(widthM));
        edge.setLengthM(geometryUtil.round2(line.getLength()));
        edge.setDirection(direction);
        edge.setPolylineWkt(geometryUtil.toWkt(line));
        edge.setActive(active);
        edge.setNotes(trimToNull(request.notes()));
        edge.setSortOrder(nextSort);
        PathwayEdge saved = edgeRepository.saveAndFlush(edge);

        return syncAndBuildWorkspace(event, plan, canEdit, canReviewFindings, null, saved.getId());
    }

    @Transactional
    public NetworkDtos.NetworkWorkspaceDto updateEdge(Long eventId,
                                                      Long edgeId,
                                                      NetworkDtos.UpdateEdgeRequest request,
                                                      boolean canEdit,
                                                      boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);

        PathwayEdge edge = edgeRepository.findByIdAndVenuePlanId(edgeId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pathway edge not found."));

        boolean reversed = Boolean.TRUE.equals(request.reverseDirection());
        if (reversed) {
            PathwayNode oldFrom = edge.getFromNode();
            edge.setFromNode(edge.getToNode());
            edge.setToNode(oldFrom);
        }

        if (request.label() != null) {
            edge.setLabel(request.label().isBlank()
                    ? truncate(edge.getFromNode().getLabel() + " -> " + edge.getToNode().getLabel(), 160)
                    : truncate(request.label().trim(), 160));
        } else if (reversed && edge.getLabel() != null && edge.getLabel().contains(" -> ")) {
            edge.setLabel(truncate(edge.getFromNode().getLabel() + " -> " + edge.getToNode().getLabel(), 160));
        }

        if (request.networkLayer() != null) {
            edge.setNetworkLayer(request.networkLayer());
        }
        if (request.widthM() != null) {
            edge.setWidthM(geometryUtil.round2(validateWidth(request.widthM())));
        }
        if (request.direction() != null) {
            edge.setDirection(request.direction());
        }
        if (request.active() != null) {
            edge.setActive(request.active());
        }
        if (request.notes() != null) {
            edge.setNotes(trimToNull(request.notes()));
        }

        List<GeometryUtil.Point2D> pts;
        if (request.waypoints() != null) {
            pts = assembleEdgePoints(edge.getFromNode(), request.waypoints(), edge.getToNode());
        } else {
            List<GeometryUtil.Point2D> existingPts = new ArrayList<>(extractOrInitEdgePoints(edge));
            if (reversed && existingPts.size() > 2) {
                List<GeometryUtil.Point2D> interior = new ArrayList<>(existingPts.subList(1, existingPts.size() - 1));
                Collections.reverse(interior);
                pts = assembleEdgePoints(edge.getFromNode(), interior, edge.getToNode());
            } else {
                List<GeometryUtil.Point2D> interior = existingPts.size() > 2
                        ? existingPts.subList(1, existingPts.size() - 1)
                        : List.of();
                pts = assembleEdgePoints(edge.getFromNode(), interior, edge.getToNode());
            }
        }
        LineString line = buildValidEdgeLine(pts);
        edge.setPolylineWkt(geometryUtil.toWkt(line));
        edge.setLengthM(geometryUtil.round2(line.getLength()));

        PathwayEdge saved = edgeRepository.saveAndFlush(edge);
        return syncAndBuildWorkspace(event, plan, canEdit, canReviewFindings, null, saved.getId());
    }

    @Transactional
    public NetworkDtos.NetworkWorkspaceDto deleteEdge(Long eventId,
                                                      Long edgeId,
                                                      boolean canEdit,
                                                      boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);

        PathwayEdge edge = edgeRepository.findByIdAndVenuePlanId(edgeId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pathway edge not found."));
        edgeRepository.delete(edge);
        edgeRepository.flush();

        return syncAndBuildWorkspace(event, plan, canEdit, canReviewFindings, null, null);
    }

    @Transactional
    public NetworkDtos.NetworkWorkspaceDto syncLayout(Long eventId,
                                                      NetworkDtos.LayoutSyncRequest request,
                                                      boolean canEdit,
                                                      boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);

        List<NetworkDtos.NodeSyncItem> nodeItems = request != null && request.nodes() != null ? request.nodes() : List.of();
        List<NetworkDtos.EdgeSyncItem> edgeItems = request != null && request.edges() != null ? request.edges() : List.of();

        List<PathwayEdge> oldEdges = edgeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        if (!oldEdges.isEmpty()) {
            edgeRepository.deleteAll(oldEdges);
            edgeRepository.flush();
        }
        List<PathwayNode> oldNodes = nodeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        if (!oldNodes.isEmpty()) {
            nodeRepository.deleteAll(oldNodes);
            nodeRepository.flush();
        }

        Map<String, PathwayNode> savedNodesByUid = new HashMap<>();
        List<PathwayNode> nodesToSave = new ArrayList<>(nodeItems.size());
        int nodeSort = 1;
        for (NetworkDtos.NodeSyncItem item : nodeItems) {
            String uid = item.nodeUid().trim();
            if (savedNodesByUid.containsKey(uid)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate nodeUid in layout sync: " + uid);
            }
            double x = requireFinite(item.x(), "Node X");
            double y = requireFinite(item.y(), "Node Y");
            PathwayNode node = new PathwayNode();
            node.setVenuePlan(plan);
            node.setNodeUid(uid);
            node.setNodeType(item.nodeType());
            node.setLabel(truncate(
                    item.label() != null && !item.label().isBlank() ? item.label().trim() : item.nodeType().getDisplayName(),
                    160));
            node.setX(geometryUtil.round2(x));
            node.setY(geometryUtil.round2(y));
            node.setLinkedZone(resolveZoneForPlan(plan.getId(), item.linkedZoneId()));
            node.setLinkedInfra(resolveInfraForPlan(plan.getId(), item.linkedInfraId()));
            node.setNotes(trimToNull(item.notes()));
            node.setSortOrder(item.sortOrder() != null ? item.sortOrder() : nodeSort);
            nodeSort++;
            nodesToSave.add(node);
            savedNodesByUid.put(uid, node);
        }
        if (!nodesToSave.isEmpty()) {
            nodeRepository.saveAllAndFlush(nodesToSave);
        }

        List<PathwayEdge> edgesToSave = new ArrayList<>(edgeItems.size());
        Set<String> seenEdgeUids = new HashSet<>();
        int edgeSort = 1;
        for (NetworkDtos.EdgeSyncItem item : edgeItems) {
            String uid = item.edgeUid().trim();
            if (!seenEdgeUids.add(uid)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate edgeUid in layout sync: " + uid);
            }
            PathwayNode fromNode = savedNodesByUid.get(item.fromNodeUid().trim());
            PathwayNode toNode = savedNodesByUid.get(item.toNodeUid().trim());
            if (fromNode == null || toNode == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pathway edge references missing node UID.");
            }
            if (fromNode == toNode) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pathway edge cannot connect a node to itself.");
            }
            double widthM = validateWidth(item.widthM());
            List<GeometryUtil.Point2D> interior = extractInteriorPoints(item.points());
            List<GeometryUtil.Point2D> pts = assembleEdgePoints(fromNode, interior, toNode);
            LineString line = buildValidEdgeLine(pts);

            PathwayEdge edge = new PathwayEdge();
            edge.setVenuePlan(plan);
            edge.setEdgeUid(uid);
            edge.setFromNode(fromNode);
            edge.setToNode(toNode);
            edge.setLabel(item.label() != null && !item.label().isBlank()
                    ? truncate(item.label().trim(), 160)
                    : truncate(fromNode.getLabel() + " -> " + toNode.getLabel(), 160));
            edge.setNetworkLayer(item.networkLayer());
            edge.setWidthM(geometryUtil.round2(widthM));
            edge.setLengthM(geometryUtil.round2(line.getLength()));
            edge.setDirection(item.direction() != null ? item.direction() : PathwayDirection.BIDIRECTIONAL);
            edge.setPolylineWkt(geometryUtil.toWkt(line));
            edge.setActive(item.active() == null || item.active());
            edge.setNotes(trimToNull(item.notes()));
            edge.setSortOrder(item.sortOrder() != null ? item.sortOrder() : edgeSort);
            edgeSort++;
            edgesToSave.add(edge);
        }
        if (!edgesToSave.isEmpty()) {
            edgeRepository.saveAllAndFlush(edgesToSave);
        }

        return syncAndBuildWorkspace(event, plan, canEdit, canReviewFindings, null, null);
    }

    @Transactional
    public NetworkDtos.NetworkWorkspaceDto runScreening(Long eventId,
                                                        boolean canEdit,
                                                        boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        return syncAndBuildWorkspace(event, plan, canEdit, canReviewFindings, null, null);
    }

    @Transactional
    public NetworkDtos.NetworkWorkspaceDto reviewFinding(Long eventId,
                                                         Long findingId,
                                                         Long reviewerUserId,
                                                         NetworkDtos.ReviewBottleneckRequest request,
                                                         boolean canEdit,
                                                         boolean canReviewFindings) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venueService.getOrCreatePlan(event);
        if (reviewerUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Reviewer account not found.");
        }
        AppUser reviewer = userRepository.findById(reviewerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Reviewer account not found."));

        BottleneckFinding finding = findingRepository.findByIdAndVenuePlanId(findingId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bottleneck finding not found."));

        finding.setReviewStatus(request.reviewStatus());
        finding.setReviewerNote(trimToNull(request.reviewerNote()));
        finding.setReviewedBy(reviewer);
        finding.setReviewedAt(Instant.now());
        findingRepository.saveAndFlush(finding);

        VenueDtos.VenueWorkspaceDto base = venueService.getWorkspaceState(eventId, canEdit);
        List<FunctionalZone> zones = functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<InfrastructureElement> elements = infrastructureElementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<PathwayNode> nodes = nodeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<PathwayEdge> edges = edgeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<BottleneckFinding> storedFindings = findingRepository.findByVenuePlanIdOrderByActiveDescIdAsc(plan.getId());
        Integer ingressWindow = capacityReviewRepository.findByEventId(eventId)
                .map(CapacityReview::getIngressWindowMinutes)
                .orElse(null);
        BottleneckScreeningService.ScreeningResult screeningResult = screeningService.screen(
                zones, elements, nodes, edges, base.expectedAttendance(), ingressWindow, null);

        return buildWorkspaceDto(base, zones, elements, nodes, edges, storedFindings,
                screeningResult, canEdit, canReviewFindings, null, null);
    }

    private NetworkDtos.NetworkWorkspaceDto syncAndBuildWorkspace(Event event,
                                                                  VenuePlan plan,
                                                                  boolean canEdit,
                                                                  boolean canReviewFindings,
                                                                  Long lastModifiedNodeId,
                                                                  Long lastModifiedEdgeId) {
        List<FunctionalZone> zones = functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<InfrastructureElement> elements = infrastructureElementRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<PathwayNode> nodes = nodeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());
        List<PathwayEdge> edges = edgeRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId());

        Integer ingressWindow = capacityReviewRepository.findByEventId(event.getId())
                .map(CapacityReview::getIngressWindowMinutes)
                .orElse(null);
        BottleneckScreeningService.ScreeningResult screeningResult = screeningService.screen(
                zones, elements, nodes, edges, event.getExpectedAttendance(), ingressWindow, null);

        syncFindings(plan, screeningResult.findings());

        List<BottleneckFinding> storedFindings = findingRepository.findByVenuePlanIdOrderByActiveDescIdAsc(plan.getId());
        VenueDtos.VenueWorkspaceDto base = venueService.getWorkspaceState(event.getId(), canEdit);
        return buildWorkspaceDto(base, zones, elements, nodes, edges, storedFindings,
                screeningResult, canEdit, canReviewFindings, lastModifiedNodeId, lastModifiedEdgeId);
    }

    private void syncFindings(VenuePlan plan, List<BottleneckScreeningService.DetectedBottleneck> detected) {
        List<BottleneckFinding> existing = findingRepository.findByVenuePlanIdOrderByActiveDescIdAsc(plan.getId());
        Map<String, BottleneckFinding> existingByKey = new HashMap<>();
        for (BottleneckFinding bf : existing) {
            existingByKey.put(bf.getFindingKey(), bf);
        }

        Set<String> activeKeys = new HashSet<>();
        List<BottleneckFinding> toSave = new ArrayList<>();
        for (BottleneckScreeningService.DetectedBottleneck db : detected) {
            activeKeys.add(db.findingKey());
            BottleneckFinding finding = existingByKey.get(db.findingKey());
            if (finding == null) {
                finding = new BottleneckFinding();
                finding.setVenuePlan(plan);
                finding.setFindingKey(truncate(db.findingKey(), 180));
                finding.setReviewStatus(FindingReviewStatus.OPEN);
            } else if (!finding.isActive()) {
                finding.setReviewStatus(FindingReviewStatus.OPEN);
            }
            finding.setFindingType(db.findingType());
            finding.setSeverity(db.severity());
            finding.setTitle(truncate(db.title(), 200));
            finding.setDescription(truncate(db.description(), 1000));
            finding.setMeasuredValue(truncate(db.measuredValue(), 160));
            finding.setThresholdNote(truncate(db.thresholdNote(), 250));
            finding.setLocationX(db.locationX() != null ? geometryUtil.round2(db.locationX()) : null);
            finding.setLocationY(db.locationY() != null ? geometryUtil.round2(db.locationY()) : null);
            finding.setInvolvedNodeIds(joinIds(db.involvedNodeIds()));
            finding.setInvolvedEdgeIds(joinIds(db.involvedEdgeIds()));
            finding.setActive(true);
            finding.setResolvedAt(null);
            toSave.add(finding);
        }

        List<BottleneckFinding> toDelete = new ArrayList<>();
        Instant now = Instant.now();
        for (BottleneckFinding old : existing) {
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

    private NetworkDtos.NetworkWorkspaceDto buildWorkspaceDto(VenueDtos.VenueWorkspaceDto base,
                                                              List<FunctionalZone> zones,
                                                              List<InfrastructureElement> elements,
                                                              List<PathwayNode> nodes,
                                                              List<PathwayEdge> edges,
                                                              List<BottleneckFinding> storedFindings,
                                                              BottleneckScreeningService.ScreeningResult screeningResult,
                                                              boolean canEdit,
                                                              boolean canReviewFindings,
                                                              Long lastModifiedNodeId,
                                                              Long lastModifiedEdgeId) {
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
        for (InfrastructureElement el : elements) {
            InfrastructureType type = el.getElementType();
            InfrastructureDtos.ElementMetadata meta = InfrastructureDtos.ElementMetadata.parse(
                    objectMapper, el.getMetadataJson());
            Polygon footprint = parseOrBuildFootprint(el);
            List<GeometryUtil.Point2D> footprintPts = geometryUtil.extractOuterRing(footprint);
            double clearanceBufferM = type.isExitPortal()
                    ? meta.effectiveClearanceBufferM()
                    : InfrastructureDtos.DEFAULT_EXIT_CLEARANCE_METERS;
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
                    List.of()
            ));
        }

        List<NetworkDtos.PathwayNodeDto> nodeDtos = new ArrayList<>(nodes.size());
        for (PathwayNode n : nodes) {
            FunctionalZone lz = n.getLinkedZone();
            InfrastructureElement li = n.getLinkedInfra();
            nodeDtos.add(new NetworkDtos.PathwayNodeDto(
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

        List<NetworkDtos.PathwayEdgeDto> edgeDtos = new ArrayList<>(edges.size());
        int activeEdges = 0;
        int entryLayerEdges = 0;
        int circulationLayerEdges = 0;
        int exitLayerEdges = 0;
        int emergencyLayerEdges = 0;
        double totalLength = 0.0;

        for (PathwayEdge e : edges) {
            PathwayNode fn = e.getFromNode();
            PathwayNode tn = e.getToNode();
            List<GeometryUtil.Point2D> pts = extractOrInitEdgePoints(e);
            GeometryUtil.Point2D mid = computeMidpoint(e, pts);
            if (e.isActive()) {
                activeEdges++;
                totalLength += e.getLengthM();
                switch (e.getNetworkLayer()) {
                    case NORMAL_ENTRY -> entryLayerEdges++;
                    case INTERNAL_CIRCULATION -> circulationLayerEdges++;
                    case NORMAL_EXIT -> exitLayerEdges++;
                    case EMERGENCY_ACCESS -> emergencyLayerEdges++;
                }
            }
            edgeDtos.add(new NetworkDtos.PathwayEdgeDto(
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

        List<NetworkDtos.BottleneckFindingDto> activeFindings = new ArrayList<>();
        List<NetworkDtos.BottleneckFindingDto> historyFindings = new ArrayList<>();
        int criticalCount = 0;
        int highCount = 0;
        int lowCount = 0;
        int infoCount = 0;
        int openCount = 0;

        for (BottleneckFinding bf : storedFindings) {
            NetworkDtos.BottleneckFindingDto dto = toFindingDto(bf);
            if (bf.isActive()) {
                activeFindings.add(dto);
                switch (bf.getSeverity()) {
                    case CRITICAL -> criticalCount++;
                    case HIGH -> highCount++;
                    case LOW -> lowCount++;
                    case INFO -> infoCount++;
                }
                if (bf.getReviewStatus() == FindingReviewStatus.OPEN) {
                    openCount++;
                }
            } else {
                historyFindings.add(dto);
            }
        }

        activeFindings.sort(Comparator
                .comparingInt((NetworkDtos.BottleneckFindingDto f) -> f.severity().sortRank())
                .thenComparing(NetworkDtos.BottleneckFindingDto::findingKey));
        historyFindings.sort(Comparator
                .comparing(NetworkDtos.BottleneckFindingDto::resolvedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(NetworkDtos.BottleneckFindingDto::findingKey));

        NetworkDtos.NetworkSummaryDto summary = new NetworkDtos.NetworkSummaryDto(
                nodes.size(),
                edges.size(),
                activeEdges,
                entryLayerEdges,
                circulationLayerEdges,
                exitLayerEdges,
                emergencyLayerEdges,
                geometryUtil.round2(totalLength),
                screeningResult.connectedComponents(),
                screeningResult.connectedEntryCapacityPerMin(),
                screeningResult.estimatedArrivalDemandPerMin(),
                screeningResult.effectiveIngressWindowMin(),
                criticalCount,
                highCount,
                lowCount,
                infoCount,
                openCount,
                historyFindings.size()
        );

        return new NetworkDtos.NetworkWorkspaceDto(
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
                List.copyOf(nodeDtos),
                List.copyOf(edgeDtos),
                List.copyOf(activeFindings),
                List.copyOf(historyFindings),
                summary,
                lastModifiedNodeId,
                lastModifiedEdgeId
        );
    }

    private NetworkDtos.BottleneckFindingDto toFindingDto(BottleneckFinding bf) {
        return new NetworkDtos.BottleneckFindingDto(
                bf.getId(),
                bf.getFindingKey(),
                bf.getFindingType(),
                bf.getFindingType().getDisplayName(),
                bf.getSeverity(),
                bf.getTitle(),
                bf.getDescription(),
                bf.getMeasuredValue(),
                bf.getThresholdNote(),
                bf.getLocationX(),
                bf.getLocationY(),
                parseIds(bf.getInvolvedNodeIds()),
                parseIds(bf.getInvolvedEdgeIds()),
                bf.isActive(),
                bf.getReviewStatus(),
                bf.getReviewStatus().getDisplayName(),
                bf.getReviewerNote(),
                bf.getReviewedBy() != null ? bf.getReviewedBy().getFullName() : null,
                bf.getReviewedAt(),
                bf.getResolvedAt()
        );
    }

    private PathwayNodeType mapInfraToNodeType(InfrastructureType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case ENTRY_GATE -> PathwayNodeType.ENTRY_GATE;
            case EXIT_GATE -> PathwayNodeType.EXIT_GATE;
            case EMERGENCY_EXIT -> PathwayNodeType.EMERGENCY_EXIT;
            case SECURITY_CHECKPOINT -> PathwayNodeType.SECURITY_CHECKPOINT;
            case MEDICAL_POST, CONTROL_ROOM -> PathwayNodeType.SERVICE_POINT;
            default -> null;
        };
    }

    private boolean isSeedableZone(ZoneType type) {
        return type == ZoneType.AUDIENCE
                || type == ZoneType.FOOD_STALLS
                || type == ZoneType.RESTROOMS
                || type == ZoneType.MEDICAL
                || type == ZoneType.QUEUE;
    }

    private FunctionalZone resolveZoneForPlan(Long venuePlanId, Long zoneId) {
        if (zoneId == null) {
            return null;
        }
        return functionalZoneRepository.findByIdAndVenuePlanId(zoneId, venuePlanId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Linked functional zone not found in venue."));
    }

    private InfrastructureElement resolveInfraForPlan(Long venuePlanId, Long infraId) {
        if (infraId == null) {
            return null;
        }
        return infrastructureElementRepository.findByIdAndVenuePlanId(infraId, venuePlanId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Linked infrastructure element not found in venue."));
    }

    private List<GeometryUtil.Point2D> assembleEdgePoints(PathwayNode fromNode,
                                                        List<GeometryUtil.Point2D> waypoints,
                                                        PathwayNode toNode) {
        List<GeometryUtil.Point2D> pts = new ArrayList<>();
        pts.add(new GeometryUtil.Point2D(fromNode.getX(), fromNode.getY()));
        if (waypoints != null) {
            for (GeometryUtil.Point2D wp : waypoints) {
                if (wp == null || !Double.isFinite(wp.x()) || !Double.isFinite(wp.y())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pathway waypoints must have finite coordinates.");
                }
                pts.add(new GeometryUtil.Point2D(geometryUtil.round2(wp.x()), geometryUtil.round2(wp.y())));
            }
        }
        pts.add(new GeometryUtil.Point2D(toNode.getX(), toNode.getY()));
        return pts;
    }

    private List<GeometryUtil.Point2D> extractInteriorPoints(List<GeometryUtil.Point2D> points) {
        if (points == null || points.size() <= 2) {
            return List.of();
        }
        return points.subList(1, points.size() - 1);
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
                // Fall back to endpoint chord below
            }
        }
        return List.of(
                new GeometryUtil.Point2D(edge.getFromNode().getX(), edge.getFromNode().getY()),
                new GeometryUtil.Point2D(edge.getToNode().getX(), edge.getToNode().getY())
        );
    }

    private GeometryUtil.Point2D computeMidpoint(PathwayEdge edge, List<GeometryUtil.Point2D> pts) {
        if (edge.getPolylineWkt() != null && !edge.getPolylineWkt().isBlank()) {
            try {
                return geometryUtil.lineMidpoint(geometryUtil.parseLineString(edge.getPolylineWkt()));
            } catch (IllegalArgumentException ignored) {
                // Fall back below
            }
        }
        GeometryUtil.Point2D start = pts.get(0);
        GeometryUtil.Point2D end = pts.get(pts.size() - 1);
        return new GeometryUtil.Point2D((start.x() + end.x()) / 2.0, (start.y() + end.y()) / 2.0);
    }

    private LineString buildValidEdgeLine(List<GeometryUtil.Point2D> pts) {
        try {
            return geometryUtil.buildLineString(pts);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private Polygon parseOrBuildFootprint(InfrastructureElement el) {
        if (el.getFootprintWkt() != null && !el.getFootprintWkt().isBlank()) {
            try {
                return geometryUtil.parsePolygon(el.getFootprintWkt());
            } catch (IllegalArgumentException ignored) {
                // Fall back
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

    private double validateWidth(Double widthM) {
        double w = requireFinite(widthM, "Pathway width");
        if (w < NetworkDtos.MIN_EDGE_WIDTH_M || w > NetworkDtos.MAX_EDGE_WIDTH_M) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pathway width must be between 0.3 m and 100.0 m.");
        }
        return w;
    }

    private String resolveNodeLabel(String requested, PathwayNodeType type, List<PathwayNode> existing) {
        if (requested != null && !requested.isBlank()) {
            return truncate(requested.trim(), 160);
        }
        long sameType = existing.stream().filter(n -> n.getNodeType() == type).count();
        return type.getDisplayName() + " " + (sameType + 1);
    }

    private String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
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
                // Ignore malformed token
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
