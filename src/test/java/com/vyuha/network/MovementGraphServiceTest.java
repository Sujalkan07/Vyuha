package com.vyuha.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.vyuha.common.GeometryUtil;
import com.vyuha.network.MovementGraphService.DeadEndStem;
import com.vyuha.network.MovementGraphService.MovementGraph;
import com.vyuha.network.MovementGraphService.PathResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.LineString;

class MovementGraphServiceTest {

    private final GeometryUtil geometryUtil = new GeometryUtil();
    private final MovementGraphService graphService = new MovementGraphService();

    private PathwayNode node(long id, String uid, PathwayNodeType type, double x, double y) {
        PathwayNode n = new PathwayNode();
        n.setId(id);
        n.setNodeUid(uid);
        n.setNodeType(type);
        n.setLabel(uid);
        n.setX(x);
        n.setY(y);
        return n;
    }

    private PathwayEdge edge(long id,
                             String uid,
                             PathwayNode from,
                             PathwayNode to,
                             NetworkLayer layer,
                             double widthM,
                             PathwayDirection direction,
                             boolean active) {
        LineString ls = geometryUtil.buildLineString(List.of(
                new GeometryUtil.Point2D(from.getX(), from.getY()),
                new GeometryUtil.Point2D(to.getX(), to.getY())
        ));
        PathwayEdge e = new PathwayEdge();
        e.setId(id);
        e.setEdgeUid(uid);
        e.setFromNode(from);
        e.setToNode(to);
        e.setLabel(uid);
        e.setNetworkLayer(layer);
        e.setWidthM(widthM);
        e.setLengthM(geometryUtil.round2(ls.getLength()));
        e.setDirection(direction);
        e.setPolylineWkt(geometryUtil.toWkt(ls));
        e.setActive(active);
        return e;
    }

    @Test
    void respectsOneWayVsBidirectionalAndLayerFilteringInBfsAndDijkstra() {
        PathwayNode entry = node(1L, "n-entry", PathwayNodeType.ENTRY_GATE, 0, 0);
        PathwayNode hub = node(2L, "n-hub", PathwayNodeType.ZONE_HUB, 20, 0);
        PathwayNode exit = node(3L, "n-exit", PathwayNodeType.EXIT_GATE, 40, 0);
        PathwayNode emExit = node(4L, "n-em", PathwayNodeType.EMERGENCY_EXIT, 20, 25);

        PathwayEdge e1 = edge(10L, "e-in", entry, hub, NetworkLayer.NORMAL_ENTRY, 4.0, PathwayDirection.ONE_WAY, true);
        PathwayEdge e2 = edge(11L, "e-out", hub, exit, NetworkLayer.NORMAL_EXIT, 3.5, PathwayDirection.ONE_WAY, true);
        PathwayEdge e3 = edge(12L, "e-em", hub, emExit, NetworkLayer.EMERGENCY_ACCESS, 4.0, PathwayDirection.BIDIRECTIONAL, true);

        MovementGraph graph = graphService.buildGraph(List.of(entry, hub, exit, emExit), List.of(e1, e2, e3));

        Set<NetworkLayer> normalOnly = Set.of(
                NetworkLayer.NORMAL_ENTRY, NetworkLayer.INTERNAL_CIRCULATION, NetworkLayer.NORMAL_EXIT);

        // Forward from entry on normal layers reaches hub and exit, not emergency exit
        Set<String> fwdNormal = graphService.findReachableForward(graph, Set.of("n-entry"), normalOnly);
        assertThat(fwdNormal).containsExactlyInAnyOrder("n-entry", "n-hub", "n-exit");

        // Backward from entry cannot travel against ONE_WAY edge e1
        Set<String> fwdFromExit = graphService.findReachableForward(graph, Set.of("n-exit"), Set.of());
        assertThat(fwdFromExit).containsExactly("n-exit");

        // Backward from normal exit reaches hub and entry
        Set<String> bwdExit = graphService.findReachableBackward(graph, Set.of("n-exit"), normalOnly);
        assertThat(bwdExit).containsExactlyInAnyOrder("n-exit", "n-hub", "n-entry");

        // Shortest path entry -> emExit requires EMERGENCY_ACCESS
        assertThat(graphService.shortestPath(graph, "n-entry", "n-em", normalOnly)).isNull();
        PathResult emPath = graphService.shortestPath(graph, "n-entry", "n-em", Set.of());
        assertThat(emPath).isNotNull();
        assertThat(emPath.nodeKeys()).containsExactly("n-entry", "n-hub", "n-em");
        assertThat(emPath.totalLengthM()).isCloseTo(45.0, within(0.01));
        assertThat(emPath.minWidthM()).isCloseTo(4.0, within(0.01));
    }

    @Test
    void computesShortestPathExcludesInactiveOrDisabledEdgesAndCountsComponents() {
        PathwayNode a = node(1L, "A", PathwayNodeType.ENTRY_GATE, 0, 0);
        PathwayNode b = node(2L, "B", PathwayNodeType.INTERSECTION, 10, 0);
        PathwayNode c = node(3L, "C", PathwayNodeType.INTERSECTION, 10, 20);
        PathwayNode d = node(4L, "D", PathwayNodeType.ZONE_HUB, 20, 0);
        PathwayNode island = node(5L, "ISLAND", PathwayNodeType.ZONE_HUB, 80, 80);

        PathwayEdge ab = edge(10L, "AB", a, b, NetworkLayer.INTERNAL_CIRCULATION, 3.0, PathwayDirection.BIDIRECTIONAL, true);
        PathwayEdge bdShort = edge(11L, "BD-short", b, d, NetworkLayer.INTERNAL_CIRCULATION, 2.0, PathwayDirection.BIDIRECTIONAL, true);
        PathwayEdge bc = edge(12L, "BC", b, c, NetworkLayer.INTERNAL_CIRCULATION, 4.0, PathwayDirection.BIDIRECTIONAL, true);
        PathwayEdge cd = edge(13L, "CD", c, d, NetworkLayer.INTERNAL_CIRCULATION, 4.0, PathwayDirection.BIDIRECTIONAL, true);
        PathwayEdge disabledDirect = edge(14L, "AD-off", a, d, NetworkLayer.INTERNAL_CIRCULATION, 5.0, PathwayDirection.BIDIRECTIONAL, false);

        MovementGraph graph = graphService.buildGraph(
                List.of(a, b, c, d, island), List.of(ab, bdShort, bc, cd, disabledDirect));

        assertThat(graphService.countConnectedComponents(graph)).isEqualTo(2);

        PathResult shortest = graphService.shortestPath(graph, "A", "D", Set.of());
        assertThat(shortest).isNotNull();
        assertThat(shortest.edgeKeys()).containsExactly("AB", "BD-short");
        assertThat(shortest.totalLengthM()).isCloseTo(20.0, within(0.01));
        assertThat(shortest.minWidthM()).isCloseTo(2.0, within(0.01));

        // Excluding edge 11 forces detour A -> B -> C -> D
        MovementGraph outageGraph = graphService.buildGraph(
                List.of(a, b, c, d, island), List.of(ab, bdShort, bc, cd, disabledDirect), Set.of(), Set.of(11L));
        PathResult detour = graphService.shortestPath(outageGraph, "A", "D", Set.of());
        assertThat(detour).isNotNull();
        assertThat(detour.nodeKeys()).containsExactly("A", "B", "C", "D");
        assertThat(detour.minWidthM()).isCloseTo(3.0, within(0.01));
    }

    @Test
    void tracesMultiSegmentDeadEndStemsUntilJunctionOrAnchor() {
        PathwayNode hub = node(1L, "hub", PathwayNodeType.ZONE_HUB, 0, 0);
        PathwayNode junc = node(2L, "junc", PathwayNodeType.INTERSECTION, 10, 0);
        PathwayNode armOut = node(3L, "exit", PathwayNodeType.EXIT_GATE, 10, 10);
        PathwayNode mid1 = node(4L, "mid1", PathwayNodeType.INTERSECTION, 16, 0);
        PathwayNode tip = node(5L, "tip", PathwayNodeType.INTERSECTION, 24, 0);

        PathwayEdge eHubJunc = edge(1L, "e1", hub, junc, NetworkLayer.INTERNAL_CIRCULATION, 3.0, PathwayDirection.BIDIRECTIONAL, true);
        PathwayEdge eJuncExit = edge(2L, "e2", junc, armOut, NetworkLayer.NORMAL_EXIT, 3.0, PathwayDirection.BIDIRECTIONAL, true);
        PathwayEdge eJuncMid = edge(3L, "e3", junc, mid1, NetworkLayer.INTERNAL_CIRCULATION, 3.0, PathwayDirection.BIDIRECTIONAL, true);
        PathwayEdge eMidTip = edge(4L, "e4", mid1, tip, NetworkLayer.INTERNAL_CIRCULATION, 3.0, PathwayDirection.BIDIRECTIONAL, true);

        MovementGraph graph = graphService.buildGraph(
                List.of(hub, junc, armOut, mid1, tip), List.of(eHubJunc, eJuncExit, eJuncMid, eMidTip));

        DeadEndStem stem = graphService.computeDeadEndStem(graph, "tip");
        assertThat(stem.totalLengthM()).isCloseTo(14.0, within(0.01));
        assertThat(stem.nodeKeys()).containsExactly("tip", "mid1", "junc");
        assertThat(stem.edgeKeys()).containsExactly("e4", "e3");
    }
}
