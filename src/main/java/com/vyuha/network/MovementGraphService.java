package com.vyuha.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Pure-Java pedestrian movement graph builder and traversal engine (BFS, reverse BFS,
 * Dijkstra shortest weighted path, connected component analysis, and dead-end stem tracing).
 */
@Service
public class MovementGraphService {

    public record GraphNode(
            String key,
            PathwayNode entity
    ) {
    }

    public record GraphEdge(
            String key,
            String fromKey,
            String toKey,
            PathwayEdge entity,
            double lengthM,
            double widthM,
            NetworkLayer layer,
            PathwayDirection direction,
            boolean active
    ) {
    }

    public record DirectedTraversal(
            String targetNodeKey,
            GraphEdge edge
    ) {
    }

    public record PathResult(
            List<String> nodeKeys,
            List<String> edgeKeys,
            double totalLengthM,
            double minWidthM
    ) {
    }

    public record DeadEndStem(
            double totalLengthM,
            List<String> nodeKeys,
            List<String> edgeKeys
    ) {
    }

    public static final class MovementGraph {
        private final Map<String, GraphNode> nodesByKey;
        private final Map<Long, String> nodeKeyById;
        private final List<GraphEdge> activeEdges;
        private final List<GraphEdge> allEdges;
        private final Map<String, List<GraphEdge>> undirectedByNode;
        private final Map<String, List<DirectedTraversal>> outgoingByNode;
        private final Map<String, List<DirectedTraversal>> incomingByNode;

        private MovementGraph(Map<String, GraphNode> nodesByKey,
                              Map<Long, String> nodeKeyById,
                              List<GraphEdge> allEdges,
                              List<GraphEdge> activeEdges,
                              Map<String, List<GraphEdge>> undirectedByNode,
                              Map<String, List<DirectedTraversal>> outgoingByNode,
                              Map<String, List<DirectedTraversal>> incomingByNode) {
            this.nodesByKey = Collections.unmodifiableMap(nodesByKey);
            this.nodeKeyById = Collections.unmodifiableMap(nodeKeyById);
            this.allEdges = List.copyOf(allEdges);
            this.activeEdges = List.copyOf(activeEdges);
            this.undirectedByNode = undirectedByNode;
            this.outgoingByNode = outgoingByNode;
            this.incomingByNode = incomingByNode;
        }

        public Map<String, GraphNode> nodesByKey() {
            return nodesByKey;
        }

        public List<GraphNode> nodes() {
            return List.copyOf(nodesByKey.values());
        }

        public GraphNode node(String key) {
            return nodesByKey.get(key);
        }

        public String keyForNodeId(Long nodeId) {
            return nodeId != null ? nodeKeyById.get(nodeId) : null;
        }

        public List<GraphEdge> allEdges() {
            return allEdges;
        }

        public List<GraphEdge> activeEdges() {
            return activeEdges;
        }

        public List<GraphEdge> undirectedEdges(String nodeKey) {
            return undirectedByNode.getOrDefault(nodeKey, List.of());
        }

        public List<DirectedTraversal> outgoingTraversals(String nodeKey, Set<NetworkLayer> allowedLayers) {
            List<DirectedTraversal> list = outgoingByNode.getOrDefault(nodeKey, List.of());
            if (allowedLayers == null || allowedLayers.isEmpty()) {
                return list;
            }
            return list.stream().filter(t -> allowedLayers.contains(t.edge().layer())).toList();
        }

        public List<DirectedTraversal> incomingTraversals(String nodeKey, Set<NetworkLayer> allowedLayers) {
            List<DirectedTraversal> list = incomingByNode.getOrDefault(nodeKey, List.of());
            if (allowedLayers == null || allowedLayers.isEmpty()) {
                return list;
            }
            return list.stream().filter(t -> allowedLayers.contains(t.edge().layer())).toList();
        }
    }

    public MovementGraph buildGraph(List<PathwayNode> nodes, List<PathwayEdge> edges) {
        return buildGraph(nodes, edges, Set.of(), Set.of());
    }

    public MovementGraph buildGraph(List<PathwayNode> nodes,
                                    List<PathwayEdge> edges,
                                    Set<Long> excludedNodeIds,
                                    Set<Long> excludedEdgeIds) {
        List<PathwayNode> safeNodes = nodes != null ? nodes : List.of();
        List<PathwayEdge> safeEdges = edges != null ? edges : List.of();
        Set<Long> skipNodes = excludedNodeIds != null ? excludedNodeIds : Set.of();
        Set<Long> skipEdges = excludedEdgeIds != null ? excludedEdgeIds : Set.of();

        Map<String, GraphNode> nodesByKey = new LinkedHashMap<>();
        Map<PathwayNode, String> keyByObj = new HashMap<>();
        Map<Long, String> keyById = new HashMap<>();

        for (int i = 0; i < safeNodes.size(); i++) {
            PathwayNode n = safeNodes.get(i);
            if (n == null || (n.getId() != null && skipNodes.contains(n.getId()))) {
                continue;
            }
            String key = nodeKey(n, i);
            GraphNode gn = new GraphNode(key, n);
            nodesByKey.put(key, gn);
            keyByObj.put(n, key);
            if (n.getId() != null) {
                keyById.put(n.getId(), key);
            }
        }

        Map<String, List<GraphEdge>> undirected = new LinkedHashMap<>();
        Map<String, List<DirectedTraversal>> outgoing = new LinkedHashMap<>();
        Map<String, List<DirectedTraversal>> incoming = new LinkedHashMap<>();
        for (String nk : nodesByKey.keySet()) {
            undirected.put(nk, new ArrayList<>());
            outgoing.put(nk, new ArrayList<>());
            incoming.put(nk, new ArrayList<>());
        }

        List<GraphEdge> allEdges = new ArrayList<>();
        List<GraphEdge> activeEdges = new ArrayList<>();

        for (int i = 0; i < safeEdges.size(); i++) {
            PathwayEdge e = safeEdges.get(i);
            if (e == null || (e.getId() != null && skipEdges.contains(e.getId()))) {
                continue;
            }
            String fromKey = resolveNodeKey(e.getFromNode(), keyByObj, keyById);
            String toKey = resolveNodeKey(e.getToNode(), keyByObj, keyById);
            if (fromKey == null || toKey == null || fromKey.equals(toKey)) {
                continue;
            }
            double len = resolveLength(e);
            double width = e.getWidthM();
            NetworkLayer layer = e.getNetworkLayer() != null ? e.getNetworkLayer() : NetworkLayer.INTERNAL_CIRCULATION;
            PathwayDirection dir = e.getDirection() != null ? e.getDirection() : PathwayDirection.BIDIRECTIONAL;
            GraphEdge ge = new GraphEdge(edgeKey(e, i), fromKey, toKey, e, len, width, layer, dir, e.isActive());
            allEdges.add(ge);

            if (!ge.active()) {
                continue;
            }
            activeEdges.add(ge);
            undirected.get(fromKey).add(ge);
            undirected.get(toKey).add(ge);

            // Forward edge from -> to
            outgoing.get(fromKey).add(new DirectedTraversal(toKey, ge));
            incoming.get(toKey).add(new DirectedTraversal(fromKey, ge));

            // Reverse edge to -> from if bidirectional
            if (dir == PathwayDirection.BIDIRECTIONAL) {
                outgoing.get(toKey).add(new DirectedTraversal(fromKey, ge));
                incoming.get(fromKey).add(new DirectedTraversal(toKey, ge));
            }
        }

        return new MovementGraph(nodesByKey, keyById, allEdges, activeEdges, undirected, outgoing, incoming);
    }

    public Set<String> findReachableForward(MovementGraph graph,
                                            Set<String> sourceKeys,
                                            Set<NetworkLayer> allowedLayers) {
        Set<String> visited = new LinkedHashSet<>();
        if (graph == null || sourceKeys == null || sourceKeys.isEmpty()) {
            return visited;
        }
        Queue<String> queue = new ArrayDeque<>();
        for (String src : sourceKeys) {
            if (graph.node(src) != null && visited.add(src)) {
                queue.add(src);
            }
        }
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            for (DirectedTraversal t : graph.outgoingTraversals(curr, allowedLayers)) {
                if (visited.add(t.targetNodeKey())) {
                    queue.add(t.targetNodeKey());
                }
            }
        }
        return visited;
    }

    public Set<String> findReachableBackward(MovementGraph graph,
                                             Set<String> targetKeys,
                                             Set<NetworkLayer> allowedLayers) {
        Set<String> visited = new LinkedHashSet<>();
        if (graph == null || targetKeys == null || targetKeys.isEmpty()) {
            return visited;
        }
        Queue<String> queue = new ArrayDeque<>();
        for (String tgt : targetKeys) {
            if (graph.node(tgt) != null && visited.add(tgt)) {
                queue.add(tgt);
            }
        }
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            for (DirectedTraversal t : graph.incomingTraversals(curr, allowedLayers)) {
                if (visited.add(t.targetNodeKey())) {
                    queue.add(t.targetNodeKey());
                }
            }
        }
        return visited;
    }

    public PathResult shortestPath(MovementGraph graph,
                                   String startKey,
                                   String endKey,
                                   Set<NetworkLayer> allowedLayers) {
        if (endKey == null) {
            return null;
        }
        return shortestPathToAny(graph, startKey, Set.of(endKey), allowedLayers);
    }

    public PathResult shortestPathToAny(MovementGraph graph,
                                        String startKey,
                                        Set<String> targetKeys,
                                        Set<NetworkLayer> allowedLayers) {
        if (graph == null || startKey == null || targetKeys == null || targetKeys.isEmpty()
                || graph.node(startKey) == null) {
            return null;
        }
        Map<String, Double> dist = new HashMap<>();
        Map<String, String> prevNode = new HashMap<>();
        Map<String, GraphEdge> prevEdge = new HashMap<>();

        record QueueEntry(String nodeKey, double distance) {
        }
        PriorityQueue<QueueEntry> pq = new PriorityQueue<>(
                Comparator.comparingDouble(QueueEntry::distance).thenComparing(QueueEntry::nodeKey));

        dist.put(startKey, 0.0);
        pq.add(new QueueEntry(startKey, 0.0));

        String reachedTarget = null;
        while (!pq.isEmpty()) {
            QueueEntry top = pq.poll();
            if (top.distance() > dist.getOrDefault(top.nodeKey(), Double.POSITIVE_INFINITY) + 1e-9) {
                continue;
            }
            if (targetKeys.contains(top.nodeKey())) {
                reachedTarget = top.nodeKey();
                break;
            }
            for (DirectedTraversal t : graph.outgoingTraversals(top.nodeKey(), allowedLayers)) {
                double edgeLen = Math.max(0.01, t.edge().lengthM());
                double nextDist = top.distance() + edgeLen;
                double prevBest = dist.getOrDefault(t.targetNodeKey(), Double.POSITIVE_INFINITY);
                if (nextDist < prevBest - 1e-9) {
                    dist.put(t.targetNodeKey(), nextDist);
                    prevNode.put(t.targetNodeKey(), top.nodeKey());
                    prevEdge.put(t.targetNodeKey(), t.edge());
                    pq.add(new QueueEntry(t.targetNodeKey(), nextDist));
                }
            }
        }

        if (reachedTarget == null) {
            return null;
        }

        List<String> nodes = new ArrayList<>();
        List<String> edges = new ArrayList<>();
        double minWidth = Double.POSITIVE_INFINITY;
        String curr = reachedTarget;
        nodes.add(curr);
        while (!curr.equals(startKey)) {
            GraphEdge pe = prevEdge.get(curr);
            String pn = prevNode.get(curr);
            if (pe == null || pn == null) {
                break;
            }
            edges.add(pe.key());
            if (pe.widthM() < minWidth) {
                minWidth = pe.widthM();
            }
            curr = pn;
            nodes.add(curr);
        }
        Collections.reverse(nodes);
        Collections.reverse(edges);
        if (Double.isInfinite(minWidth)) {
            minWidth = 0.0;
        }
        return new PathResult(List.copyOf(nodes), List.copyOf(edges),
                dist.getOrDefault(reachedTarget, 0.0), minWidth);
    }

    public int countConnectedComponents(MovementGraph graph) {
        if (graph == null || graph.nodesByKey().isEmpty()) {
            return 0;
        }
        Set<String> visited = new HashSet<>();
        int components = 0;
        for (String start : graph.nodesByKey().keySet()) {
            if (!visited.add(start)) {
                continue;
            }
            components++;
            Queue<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String curr = queue.poll();
                for (GraphEdge e : graph.undirectedEdges(curr)) {
                    String other = e.fromKey().equals(curr) ? e.toKey() : e.fromKey();
                    if (visited.add(other)) {
                        queue.add(other);
                    }
                }
            }
        }
        return components;
    }

    /**
     * Walks back from a degree-1 INTERSECTION node along any degree-2 INTERSECTION chain
     * until reaching a junction (degree >= 3) or non-intersection anchor node.
     */
    public DeadEndStem computeDeadEndStem(MovementGraph graph, String deadEndNodeKey) {
        List<String> nodeKeys = new ArrayList<>();
        List<String> edgeKeys = new ArrayList<>();
        double totalLength = 0.0;

        if (graph == null || deadEndNodeKey == null) {
            return new DeadEndStem(0.0, List.of(), List.of());
        }
        Set<String> visited = new HashSet<>();
        String curr = deadEndNodeKey;
        String prevEdgeKey = null;
        nodeKeys.add(curr);
        visited.add(curr);

        while (true) {
            List<GraphEdge> incident = graph.undirectedEdges(curr);
            GraphEdge nextEdge = null;
            for (GraphEdge e : incident) {
                if (!e.key().equals(prevEdgeKey)) {
                    nextEdge = e;
                    break;
                }
            }
            if (nextEdge == null) {
                break;
            }
            totalLength += nextEdge.lengthM();
            edgeKeys.add(nextEdge.key());
            String nextNode = nextEdge.fromKey().equals(curr) ? nextEdge.toKey() : nextEdge.fromKey();
            if (!visited.add(nextNode)) {
                break;
            }
            nodeKeys.add(nextNode);

            GraphNode gn = graph.node(nextNode);
            boolean isDegreeTwoIntersection = gn != null
                    && gn.entity().getNodeType() == PathwayNodeType.INTERSECTION
                    && graph.undirectedEdges(nextNode).size() == 2;
            if (!isDegreeTwoIntersection) {
                break;
            }
            curr = nextNode;
            prevEdgeKey = nextEdge.key();
        }

        return new DeadEndStem(totalLength, List.copyOf(nodeKeys), List.copyOf(edgeKeys));
    }

    public static String nodeKey(PathwayNode node, int index) {
        if (node.getNodeUid() != null && !node.getNodeUid().isBlank()) {
            return node.getNodeUid().trim();
        }
        if (node.getId() != null) {
            return "ID-" + node.getId();
        }
        return "NIDX-" + index;
    }

    public static String edgeKey(PathwayEdge edge, int index) {
        if (edge.getEdgeUid() != null && !edge.getEdgeUid().isBlank()) {
            return edge.getEdgeUid().trim();
        }
        if (edge.getId() != null) {
            return "ID-" + edge.getId();
        }
        return "EIDX-" + index;
    }

    private String resolveNodeKey(PathwayNode node, Map<PathwayNode, String> keyByObj, Map<Long, String> keyById) {
        if (node == null) {
            return null;
        }
        String k = keyByObj.get(node);
        if (k != null) {
            return k;
        }
        if (node.getId() != null) {
            return keyById.get(node.getId());
        }
        return null;
    }

    private double resolveLength(PathwayEdge edge) {
        if (edge.getLengthM() > 0.0 && Double.isFinite(edge.getLengthM())) {
            return edge.getLengthM();
        }
        if (edge.getFromNode() != null && edge.getToNode() != null) {
            return Math.hypot(
                    edge.getToNode().getX() - edge.getFromNode().getX(),
                    edge.getToNode().getY() - edge.getFromNode().getY());
        }
        return 0.0;
    }
}
