import { apiDelete, apiPost, apiPut } from './api-client.js';
import { VyuhaCanvasCore } from './canvas-core.js';

const rootEl = document.getElementById('networkWorkspaceRoot');
if (rootEl) {
    initNetworkWorkspace(rootEl);
}

function initNetworkWorkspace(root) {
    const eventId = Number(root.dataset.eventId);
    const canEdit = root.dataset.canEdit === 'true';
    const canReview = root.dataset.canReview === 'true';
    const initialState = JSON.parse(root.dataset.initialState || '{}');

    const canvasEl = document.getElementById('networkCanvas');
    const hintBar = document.getElementById('canvasHintBar');
    const hintText = document.getElementById('canvasHintText');

    let state = initialState;
    let activeTool = 'SELECT'; // SELECT | ADD_NODE | DRAW_EDGE
    let selectedNodeId = null;
    let selectedEdgeId = null;
    let snapEnabled = true;
    let showRibbons = true;
    let blueprintImage = null;
    let loadedBlueprintUrl = null;
    let visibleLayers = new Set(['NORMAL_ENTRY', 'INTERNAL_CIRCULATION', 'NORMAL_EXIT', 'EMERGENCY_ACCESS']);

    let edgeDraft = null; // { fromNodeId, waypoints: [{x, y}] }
    let dragState = null; // { type: 'pan' | 'move_node', ... }
    let previewNodePos = null; // { id, x, y }
    let hoverWorld = null;
    let highlightedFinding = null; // { x, y, nodeIds, edgeIds, until }

    const undoStack = [];
    const redoStack = [];
    const MAX_UNDO = 40;

    const core = new VyuhaCanvasCore(canvasEl, {
        snapMeters: state.venuePlan?.gridSnapMeters ?? 1.0,
        showGrid: true,
        onRender: renderScene,
        onViewChange: updateZoomLabel
    });

    bindInspectorTabs();
    bindControls();
    bindCanvasInteractions();
    syncFromState(state, true);

    function syncFromState(nextState, fitView = false) {
        state = nextState;
        const plan = state.venuePlan || {};
        core.snapMeters = snapEnabled ? (plan.gridSnapMeters ?? 1.0) : 0;

        loadBlueprintIfNeeded(plan);

        if (state.lastModifiedNodeId != null
                && (state.nodes || []).some(n => n.id === state.lastModifiedNodeId)) {
            selectedNodeId = state.lastModifiedNodeId;
            selectedEdgeId = null;
        } else if (state.lastModifiedEdgeId != null
                && (state.edges || []).some(e => e.id === state.lastModifiedEdgeId)) {
            selectedEdgeId = state.lastModifiedEdgeId;
            selectedNodeId = null;
        } else {
            if (selectedNodeId != null && !(state.nodes || []).some(n => n.id === selectedNodeId)) {
                selectedNodeId = null;
            }
            if (selectedEdgeId != null && !(state.edges || []).some(e => e.id === selectedEdgeId)) {
                selectedEdgeId = null;
            }
        }

        updateStatusPill();
        updateUndoRedoButtons();
        updateSelectedInspectors();
        updateSummaryPanel(state.summary || {}, state.nodes || [], state.edges || []);
        updateFindingsPanel(state.activeFindings || [], state.resolvedHistoryFindings || []);
        updateStatusBar();

        if (fitView) {
            fitWorkspaceToContent();
        } else {
            core.requestRender();
        }
    }

    function snapshotCurrentNetwork() {
        return {
            nodes: (state.nodes || []).map((n, idx) => ({
                nodeUid: n.nodeUid,
                nodeType: n.nodeType,
                label: n.label,
                x: n.x,
                y: n.y,
                linkedZoneId: n.linkedZoneId,
                linkedInfraId: n.linkedInfraId,
                notes: n.notes,
                sortOrder: idx + 1
            })),
            edges: (state.edges || []).map((e, idx) => ({
                edgeUid: e.edgeUid,
                fromNodeUid: e.fromNodeUid,
                toNodeUid: e.toNodeUid,
                label: e.label,
                networkLayer: e.networkLayer,
                widthM: e.widthM,
                direction: e.direction,
                points: e.points || [],
                active: e.active,
                notes: e.notes,
                sortOrder: idx + 1
            }))
        };
    }

    function pushUndoSnapshot(beforeSnap) {
        undoStack.push(beforeSnap);
        if (undoStack.length > MAX_UNDO) {
            undoStack.shift();
        }
        redoStack.length = 0;
        updateUndoRedoButtons();
    }

    function loadBlueprintIfNeeded(plan) {
        if (!plan.hasBlueprint || !plan.blueprintUrl) {
            blueprintImage = null;
            loadedBlueprintUrl = null;
            return;
        }
        const cacheKey = `${plan.blueprintUrl}|${plan.blueprintOriginalFilename}|${plan.blueprintWidthPx}`;
        if (loadedBlueprintUrl === cacheKey && blueprintImage) {
            return;
        }
        loadedBlueprintUrl = cacheKey;
        const img = new Image();
        img.onload = () => {
            blueprintImage = img;
            core.requestRender();
        };
        img.src = plan.blueprintUrl + '?t=' + Date.now();
    }

    function fitWorkspaceToContent() {
        const plan = state.venuePlan || {};
        if (state.areaBreakdown && state.areaBreakdown.boundaryBounds) {
            core.fitToBounds(state.areaBreakdown.boundaryBounds, 56);
            return;
        }
        if (plan.workspaceMode === 'BLUEPRINT_UPLOAD' && plan.hasBlueprint && plan.pixelsPerMeter) {
            core.fitToBounds({
                minX: 0,
                minY: 0,
                maxX: (plan.blueprintWidthPx || 1000) / plan.pixelsPerMeter,
                maxY: (plan.blueprintHeightPx || 800) / plan.pixelsPerMeter
            }, 40);
            return;
        }
        core.fitToBounds({ minX: 0, minY: 0, maxX: 120, maxY: 90 }, 36);
    }

    function renderScene() {
        core.beginFrame();
        const plan = state.venuePlan || {};
        core.drawMetricGrid('m');

        if (plan.workspaceMode === 'BLUEPRINT_UPLOAD' && blueprintImage && plan.pixelsPerMeter) {
            core.drawBlueprintImage(blueprintImage, plan.pixelsPerMeter, plan.blueprintWidthPx, plan.blueprintHeightPx, 0.42);
        } else if (state.vectorMapGeoJson) {
            core.drawGeoJsonOverlay(state.vectorMapGeoJson);
        }

        if (plan.boundaryPoints && plan.boundaryPoints.length >= 3) {
            core.drawPolygon(plan.boundaryPoints, {
                fill: 'rgba(255, 255, 255, 0.35)',
                stroke: '#1E293B',
                lineWidth: 2.0
            });
        }

        for (const ex of (state.exclusions || [])) {
            core.drawPolygon(ex.points, {
                fill: hexToRgba(ex.fillColor || '#64748B', 0.14),
                stroke: ex.strokeColor || '#64748B',
                lineWidth: 1.0,
                hatched: true,
                hatchColor: ex.strokeColor || '#64748B'
            });
        }

        for (const zone of (state.zones || [])) {
            core.drawPolygonShapes(zone.shapes, {
                fill: hexToRgba(zone.fillColor || '#3B82F6', 0.16),
                stroke: zone.strokeColor || '#64748B',
                lineWidth: 1.1,
                hatched: Boolean(zone.hatched),
                hatchColor: zone.strokeColor || '#475569',
                label: zone.zoneCode,
                labelPoint: zone.labelPoint,
                labelFont: '600 10px system-ui, sans-serif',
                labelColor: '#475569'
            });
        }

        // Muted infrastructure context layer
        for (const el of (state.elements || [])) {
            if (Array.isArray(el.footprintPoints) && el.footprintPoints.length >= 3) {
                core.drawPolygon(el.footprintPoints, {
                    fill: hexToRgba(el.fillColor || '#64748B', el.active ? 0.14 : 0.06),
                    stroke: hexToRgba(el.strokeColor || '#334155', 0.45),
                    lineWidth: 1.0,
                    dashed: el.active ? null : [4, 3]
                });
            }
        }

        const edgeSeverity = buildFindingSeverityMap(state.activeFindings || [], 'involvedEdgeIds');
        const nodeSeverity = buildFindingSeverityMap(state.activeFindings || [], 'involvedNodeIds');

        // Draw pathway edges filtered by layer visibility
        for (const edge of (state.edges || [])) {
            if (!visibleLayers.has(edge.networkLayer)) continue;
            drawPathwayEdge(edge, edgeSeverity.get(edge.id));
        }

        // Draw active edge draft polyline
        if (activeTool === 'DRAW_EDGE' && edgeDraft) {
            const fromNode = (state.nodes || []).find(n => n.id === edgeDraft.fromNodeId);
            if (fromNode) {
                const draftPts = [{ x: fromNode.x, y: fromNode.y }, ...edgeDraft.waypoints];
                const layerSel = document.getElementById('drawLayerSelect')?.value || 'NORMAL_ENTRY';
                core.drawActiveTrace(draftPts, hoverWorld, layerColor(layerSel), 'm');
            }
        }

        // Draw pathway nodes
        for (const node of (state.nodes || [])) {
            drawPathwayNode(node, nodeSeverity.get(node.id));
        }

        // Draw bottleneck finding pins
        drawFindingMarkers(state.activeFindings || []);

        // Locate pulse ring
        if (highlightedFinding && highlightedFinding.until > Date.now()
                && highlightedFinding.x != null && highlightedFinding.y != null) {
            const sp = core.worldToScreen(highlightedFinding.x, highlightedFinding.y);
            const ctx = core.ctx;
            ctx.save();
            ctx.strokeStyle = '#B91C1C';
            ctx.lineWidth = 2.5;
            ctx.beginPath();
            ctx.arc(sp.x, sp.y, 18, 0, Math.PI * 2);
            ctx.stroke();
            ctx.beginPath();
            ctx.arc(sp.x, sp.y, 26, 0, Math.PI * 2);
            ctx.strokeStyle = 'rgba(185, 28, 28, 0.45)';
            ctx.stroke();
            ctx.restore();
        }

        core.drawScaleBarAndCompass(true);
    }

    function drawPathwayEdge(edge, sev) {
        const pts = effectiveEdgePoints(edge);
        if (!pts || pts.length < 2) return;
        const ctx = core.ctx;
        const isSel = selectedEdgeId === edge.id;
        const isLocateTarget = highlightedFinding && highlightedFinding.until > Date.now()
                && Array.isArray(highlightedFinding.edgeIds)
                && highlightedFinding.edgeIds.includes(edge.id);

        const baseColor = edge.colorHex || '#2563EB';
        const strokeColor = sev === 'CRITICAL' ? '#B91C1C' : (sev === 'HIGH' ? '#C2410C' : baseColor);

        ctx.save();
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';

        // 1. Proportional corridor width ribbon
        if (showRibbons) {
            const ribbonPx = Math.max(4, edge.widthM * core.zoom);
            ctx.beginPath();
            pts.forEach((p, idx) => {
                const s = core.worldToScreen(p.x, p.y);
                if (idx === 0) ctx.moveTo(s.x, s.y);
                else ctx.lineTo(s.x, s.y);
            });
            ctx.strokeStyle = hexToRgba(strokeColor, edge.active ? (isSel || isLocateTarget ? 0.34 : 0.18) : 0.08);
            ctx.lineWidth = ribbonPx;
            ctx.stroke();
        }

        // 2. Centerline
        ctx.beginPath();
        pts.forEach((p, idx) => {
            const s = core.worldToScreen(p.x, p.y);
            if (idx === 0) ctx.moveTo(s.x, s.y);
            else ctx.lineTo(s.x, s.y);
        });
        if (!edge.active) {
            ctx.setLineDash([4, 4]);
        } else if (edge.dashed) {
            ctx.setLineDash([8, 5]);
        }
        ctx.strokeStyle = edge.active ? strokeColor : '#94A3B8';
        ctx.lineWidth = isSel || isLocateTarget ? 3.5 : 2.3;
        ctx.stroke();
        ctx.setLineDash([]);

        // 3. Directional arrowheads
        drawEdgeArrows(pts, edge.direction, edge.active ? strokeColor : '#94A3B8');

        // 4. Compact width badge at midpoint
        if (core.zoom >= 2.2) {
            const mid = edge.midpoint || midpointOfPoints(pts);
            const sm = core.worldToScreen(mid.x, mid.y);
            const label = `${Number(edge.widthM).toFixed(1)}m`;
            ctx.font = '600 10px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
            const tw = ctx.measureText(label).width;
            ctx.fillStyle = isSel ? '#EFF6FF' : 'rgba(255, 255, 255, 0.92)';
            ctx.strokeStyle = isSel ? '#2563EB' : '#CBD5E1';
            ctx.lineWidth = 1;
            ctx.fillRect(sm.x - tw / 2 - 4, sm.y - 8, tw + 8, 16);
            ctx.strokeRect(sm.x - tw / 2 - 4, sm.y - 8, tw + 8, 16);
            ctx.fillStyle = '#0F172A';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(label, sm.x, sm.y);
        }

        ctx.restore();
    }

    function drawEdgeArrows(pts, direction, color) {
        const ctx = core.ctx;
        for (let i = 0; i < pts.length - 1; i++) {
            const s1 = core.worldToScreen(pts[i].x, pts[i].y);
            const s2 = core.worldToScreen(pts[i + 1].x, pts[i + 1].y);
            const segLen = Math.hypot(s2.x - s1.x, s2.y - s1.y);
            if (segLen < 24) continue;
            const angle = Math.atan2(s2.y - s1.y, s2.x - s1.x);

            if (direction === 'ONE_WAY') {
                const mx = s1.x + (s2.x - s1.x) * 0.65;
                const my = s1.y + (s2.y - s1.y) * 0.65;
                drawArrowHead(ctx, mx, my, angle, 8, color);
            } else {
                const mx1 = s1.x + (s2.x - s1.x) * 0.72;
                const my1 = s1.y + (s2.y - s1.y) * 0.72;
                const mx2 = s1.x + (s2.x - s1.x) * 0.28;
                const my2 = s1.y + (s2.y - s1.y) * 0.28;
                drawArrowHead(ctx, mx1, my1, angle, 6, color);
                drawArrowHead(ctx, mx2, my2, angle + Math.PI, 6, color);
            }
        }
    }

    function drawArrowHead(ctx, tipX, tipY, angle, size, color) {
        ctx.save();
        ctx.translate(tipX, tipY);
        ctx.rotate(angle);
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.moveTo(0, 0);
        ctx.lineTo(-size, -size * 0.55);
        ctx.lineTo(-size * 0.7, 0);
        ctx.lineTo(-size, size * 0.55);
        ctx.closePath();
        ctx.fill();
        ctx.restore();
    }

    function drawPathwayNode(node, sev) {
        const pos = effectiveNodePos(node);
        const sp = core.worldToScreen(pos.x, pos.y);
        const ctx = core.ctx;
        const isSel = selectedNodeId === node.id;
        const isDraftStart = edgeDraft && edgeDraft.fromNodeId === node.id;
        const isLocateTarget = highlightedFinding && highlightedFinding.until > Date.now()
                && Array.isArray(highlightedFinding.nodeIds)
                && highlightedFinding.nodeIds.includes(node.id);

        const radius = isSel || isDraftStart ? 10 : 8;
        ctx.save();
        ctx.beginPath();
        ctx.arc(sp.x, sp.y, radius, 0, Math.PI * 2);
        ctx.fillStyle = node.fillColor || '#3B82F6';
        ctx.fill();

        if (isSel || isDraftStart || isLocateTarget) {
            ctx.lineWidth = 3;
            ctx.strokeStyle = isLocateTarget ? '#B91C1C' : '#0F172A';
        } else if (sev === 'CRITICAL' || sev === 'HIGH') {
            ctx.lineWidth = 2.5;
            ctx.strokeStyle = severityColor(sev);
        } else {
            ctx.lineWidth = 1.6;
            ctx.strokeStyle = '#FFFFFF';
        }
        ctx.stroke();

        ctx.fillStyle = '#FFFFFF';
        ctx.font = '700 8px system-ui, sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(node.shortCode || 'NOD', sp.x, sp.y + 0.5);

        if (core.zoom >= 3.2) {
            ctx.font = '600 10px system-ui, sans-serif';
            ctx.fillStyle = '#0F172A';
            ctx.fillText(node.label, sp.x, sp.y + radius + 9);
        }
        ctx.restore();
    }

    function drawFindingMarkers(findings) {
        const ctx = core.ctx;
        ctx.save();
        findings.forEach((f, idx) => {
            if (f.locationX == null || f.locationY == null) return;
            const sp = core.worldToScreen(f.locationX, f.locationY);
            const col = severityColor(f.severity);
            ctx.fillStyle = col;
            ctx.strokeStyle = '#FFFFFF';
            ctx.lineWidth = 1.5;
            ctx.beginPath();
            ctx.arc(sp.x + 10, sp.y - 10, 8, 0, Math.PI * 2);
            ctx.fill();
            ctx.stroke();

            ctx.fillStyle = '#FFFFFF';
            ctx.font = '700 10px system-ui, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(String(idx + 1), sp.x + 10, sp.y - 10);
        });
        ctx.restore();
    }

    function bindInspectorTabs() {
        document.querySelectorAll('.inspector-tab').forEach(tab => {
            tab.addEventListener('click', () => activateInspectorTab(tab.dataset.tab));
        });
    }

    function activateInspectorTab(tabName) {
        document.querySelectorAll('.inspector-tab').forEach(t => t.classList.toggle('active', t.dataset.tab === tabName));
        document.querySelectorAll('.inspector-pane').forEach(p => p.classList.toggle('active', p.dataset.pane === tabName));
    }

    function bindControls() {
        document.getElementById('toggleInspectorBtn')?.addEventListener('click', () => {
            document.querySelector('.inspector-panel')?.classList.toggle('collapsed');
            setTimeout(() => core.resize(), 120);
        });

        document.getElementById('legendToggleBtn')?.addEventListener('click', () => {
            const list = document.getElementById('legendList');
            const btn = document.getElementById('legendToggleBtn');
            if (!list || !btn) return;
            const hidden = list.style.display === 'none';
            list.style.display = hidden ? 'flex' : 'none';
            btn.textContent = hidden ? 'Hide' : 'Show';
        });

        document.getElementById('zoomInBtn')?.addEventListener('click', () => core.zoomAt(1.25));
        document.getElementById('zoomOutBtn')?.addEventListener('click', () => core.zoomAt(0.8));
        document.getElementById('zoom100Btn')?.addEventListener('click', () => core.resetZoom100());
        document.getElementById('zoomFitBtn')?.addEventListener('click', () => fitWorkspaceToContent());

        document.getElementById('snapToggleBtn')?.addEventListener('click', (e) => {
            snapEnabled = !snapEnabled;
            e.currentTarget.classList.toggle('active', snapEnabled);
            core.snapMeters = snapEnabled ? (state.venuePlan?.gridSnapMeters ?? 1.0) : 0;
        });

        document.getElementById('ribbonToggleBtn')?.addEventListener('click', (e) => {
            showRibbons = !showRibbons;
            e.currentTarget.classList.toggle('active', showRibbons);
            core.requestRender();
        });

        document.querySelectorAll('.layer-pill[data-layer]').forEach(pill => {
            pill.addEventListener('click', () => {
                const layer = pill.dataset.layer;
                if (visibleLayers.has(layer)) {
                    visibleLayers.delete(layer);
                    pill.classList.remove('active');
                } else {
                    visibleLayers.add(layer);
                    pill.classList.add('active');
                }
                core.requestRender();
            });
        });

        document.getElementById('drawLayerSelect')?.addEventListener('change', (e) => {
            const opt = e.target.selectedOptions?.[0];
            if (opt && opt.dataset.defaultWidth) {
                setInputValue('drawWidthInput', opt.dataset.defaultWidth);
            }
        });

        document.querySelector('.tool-rail-btn[data-tool="SELECT"]')?.addEventListener('click', () => setTool('SELECT'));
        document.querySelector('.tool-rail-btn[data-tool="ADD_NODE"]')?.addEventListener('click', () => {
            if (canEdit) setTool('ADD_NODE');
        });
        document.querySelector('.tool-rail-btn[data-tool="DRAW_EDGE"]')?.addEventListener('click', () => {
            if (canEdit) setTool('DRAW_EDGE');
        });

        document.getElementById('addNodeToolBtn')?.addEventListener('click', () => {
            if (!canEdit) return;
            setTool(activeTool === 'ADD_NODE' ? 'SELECT' : 'ADD_NODE');
        });
        document.getElementById('drawEdgeToolBtn')?.addEventListener('click', () => {
            if (!canEdit) return;
            setTool(activeTool === 'DRAW_EDGE' ? 'SELECT' : 'DRAW_EDGE');
        });

        document.getElementById('autoSeedBtn')?.addEventListener('click', () => autoSeedNodes());
        document.getElementById('autoSeedRailBtn')?.addEventListener('click', () => autoSeedNodes());

        document.getElementById('applyNodeBtn')?.addEventListener('click', () => applySelectedNode());
        document.getElementById('deleteNodeBtn')?.addEventListener('click', () => deleteSelectedNode());
        document.getElementById('applyEdgeBtn')?.addEventListener('click', () => applySelectedEdge(false));
        document.getElementById('reverseEdgeBtn')?.addEventListener('click', () => applySelectedEdge(true));
        document.getElementById('deleteEdgeBtn')?.addEventListener('click', () => deleteSelectedEdge());

        document.getElementById('runScreenBtn')?.addEventListener('click', () => runScreening());
        document.getElementById('saveNetworkBtn')?.addEventListener('click', () => saveWorkspace());

        document.getElementById('undoBtn')?.addEventListener('click', () => performUndo());
        document.getElementById('redoBtn')?.addEventListener('click', () => performRedo());

        window.addEventListener('keydown', (e) => {
            if (isTypingElement(document.activeElement)) return;
            if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
                e.preventDefault();
                if (e.shiftKey) performRedo();
                else performUndo();
            } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') {
                e.preventDefault();
                performRedo();
            } else if (e.key === 'Escape') {
                edgeDraft = null;
                setTool('SELECT');
            }
        });
    }

    function bindCanvasInteractions() {
        canvasEl.addEventListener('contextmenu', (e) => {
            if (activeTool === 'DRAW_EDGE' && edgeDraft) {
                e.preventDefault();
                edgeDraft = null;
                updateHint();
                core.requestRender();
            }
        });

        canvasEl.addEventListener('wheel', (e) => {
            e.preventDefault();
            const local = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const factor = e.deltaY < 0 ? 1.12 : 0.89;
            core.zoomAt(factor, local.x, local.y);
        }, { passive: false });

        canvasEl.addEventListener('mousedown', (e) => {
            const local = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const world = core.screenToWorld(local.x, local.y);

            if (e.button === 1 || (e.button === 0 && e.shiftKey)) {
                dragState = { type: 'pan', lastX: e.clientX, lastY: e.clientY };
                return;
            }
            if (e.button !== 0) return;

            if (activeTool === 'ADD_NODE' && canEdit) {
                const pt = snapEnabled ? core.snapWorld(world.x, world.y) : world;
                createNodeAt(pt.x, pt.y);
                return;
            }

            if (activeTool === 'DRAW_EDGE' && canEdit) {
                const hitNode = findNodeAtWorld(world);
                if (!edgeDraft) {
                    if (hitNode) {
                        edgeDraft = { fromNodeId: hitNode.id, waypoints: [] };
                        updateHint();
                        core.requestRender();
                    } else {
                        setErrorToast('Click a pathway node first to start drawing an edge.');
                    }
                } else {
                    if (hitNode && hitNode.id !== edgeDraft.fromNodeId) {
                        const fromId = edgeDraft.fromNodeId;
                        const wps = [...edgeDraft.waypoints];
                        edgeDraft = null;
                        createEdgeBetween(fromId, hitNode.id, wps);
                    } else if (!hitNode) {
                        const wp = snapEnabled ? core.snapWorld(world.x, world.y) : world;
                        edgeDraft.waypoints.push({ x: round2(wp.x), y: round2(wp.y) });
                        core.requestRender();
                    }
                }
                return;
            }

            // SELECT tool
            const hitNode = findNodeAtWorld(world);
            if (hitNode) {
                selectedNodeId = hitNode.id;
                selectedEdgeId = null;
                updateSelectedInspectors();
                updateSummaryPanel(state.summary || {}, state.nodes || [], state.edges || []);
                activateInspectorTab('properties');
                if (canEdit) {
                    dragState = {
                        type: 'move_node',
                        node: hitNode,
                        origX: hitNode.x,
                        origY: hitNode.y,
                        moved: false
                    };
                }
                core.requestRender();
                return;
            }

            const hitEdge = findEdgeAtWorld(world);
            if (hitEdge) {
                selectedEdgeId = hitEdge.id;
                selectedNodeId = null;
                updateSelectedInspectors();
                updateSummaryPanel(state.summary || {}, state.nodes || [], state.edges || []);
                activateInspectorTab('properties');
                core.requestRender();
                return;
            }

            selectedNodeId = null;
            selectedEdgeId = null;
            updateSelectedInspectors();
            updateSummaryPanel(state.summary || {}, state.nodes || [], state.edges || []);
            dragState = { type: 'pan', lastX: e.clientX, lastY: e.clientY };
            core.requestRender();
        });

        window.addEventListener('mousemove', (e) => {
            const local = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const world = core.screenToWorld(local.x, local.y);
            hoverWorld = world;
            const snapped = snapEnabled ? core.snapWorld(world.x, world.y) : world;
            setText('cursorCoordsReadout', `X: ${snapped.x.toFixed(1)} m, Y: ${snapped.y.toFixed(1)} m`);

            if (dragState) {
                if (dragState.type === 'pan') {
                    core.panBy(e.clientX - dragState.lastX, e.clientY - dragState.lastY);
                    dragState.lastX = e.clientX;
                    dragState.lastY = e.clientY;
                    return;
                }
                if (dragState.type === 'move_node') {
                    const target = snapEnabled ? core.snapWorld(world.x, world.y) : world;
                    previewNodePos = {
                        id: dragState.node.id,
                        x: round2(target.x),
                        y: round2(target.y)
                    };
                    if (Math.hypot(previewNodePos.x - dragState.origX, previewNodePos.y - dragState.origY) > 0.05) {
                        dragState.moved = true;
                    }
                    core.requestRender();
                    return;
                }
            }

            if (activeTool === 'DRAW_EDGE' && edgeDraft) {
                core.requestRender();
            }
        });

        window.addEventListener('mouseup', async () => {
            if (!dragState) return;
            const currentDrag = dragState;
            dragState = null;

            if (currentDrag.type === 'move_node') {
                const pos = previewNodePos;
                previewNodePos = null;
                if (currentDrag.moved && pos) {
                    const beforeSnap = snapshotCurrentNetwork();
                    try {
                        const updated = await apiPut(`/api/events/${eventId}/network/nodes/${currentDrag.node.id}`, {
                            nodeType: currentDrag.node.nodeType,
                            label: currentDrag.node.label,
                            x: pos.x,
                            y: pos.y,
                            linkedZoneId: currentDrag.node.linkedZoneId,
                            linkedInfraId: currentDrag.node.linkedInfraId,
                            updateLinks: true,
                            notes: currentDrag.node.notes
                        });
                        pushUndoSnapshot(beforeSnap);
                        syncFromState(updated);
                        setToast('Node moved & connected edge lengths recalculated.');
                    } catch (err) {
                        core.requestRender();
                        setErrorToast(err.message);
                    }
                } else {
                    core.requestRender();
                }
            }
        });
    }

    async function autoSeedNodes() {
        if (!canEdit) return;
        const beforeSnap = snapshotCurrentNetwork();
        try {
            const updated = await apiPost(`/api/events/${eventId}/network/seed`, {});
            pushUndoSnapshot(beforeSnap);
            syncFromState(updated);
            setToast('Seeded nodes from existing zones & anchor infrastructure.');
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    async function createNodeAt(x, y) {
        const beforeSnap = snapshotCurrentNetwork();
        const zoneVal = document.getElementById('addNodeZoneSelect')?.value;
        const infraVal = document.getElementById('addNodeInfraSelect')?.value;
        try {
            const updated = await apiPost(`/api/events/${eventId}/network/nodes`, {
                nodeType: document.getElementById('addNodeTypeSelect')?.value || 'INTERSECTION',
                label: document.getElementById('addNodeLabelInput')?.value?.trim() || null,
                x: round2(x),
                y: round2(y),
                linkedZoneId: zoneVal ? Number(zoneVal) : null,
                linkedInfraId: infraVal ? Number(infraVal) : null,
                notes: null
            });
            pushUndoSnapshot(beforeSnap);
            syncFromState(updated);
            setToast('Pathway node added.');
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    async function createEdgeBetween(fromNodeId, toNodeId, waypoints) {
        const beforeSnap = snapshotCurrentNetwork();
        const layer = document.getElementById('drawLayerSelect')?.value || 'NORMAL_ENTRY';
        const widthM = parseFloat(document.getElementById('drawWidthInput')?.value) || 3.0;
        const direction = document.getElementById('drawDirectionSelect')?.value || 'BIDIRECTIONAL';
        try {
            const updated = await apiPost(`/api/events/${eventId}/network/edges`, {
                fromNodeId,
                toNodeId,
                networkLayer: layer,
                widthM,
                direction,
                waypoints,
                active: true
            });
            pushUndoSnapshot(beforeSnap);
            visibleLayers.add(layer);
            document.querySelector(`.layer-pill[data-layer="${layer}"]`)?.classList.add('active');
            syncFromState(updated);
            updateHint();
            setToast('Pathway edge connected & bottleneck screening updated.');
        } catch (err) {
            setErrorToast(err.message);
            updateHint();
            core.requestRender();
        }
    }

    async function applySelectedNode() {
        if (!canEdit || selectedNodeId == null) return;
        const orig = (state.nodes || []).find(n => n.id === selectedNodeId);
        if (!orig) return;
        const beforeSnap = snapshotCurrentNetwork();
        const zoneVal = document.getElementById('selNodeZoneSelect')?.value;
        const infraVal = document.getElementById('selNodeInfraSelect')?.value;
        try {
            const updated = await apiPut(`/api/events/${eventId}/network/nodes/${orig.id}`, {
                nodeType: document.getElementById('selNodeTypeSelect')?.value || orig.nodeType,
                label: document.getElementById('selNodeLabelInput')?.value?.trim() || orig.label,
                x: parseFloat(document.getElementById('selNodeXInput')?.value),
                y: parseFloat(document.getElementById('selNodeYInput')?.value),
                linkedZoneId: zoneVal ? Number(zoneVal) : null,
                linkedInfraId: infraVal ? Number(infraVal) : null,
                updateLinks: true,
                notes: document.getElementById('selNodeNotesInput')?.value?.trim() || ''
            });
            pushUndoSnapshot(beforeSnap);
            syncFromState(updated);
            setToast('Node updated.');
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    async function deleteSelectedNode() {
        if (!canEdit || selectedNodeId == null) return;
        const beforeSnap = snapshotCurrentNetwork();
        try {
            const updated = await apiDelete(`/api/events/${eventId}/network/nodes/${selectedNodeId}`);
            pushUndoSnapshot(beforeSnap);
            selectedNodeId = null;
            syncFromState(updated);
            setToast('Node and connected edges removed.');
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    async function applySelectedEdge(reverseDirection) {
        if (!canEdit || selectedEdgeId == null) return;
        const orig = (state.edges || []).find(e => e.id === selectedEdgeId);
        if (!orig) return;
        const beforeSnap = snapshotCurrentNetwork();
        try {
            const updated = await apiPut(`/api/events/${eventId}/network/edges/${orig.id}`, {
                label: document.getElementById('selEdgeLabelInput')?.value?.trim() || '',
                networkLayer: document.getElementById('selEdgeLayerSelect')?.value || orig.networkLayer,
                widthM: parseFloat(document.getElementById('selEdgeWidthInput')?.value),
                direction: document.getElementById('selEdgeDirectionSelect')?.value || orig.direction,
                active: document.getElementById('selEdgeActiveCheckbox')?.checked ?? orig.active,
                reverseDirection,
                notes: document.getElementById('selEdgeNotesInput')?.value?.trim() || ''
            });
            pushUndoSnapshot(beforeSnap);
            syncFromState(updated);
            setToast(reverseDirection ? 'Edge direction reversed.' : 'Pathway edge updated.');
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    async function deleteSelectedEdge() {
        if (!canEdit || selectedEdgeId == null) return;
        const beforeSnap = snapshotCurrentNetwork();
        try {
            const updated = await apiDelete(`/api/events/${eventId}/network/edges/${selectedEdgeId}`);
            pushUndoSnapshot(beforeSnap);
            selectedEdgeId = null;
            syncFromState(updated);
            setToast('Pathway edge removed.');
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    async function performUndo() {
        if (!canEdit || undoStack.length === 0) return;
        const currentSnap = snapshotCurrentNetwork();
        const prevSnap = undoStack.pop();
        try {
            const updated = await apiPut(`/api/events/${eventId}/network/layout`, prevSnap);
            redoStack.push(currentSnap);
            selectedNodeId = null;
            selectedEdgeId = null;
            syncFromState(updated);
            setToast('Reverted last pathway edit.');
        } catch (err) {
            undoStack.push(prevSnap);
            setErrorToast(err.message);
        }
    }

    async function performRedo() {
        if (!canEdit || redoStack.length === 0) return;
        const currentSnap = snapshotCurrentNetwork();
        const nextSnap = redoStack.pop();
        try {
            const updated = await apiPut(`/api/events/${eventId}/network/layout`, nextSnap);
            undoStack.push(currentSnap);
            selectedNodeId = null;
            selectedEdgeId = null;
            syncFromState(updated);
            setToast('Reapplied pathway edit.');
        } catch (err) {
            redoStack.push(nextSnap);
            setErrorToast(err.message);
        }
    }

    async function runScreening() {
        try {
            const updated = await apiPost(`/api/events/${eventId}/network/screen`, {});
            syncFromState(updated);
            const count = (updated.activeFindings || []).length;
            setToast(`Bottleneck screening complete: ${count} active ${count === 1 ? 'finding' : 'findings'}.`);
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    async function saveWorkspace() {
        if (selectedNodeId != null) {
            await applySelectedNode();
        } else if (selectedEdgeId != null) {
            await applySelectedEdge(false);
        } else {
            await runScreening();
        }
    }

    function setTool(tool) {
        activeTool = tool;
        edgeDraft = null;
        document.querySelectorAll('.tool-rail-btn[data-tool]').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tool === tool);
        });
        const addBtn = document.getElementById('addNodeToolBtn');
        const edgeBtn = document.getElementById('drawEdgeToolBtn');
        if (addBtn) addBtn.textContent = tool === 'ADD_NODE' ? 'Cancel Add Node' : 'Click Canvas to Add Node';
        if (edgeBtn) edgeBtn.textContent = tool === 'DRAW_EDGE' ? 'Cancel Edge Draw' : 'Connect Nodes on Canvas';
        updateHint();
        updateStatusBar();
        core.requestRender();
    }

    function updateHint() {
        if (!hintBar || !hintText) return;
        if (activeTool === 'ADD_NODE') {
            hintText.textContent = 'Click anywhere on the canvas to place a pathway node (Esc to cancel).';
            hintBar.style.display = 'flex';
        } else if (activeTool === 'DRAW_EDGE') {
            hintText.textContent = edgeDraft
                ? 'Click bend points or click the destination node to connect (Right-click / Esc to cancel).'
                : 'Click a start node to begin drawing a pathway edge (Esc to cancel).';
            hintBar.style.display = 'flex';
        } else {
            hintBar.style.display = 'none';
        }
    }

    function updateSelectedInspectors() {
        const nodePanel = document.getElementById('selectedNodeSection');
        const edgePanel = document.getElementById('selectedEdgeSection');
        const node = selectedNodeId != null ? (state.nodes || []).find(n => n.id === selectedNodeId) : null;
        const edge = selectedEdgeId != null ? (state.edges || []).find(e => e.id === selectedEdgeId) : null;

        if (nodePanel) {
            if (!node) {
                nodePanel.style.display = 'none';
            } else {
                nodePanel.style.display = 'flex';
                setText('selNodeMeta', `${node.nodeTypeName} · (${Number(node.x).toFixed(1)} m, ${Number(node.y).toFixed(1)} m)`);
                setInputValue('selNodeTypeSelect', node.nodeType);
                setInputValue('selNodeLabelInput', node.label);
                setInputValue('selNodeXInput', node.x);
                setInputValue('selNodeYInput', node.y);
                setInputValue('selNodeZoneSelect', node.linkedZoneId != null ? String(node.linkedZoneId) : '');
                setInputValue('selNodeInfraSelect', node.linkedInfraId != null ? String(node.linkedInfraId) : '');
                setInputValue('selNodeNotesInput', node.notes || '');
            }
        }

        if (edgePanel) {
            if (!edge) {
                edgePanel.style.display = 'none';
            } else {
                edgePanel.style.display = 'flex';
                setText('selEdgeMeta', `${edge.fromNodeLabel} -> ${edge.toNodeLabel} · ${Number(edge.lengthM).toFixed(2)} m`);
                setInputValue('selEdgeLabelInput', edge.label || '');
                setInputValue('selEdgeLayerSelect', edge.networkLayer);
                setInputValue('selEdgeWidthInput', edge.widthM);
                setInputValue('selEdgeDirectionSelect', edge.direction);
                const actCb = document.getElementById('selEdgeActiveCheckbox');
                if (actCb) actCb.checked = Boolean(edge.active);
                setInputValue('selEdgeNotesInput', edge.notes || '');
            }
        }
    }

    function updateSummaryPanel(summary, nodes, edges) {
        setText('sumNodesEdges', `${summary.totalNodes || 0} / ${summary.activeEdges || 0}`);
        setText('sumTotalLength', `${Number(summary.totalLengthM || 0).toFixed(1)} m`);
        setText('sumComponents', String(summary.connectedComponents || 0));
        setText('sumLayerCounts', `${summary.entryLayerEdges || 0} / ${summary.circulationLayerEdges || 0} / ${summary.exitLayerEdges || 0} / ${summary.emergencyLayerEdges || 0}`);
        setText('sumGateBalance', `${Number(summary.connectedEntryCapacityPerMin || 0).toFixed(0)} vs ${Number(summary.estimatedArrivalDemandPerMin || 0).toFixed(0)} p/m`);
        setText('sumFindingsCounts', `${summary.criticalCount || 0} / ${summary.highCount || 0} / ${summary.lowCount || 0}`);
        setText('edgesCountLabel', String(edges.length));
        setText('nodesCountLabel', String(nodes.length));

        const edgesList = document.getElementById('edgesList');
        if (edgesList) {
            if (!edges || edges.length === 0) {
                edgesList.innerHTML = '<div class="text-muted" style="font-size:12px;">No pathway edges drawn yet.</div>';
            } else {
                edgesList.innerHTML = edges.map(e => `
                    <div class="network-item-row ${selectedEdgeId === e.id ? 'selected' : ''}" data-edge-id="${e.id}">
                        <div style="display:flex;align-items:center;gap:8px;min-width:0;">
                            <span class="layer-dot" style="background:${escapeHtml(e.colorHex)};"></span>
                            <div style="min-width:0;">
                                <div style="font-size:12px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">
                                    ${escapeHtml(e.label || (e.fromNodeLabel + ' -> ' + e.toNodeLabel))}
                                </div>
                                <div class="text-muted mono" style="font-size:11px;">
                                    ${escapeHtml(e.networkLayerShort)} · ${Number(e.widthM).toFixed(1)} m × ${Number(e.lengthM).toFixed(1)} m · ${e.direction === 'ONE_WAY' ? '1-Way' : '2-Way'}${!e.active ? ' · OFF' : ''}
                                </div>
                            </div>
                        </div>
                    </div>
                `).join('');
                edgesList.querySelectorAll('.network-item-row[data-edge-id]').forEach(row => {
                    row.addEventListener('click', () => {
                        const id = Number(row.dataset.edgeId);
                        const e = (state.edges || []).find(item => item.id === id);
                        selectedEdgeId = id;
                        selectedNodeId = null;
                        updateSelectedInspectors();
                        updateSummaryPanel(state.summary || {}, state.nodes || [], state.edges || []);
                        if (e && e.midpoint) core.panTo(e.midpoint.x, e.midpoint.y);
                        else core.requestRender();
                    });
                });
            }
        }

        const nodesList = document.getElementById('nodesList');
        if (nodesList) {
            if (!nodes || nodes.length === 0) {
                nodesList.innerHTML = '<div class="text-muted" style="font-size:12px;">No nodes placed yet. Click Auto-Seed Nodes to anchor zones &amp; gates.</div>';
            } else {
                nodesList.innerHTML = nodes.map(n => `
                    <div class="network-item-row ${selectedNodeId === n.id ? 'selected' : ''}" data-node-id="${n.id}">
                        <div style="display:flex;align-items:center;gap:8px;min-width:0;">
                            <span class="layer-dot" style="background:${escapeHtml(n.fillColor)};"></span>
                            <div style="min-width:0;">
                                <div style="font-size:12px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">
                                    ${escapeHtml(n.label)}
                                </div>
                                <div class="text-muted mono" style="font-size:11px;">
                                    ${escapeHtml(n.nodeTypeName)} · (${Number(n.x).toFixed(1)}, ${Number(n.y).toFixed(1)})
                                </div>
                            </div>
                        </div>
                    </div>
                `).join('');
                nodesList.querySelectorAll('.network-item-row[data-node-id]').forEach(row => {
                    row.addEventListener('click', () => {
                        const id = Number(row.dataset.nodeId);
                        const n = (state.nodes || []).find(item => item.id === id);
                        selectedNodeId = id;
                        selectedEdgeId = null;
                        updateSelectedInspectors();
                        updateSummaryPanel(state.summary || {}, state.nodes || [], state.edges || []);
                        if (n) core.panTo(n.x, n.y);
                        else core.requestRender();
                    });
                });
            }
        }
    }

    function updateFindingsPanel(activeFindings, historyFindings) {
        setText('findingsCountBadge', String(activeFindings.length));
        const listEl = document.getElementById('activeFindingsList');
        if (listEl) {
            if (!activeFindings || activeFindings.length === 0) {
                listEl.innerHTML = '<div class="banner banner-info">No automated bottleneck screening warnings detected across the current movement graph. Professional safety review is still required.</div>';
            } else {
                listEl.innerHTML = activeFindings.map((f, idx) => renderFindingCard(f, idx, true)).join('');
                bindFindingCardEvents(listEl, activeFindings);
            }
        }

        const histPanel = document.getElementById('historyFindingsPanel');
        const histList = document.getElementById('historyFindingsList');
        setText('historyFindingsCount', String((historyFindings || []).length));
        if (histPanel && histList) {
            if (historyFindings && historyFindings.length > 0) {
                histPanel.style.display = 'block';
                histList.innerHTML = historyFindings.map((f, idx) => renderFindingCard(f, idx, false)).join('');
                bindFindingCardEvents(histList, historyFindings);
            } else {
                histPanel.style.display = 'none';
                histList.innerHTML = '';
            }
        }
    }

    function renderFindingCard(f, idx, isActive) {
        const sevClass = severityBadgeClass(f.severity);
        const statusClass = reviewBadgeClass(f.reviewStatus, isActive);
        const hasLoc = f.locationX != null && f.locationY != null;
        const reviewControl = isActive && canReview ? `
            <div class="finding-review-form">
                <div class="form-grid-2">
                    <select class="form-control finding-status-select" data-finding-id="${f.id}">
                        <option value="OPEN" ${f.reviewStatus === 'OPEN' ? 'selected' : ''}>Open</option>
                        <option value="ACKNOWLEDGED" ${f.reviewStatus === 'ACKNOWLEDGED' ? 'selected' : ''}>Acknowledged</option>
                        <option value="MITIGATED" ${f.reviewStatus === 'MITIGATED' ? 'selected' : ''}>Mitigated</option>
                        <option value="REVIEWER_NOTED" ${f.reviewStatus === 'REVIEWER_NOTED' ? 'selected' : ''}>Reviewer Noted</option>
                    </select>
                    <button type="button" class="btn btn-secondary btn-sm save-finding-review-btn" data-finding-id="${f.id}">
                        Save Note
                    </button>
                </div>
                <input type="text" class="form-control finding-note-input" data-finding-id="${f.id}"
                       maxlength="1000" placeholder="Safety reviewer annotation..."
                       value="${escapeHtml(f.reviewerNote || '')}">
            </div>` : (f.reviewerNote ? `
            <div class="text-secondary" style="font-size:11px;border-top:1px solid var(--border);padding-top:4px;">
                <strong>Reviewer Note:</strong> ${escapeHtml(f.reviewerNote)}
                ${f.reviewedByName ? ` — ${escapeHtml(f.reviewedByName)}` : ''}
            </div>` : '');

        return `<div class="finding-card">
            <div style="display:flex;align-items:center;justify-content:space-between;gap:6px;flex-wrap:wrap;">
                <div style="display:flex;align-items:center;gap:4px;">
                    <span class="badge ${sevClass}">${escapeHtml(f.severity)}</span>
                    <span class="badge ${statusClass}">${escapeHtml(isActive ? f.reviewStatusName : 'Resolved')}</span>
                </div>
                <span class="mono text-muted" style="font-size:11px;">${escapeHtml(f.measuredValue || '')}</span>
            </div>
            <div style="font-size:12px;font-weight:600;">${escapeHtml(f.title)}</div>
            <div class="text-secondary" style="font-size:12px;">${escapeHtml(f.description)}</div>
            ${f.thresholdNote ? `<div class="text-muted mono" style="font-size:11px;">${escapeHtml(f.thresholdNote)}</div>` : ''}
            ${hasLoc ? `<div><button type="button" class="btn btn-secondary btn-sm locate-finding-btn" data-locate-idx="${idx}">Locate on Canvas</button></div>` : ''}
            ${reviewControl}
        </div>`;
    }

    function bindFindingCardEvents(container, findings) {
        container.querySelectorAll('.locate-finding-btn[data-locate-idx]').forEach(btn => {
            btn.addEventListener('click', () => {
                const f = findings[Number(btn.dataset.locateIdx)];
                if (!f || f.locationX == null || f.locationY == null) return;
                if (Array.isArray(f.involvedEdgeIds) && f.involvedEdgeIds.length > 0) {
                    const edge = (state.edges || []).find(e => e.id === f.involvedEdgeIds[0]);
                    if (edge) {
                        visibleLayers.add(edge.networkLayer);
                        document.querySelector(`.layer-pill[data-layer="${edge.networkLayer}"]`)?.classList.add('active');
                    }
                    selectedEdgeId = f.involvedEdgeIds[0];
                    selectedNodeId = null;
                } else if (Array.isArray(f.involvedNodeIds) && f.involvedNodeIds.length > 0) {
                    selectedNodeId = f.involvedNodeIds[0];
                    selectedEdgeId = null;
                }
                updateSelectedInspectors();
                highlightedFinding = {
                    x: f.locationX,
                    y: f.locationY,
                    nodeIds: f.involvedNodeIds || [],
                    edgeIds: f.involvedEdgeIds || [],
                    until: Date.now() + 2600
                };
                core.panTo(f.locationX, f.locationY, 8.5);
                setTimeout(() => core.requestRender(), 2650);
            });
        });

        container.querySelectorAll('.save-finding-review-btn[data-finding-id]').forEach(btn => {
            btn.addEventListener('click', async () => {
                if (!canReview) return;
                const fid = Number(btn.dataset.findingId);
                const statusEl = container.querySelector(`.finding-status-select[data-finding-id="${fid}"]`);
                const noteEl = container.querySelector(`.finding-note-input[data-finding-id="${fid}"]`);
                try {
                    const updated = await apiPost(`/api/events/${eventId}/network/findings/${fid}/review`, {
                        reviewStatus: statusEl?.value || 'REVIEWER_NOTED',
                        reviewerNote: noteEl?.value?.trim() || null
                    });
                    syncFromState(updated);
                    activateInspectorTab('findings');
                    setToast('Reviewer note saved.');
                } catch (err) {
                    setErrorToast(err.message);
                }
            });
        });
    }

    function updateStatusPill() {
        const nCount = state.summary?.totalNodes || 0;
        const eCount = state.summary?.activeEdges || 0;
        const fCount = (state.activeFindings || []).length;
        setText('networkStatusPill', `${nCount} ${nCount === 1 ? 'node' : 'nodes'} · ${eCount} ${eCount === 1 ? 'edge' : 'edges'} · ${fCount} ${fCount === 1 ? 'finding' : 'findings'}`);
    }

    function updateUndoRedoButtons() {
        const undoBtn = document.getElementById('undoBtn');
        const redoBtn = document.getElementById('redoBtn');
        if (undoBtn) undoBtn.disabled = !canEdit || undoStack.length === 0;
        if (redoBtn) redoBtn.disabled = !canEdit || redoStack.length === 0;
    }

    function updateZoomLabel() {
        const pct = Math.round((core.zoom / 6.5) * 100);
        setText('zoom100Btn', `${pct}%`);
    }

    function updateStatusBar() {
        setText('statusLayerText', `Pathways · ${activeTool}`);
    }

    function effectiveNodePos(node) {
        if (previewNodePos && previewNodePos.id === node.id) {
            return { x: previewNodePos.x, y: previewNodePos.y };
        }
        return { x: node.x, y: node.y };
    }

    function effectiveEdgePoints(edge) {
        const pts = (edge.points || []).map(p => ({ x: p.x, y: p.y }));
        if (previewNodePos && pts.length >= 2) {
            if (edge.fromNodeId === previewNodePos.id) {
                pts[0] = { x: previewNodePos.x, y: previewNodePos.y };
            }
            if (edge.toNodeId === previewNodePos.id) {
                pts[pts.length - 1] = { x: previewNodePos.x, y: previewNodePos.y };
            }
        }
        return pts;
    }

    function findNodeAtWorld(pt) {
        const hitRadiusWorld = 12 / Math.max(core.zoom, 1);
        const nodes = [...(state.nodes || [])].reverse();
        for (const n of nodes) {
            const pos = effectiveNodePos(n);
            if (Math.hypot(pt.x - pos.x, pt.y - pos.y) <= hitRadiusWorld) {
                return n;
            }
        }
        return null;
    }

    function findEdgeAtWorld(pt) {
        const baseHitWorld = 10 / Math.max(core.zoom, 1);
        const edges = [...(state.edges || [])].reverse();
        for (const e of edges) {
            if (!visibleLayers.has(e.networkLayer)) continue;
            const pts = effectiveEdgePoints(e);
            const tol = Math.max(baseHitWorld, e.widthM / 2);
            for (let i = 0; i < pts.length - 1; i++) {
                if (pointToSegmentDist(pt, pts[i], pts[i + 1]) <= tol) {
                    return e;
                }
            }
        }
        return null;
    }

    function buildFindingSeverityMap(findings, idField) {
        const rank = { CRITICAL: 1, HIGH: 2, LOW: 3, INFO: 4 };
        const map = new Map();
        for (const f of findings) {
            for (const id of (f[idField] || [])) {
                const prev = map.get(id);
                if (!prev || (rank[f.severity] || 9) < (rank[prev] || 9)) {
                    map.set(id, f.severity);
                }
            }
        }
        return map;
    }

    function setToast(msg) {
        const el = document.getElementById('workspaceMessage');
        if (!el) return;
        el.className = 'text-secondary';
        el.style.color = '';
        el.textContent = msg;
    }

    function setErrorToast(msg) {
        const el = document.getElementById('workspaceMessage');
        if (!el) return;
        el.className = '';
        el.style.color = '#B91C1C';
        el.textContent = msg;
    }
}

function pointToSegmentDist(p, a, b) {
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const lenSq = dx * dx + dy * dy;
    if (lenSq < 1e-9) return Math.hypot(p.x - a.x, p.y - a.y);
    const t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq));
    return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
}

function midpointOfPoints(pts) {
    const a = pts[0];
    const b = pts[pts.length - 1];
    return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
}

function layerColor(layer) {
    switch (layer) {
        case 'NORMAL_ENTRY': return '#10B981';
        case 'NORMAL_EXIT': return '#0F766E';
        case 'EMERGENCY_ACCESS': return '#DC2626';
        default: return '#2563EB';
    }
}

function severityColor(sev) {
    switch (sev) {
        case 'CRITICAL': return '#B91C1C';
        case 'HIGH': return '#C2410C';
        case 'LOW': return '#B45309';
        default: return '#0284C7';
    }
}

function severityBadgeClass(sev) {
    switch (sev) {
        case 'CRITICAL': return 'badge-critical';
        case 'HIGH': return 'badge-high';
        case 'LOW': return 'badge-low';
        default: return 'badge-info';
    }
}

function reviewBadgeClass(status, isActive) {
    if (!isActive) return 'badge-neutral';
    switch (status) {
        case 'MITIGATED':
        case 'REVIEWER_NOTED':
            return 'badge-verified';
        case 'ACKNOWLEDGED':
            return 'badge-info';
        default:
            return 'badge-neutral';
    }
}

function round2(v) {
    return Math.round(v * 100) / 100;
}

function setText(id, text) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
}

function setInputValue(id, value) {
    const el = document.getElementById(id);
    if (el && value !== undefined && value !== null) {
        el.value = value;
    }
}

function hexToRgba(hex, alpha) {
    const clean = String(hex || '#3B82F6').replace('#', '');
    if (clean.length !== 6) return `rgba(59, 130, 246, ${alpha})`;
    const r = parseInt(clean.substring(0, 2), 16);
    const g = parseInt(clean.substring(2, 4), 16);
    const b = parseInt(clean.substring(4, 6), 16);
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

function escapeHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function isTypingElement(el) {
    if (!el) return false;
    const tag = el.tagName ? el.tagName.toLowerCase() : '';
    return tag === 'input' || tag === 'textarea' || tag === 'select' || el.isContentEditable;
}
