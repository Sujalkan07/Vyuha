import { apiDelete, apiPost } from './api-client.js';
import { VyuhaCanvasCore } from './canvas-core.js';

const rootEl = document.getElementById('emergencyWorkspaceRoot');
if (rootEl) {
    initEmergencyWorkspace(rootEl);
}

function initEmergencyWorkspace(root) {
    const eventId = Number(root.dataset.eventId);
    const canEdit = root.dataset.canEdit === 'true';
    let state = JSON.parse(root.dataset.initialState || '{}');
    let outageResult = state.baselineOutage || emptyOutageResult();

    let activeTool = 'OUTAGE_TEST'; // OUTAGE_TEST | PLACE_EXIT | DRAW_ASSEMBLY | DRAW_AMBULANCE | DRAW_FIRE
    let draftPoints = [];
    let hoverWorld = null;
    let dragPan = null;
    let blueprintImage = null;
    let loadedBlueprintUrl = null;
    let highlightPin = null; // { x, y, until }

    const disabledInfraIds = new Set();
    const disabledNodeIds = new Set();
    const disabledEdgeIds = new Set();
    const disabledAssetIds = new Set();

    const canvasEl = document.getElementById('emergencyCanvas');
    const hintEl = document.getElementById('emCanvasHint');
    const coordsEl = document.getElementById('emCursorCoords');
    const modeNoteEl = document.getElementById('emActiveModeNote');
    const finishBtn = document.getElementById('finishDrawBtn');
    const cancelBtn = document.getElementById('cancelDrawBtn');
    const emergencyOnlyCheckbox = document.getElementById('emergencyExitsOnlyToggle');

    const dialogEl = document.getElementById('emergencyAssetDialog');
    const dialogForm = document.getElementById('emergencyAssetDialogForm');
    const dialogTitle = document.getElementById('emDialogTitle');
    const dialogLabel = document.getElementById('emDialogLabel');
    const dialogWidthGroup = document.getElementById('emDialogWidthGroup');
    const dialogWidth = document.getElementById('emDialogWidth');
    const dialogCapacity = document.getElementById('emDialogCapacity');
    const dialogNotes = document.getElementById('emDialogNotes');
    const dialogError = document.getElementById('emDialogError');
    let pendingAssetPayload = null;

    const core = new VyuhaCanvasCore(canvasEl, {
        snapMeters: state.venuePlan?.gridSnapMeters ?? 1.0,
        showGrid: true,
        onRender: renderScene,
        onViewChange: updateZoomLabel
    });

    bindControls();
    bindCanvasEvents();
    loadBlueprintIfNeeded(state.venuePlan || {});
    fitWorkspace();
    refreshOutageAndDom(false);

    function emptyOutageResult() {
        return {
            emergencyExitsOnly: false,
            disabledExitInfraIds: [],
            disabledNodeIds: [],
            disabledEdgeIds: [],
            disabledAssetIds: [],
            totalEvaluatedZones: 0,
            unaffectedZoneCount: 0,
            reroutedZoneCount: 0,
            newlyIsolatedZoneCount: 0,
            alreadyIsolatedZoneCount: 0,
            baselineTotalEgressWidthM: 0,
            remainingEgressWidthM: 0,
            egressWidthLossM: 0,
            egressWidthLossPct: 0,
            remainingEgressCapacityPct: 100,
            baselineThroughputPerMin: 0,
            remainingThroughputPerMin: 0,
            throughputLossPct: 0,
            remainingThroughputPct: 100,
            activeExitCount: 0,
            operationalExitCount: 0,
            cutOffExitCount: 0,
            exitStatuses: [],
            zoneImpacts: []
        };
    }

    function bindControls() {
        document.querySelectorAll('[data-em-tool]').forEach(btn => {
            btn.addEventListener('click', () => {
                setTool(btn.dataset.emTool);
            });
        });

        finishBtn?.addEventListener('click', () => {
            completeMultiPointDraft();
        });

        cancelBtn?.addEventListener('click', () => {
            draftPoints = [];
            setTool('OUTAGE_TEST');
        });

        document.getElementById('resetOutageBtn')?.addEventListener('click', () => {
            disabledInfraIds.clear();
            disabledNodeIds.clear();
            disabledEdgeIds.clear();
            disabledAssetIds.clear();
            if (emergencyOnlyCheckbox) {
                emergencyOnlyCheckbox.checked = false;
            }
            runOutageAnalysis();
        });

        emergencyOnlyCheckbox?.addEventListener('change', () => {
            runOutageAnalysis();
        });

        document.getElementById('emZoomOutBtn')?.addEventListener('click', () => core.zoomAt(0.8));
        document.getElementById('emZoomInBtn')?.addEventListener('click', () => core.zoomAt(1.25));
        document.getElementById('emZoom100Btn')?.addEventListener('click', () => core.resetZoom100());
        document.getElementById('emZoomFitBtn')?.addEventListener('click', () => fitWorkspace());

        const legendBtn = document.getElementById('emLegendToggleBtn');
        const legendList = document.getElementById('emLegendList');
        legendBtn?.addEventListener('click', () => {
            if (!legendList) return;
            const hidden = legendList.style.display === 'none';
            legendList.style.display = hidden ? 'flex' : 'none';
            legendBtn.textContent = hidden ? 'Hide' : 'Show';
        });

        // Delegated locate button handler across static and dynamically updated sections
        document.addEventListener('click', (e) => {
            const btn = e.target.closest('.js-locate-btn');
            if (!btn) return;
            const x = Number(btn.dataset.x);
            const y = Number(btn.dataset.y);
            if (!Number.isFinite(x) || !Number.isFinite(y)) return;
            locateCoordinate(x, y);
        });

        document.getElementById('emDialogCloseBtn')?.addEventListener('click', closeAssetDialog);
        document.getElementById('emDialogCancelBtn')?.addEventListener('click', closeAssetDialog);
        dialogForm?.addEventListener('submit', async (e) => {
            e.preventDefault();
            await submitAssetDialog();
        });
    }

    function setTool(tool) {
        activeTool = tool;
        draftPoints = [];
        document.querySelectorAll('[data-em-tool]').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.emTool === tool);
        });
        const isDraw = tool !== 'OUTAGE_TEST';
        if (finishBtn) finishBtn.style.display = (isDraw && tool !== 'PLACE_EXIT') ? 'inline-flex' : 'none';
        if (cancelBtn) cancelBtn.style.display = isDraw ? 'inline-flex' : 'none';

        if (hintEl) {
            if (tool === 'PLACE_EXIT') {
                hintEl.textContent = 'Click on the venue perimeter to place an Emergency Exit point.';
                hintEl.style.display = 'block';
            } else if (tool === 'DRAW_ASSEMBLY') {
                hintEl.textContent = 'Click vertices to outline an Assembly Point polygon, then click Finish Drawing (or double-click).';
                hintEl.style.display = 'block';
            } else if (tool === 'DRAW_AMBULANCE') {
                hintEl.textContent = 'Click points to trace an Ambulance Access Route, then click Finish Drawing (or double-click).';
                hintEl.style.display = 'block';
            } else if (tool === 'DRAW_FIRE') {
                hintEl.textContent = 'Click points to trace a Fire Access Route, then click Finish Drawing (or double-click).';
                hintEl.style.display = 'block';
            } else {
                hintEl.style.display = 'none';
            }
        }
        if (modeNoteEl) {
            modeNoteEl.textContent = isDraw
                ? 'Drawing mode active — click canvas vertices; press Esc or Cancel to return to Outage Test mode.'
                : 'Click any exit or pathway edge on the map to toggle simulated outage · Drag with middle-mouse or Shift+drag to pan.';
        }
        core.requestRender();
    }

    function bindCanvasEvents() {
        canvasEl.addEventListener('mousemove', (e) => {
            const local = core.getCanvasLocalCoords(e.clientX, e.clientY);
            if (dragPan) {
                const dx = local.x - dragPan.lastX;
                const dy = local.y - dragPan.lastY;
                dragPan.lastX = local.x;
                dragPan.lastY = local.y;
                if (Math.hypot(local.x - dragPan.startX, local.y - dragPan.startY) > 3) {
                    dragPan.moved = true;
                }
                core.panBy(dx, dy);
                return;
            }
            const world = core.screenToWorld(local.x, local.y);
            hoverWorld = core.snapMeters > 0 ? core.snapWorld(world.x, world.y) : world;
            if (coordsEl) {
                coordsEl.textContent = `X: ${hoverWorld.x.toFixed(1)} m, Y: ${hoverWorld.y.toFixed(1)} m`;
            }
            if (activeTool !== 'OUTAGE_TEST' && draftPoints.length > 0) {
                core.requestRender();
            }
        });

        canvasEl.addEventListener('mousedown', (e) => {
            const local = core.getCanvasLocalCoords(e.clientX, e.clientY);
            if (e.button === 1 || e.shiftKey) {
                e.preventDefault();
                dragPan = { startX: local.x, startY: local.y, lastX: local.x, lastY: local.y, moved: false };
                return;
            }
            if (e.button === 0 && activeTool === 'OUTAGE_TEST') {
                const world = core.screenToWorld(local.x, local.y);
                const hit = hitTestOutageTarget(world);
                if (!hit) {
                    dragPan = { startX: local.x, startY: local.y, lastX: local.x, lastY: local.y, moved: false };
                }
            }
        });

        window.addEventListener('mouseup', () => {
            if (dragPan) {
                dragPan = null;
            }
        });

        canvasEl.addEventListener('click', (e) => {
            if (e.shiftKey || e.button !== 0) return;
            const local = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const rawWorld = core.screenToWorld(local.x, local.y);
            const snapped = core.snapMeters > 0 ? core.snapWorld(rawWorld.x, rawWorld.y) : rawWorld;

            if (activeTool === 'OUTAGE_TEST') {
                const hit = hitTestOutageTarget(rawWorld);
                if (hit) {
                    toggleHitTarget(hit);
                }
                return;
            }

            if (!canEdit) return;
            if (activeTool === 'PLACE_EXIT') {
                openAssetDialog('EMERGENCY_EXIT', [{ x: round2(snapped.x), y: round2(snapped.y) }]);
            } else {
                draftPoints.push({ x: round2(snapped.x), y: round2(snapped.y) });
                core.requestRender();
            }
        });

        canvasEl.addEventListener('dblclick', (e) => {
            if (activeTool === 'OUTAGE_TEST' || activeTool === 'PLACE_EXIT') return;
            e.preventDefault();
            completeMultiPointDraft();
        });

        canvasEl.addEventListener('wheel', (e) => {
            e.preventDefault();
            const local = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const factor = e.deltaY < 0 ? 1.12 : 0.89;
            core.zoomAt(factor, local.x, local.y);
        }, { passive: false });

        window.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && activeTool !== 'OUTAGE_TEST') {
                draftPoints = [];
                setTool('OUTAGE_TEST');
            }
        });
    }

    function completeMultiPointDraft() {
        if (activeTool === 'DRAW_ASSEMBLY') {
            if (draftPoints.length < 3) {
                if (hintEl) hintEl.textContent = 'Assembly Point polygon requires at least 3 points.';
                return;
            }
            openAssetDialog('ASSEMBLY_POINT', [...draftPoints]);
        } else if (activeTool === 'DRAW_AMBULANCE') {
            if (draftPoints.length < 2) {
                if (hintEl) hintEl.textContent = 'Ambulance Route requires at least 2 points.';
                return;
            }
            openAssetDialog('AMBULANCE_ROUTE', [...draftPoints]);
        } else if (activeTool === 'DRAW_FIRE') {
            if (draftPoints.length < 2) {
                if (hintEl) hintEl.textContent = 'Fire Access Route requires at least 2 points.';
                return;
            }
            openAssetDialog('FIRE_ROUTE', [...draftPoints]);
        }
    }

    function openAssetDialog(assetType, points) {
        pendingAssetPayload = { assetType, points };
        const labels = {
            EMERGENCY_EXIT: 'Emergency Exit',
            ASSEMBLY_POINT: 'Assembly Point',
            AMBULANCE_ROUTE: 'Ambulance Route',
            FIRE_ROUTE: 'Fire Access Route'
        };
        dialogTitle.textContent = 'Save ' + (labels[assetType] || 'Emergency Asset');
        const countSame = (state.assets || []).filter(a => a.assetType === assetType).length + 1;
        dialogLabel.value = `${labels[assetType]} ${countSame}`;
        dialogCapacity.value = '';
        dialogNotes.value = '';
        dialogError.style.display = 'none';

        if (assetType === 'ASSEMBLY_POINT') {
            dialogWidthGroup.style.display = 'none';
            dialogWidth.value = '';
        } else {
            dialogWidthGroup.style.display = 'flex';
            dialogWidth.value = '4.0';
        }
        dialogEl.showModal();
    }

    function closeAssetDialog() {
        pendingAssetPayload = null;
        if (dialogEl.open) {
            dialogEl.close();
        }
    }

    async function submitAssetDialog() {
        if (!pendingAssetPayload) return;
        const { assetType, points } = pendingAssetPayload;
        const body = {
            assetType,
            label: dialogLabel.value.trim(),
            points: assetType === 'EMERGENCY_EXIT' ? null : points,
            x: assetType === 'EMERGENCY_EXIT' ? points[0].x : null,
            y: assetType === 'EMERGENCY_EXIT' ? points[0].y : null,
            minWidthM: (assetType !== 'ASSEMBLY_POINT' && dialogWidth.value) ? Number(dialogWidth.value) : null,
            capacityNotes: dialogCapacity.value.trim() || null,
            notes: dialogNotes.value.trim() || null
        };
        try {
            const nextWorkspace = await apiPost(`/api/events/${eventId}/emergency/assets`, body);
            state = nextWorkspace;
            closeAssetDialog();
            setTool('OUTAGE_TEST');
            await runOutageAnalysis();
        } catch (err) {
            dialogError.textContent = err.message || 'Failed to save emergency asset.';
            dialogError.style.display = 'block';
        }
    }

    function hitTestOutageTarget(world) {
        const hitRadiusWorld = Math.max(2.0, 14 / core.zoom);

        // 1. Exit infrastructure elements
        for (const el of (state.elements || [])) {
            if (!el.active || !el.exitPortal) continue;
            const d = Math.hypot(world.x - el.centerX, world.y - el.centerY);
            const elRadius = Math.max(el.widthM, el.heightM) / 2.0 + hitRadiusWorld * 0.6;
            if (d <= elRadius) {
                return { kind: 'INFRA_EXIT', id: el.id };
            }
        }

        // 2. Emergency Exit assets
        for (const asset of (state.assets || [])) {
            if (asset.assetType !== 'EMERGENCY_EXIT' || !asset.anchorPoint) continue;
            const d = Math.hypot(world.x - asset.anchorPoint.x, world.y - asset.anchorPoint.y);
            if (d <= hitRadiusWorld) {
                return { kind: 'ASSET_EXIT', id: asset.id };
            }
        }

        // 3. Exit pathway nodes
        for (const node of (state.nodes || [])) {
            if (node.nodeType !== 'EXIT_GATE' && node.nodeType !== 'EMERGENCY_EXIT') continue;
            const d = Math.hypot(world.x - node.x, world.y - node.y);
            if (d <= hitRadiusWorld) {
                return { kind: 'NODE_EXIT', id: node.id };
            }
        }

        // 4. Pathway edges (corridors)
        let bestEdge = null;
        let bestDist = hitRadiusWorld;
        for (const edge of (state.edges || [])) {
            if (!edge.active || !Array.isArray(edge.points) || edge.points.length < 2) continue;
            const d = distanceToPolyline(world, edge.points);
            if (d <= bestDist) {
                bestDist = d;
                bestEdge = edge;
            }
        }
        if (bestEdge) {
            return { kind: 'EDGE', id: bestEdge.id };
        }
        return null;
    }

    function toggleHitTarget(hit) {
        if (hit.kind === 'INFRA_EXIT') {
            toggleSetId(disabledInfraIds, hit.id);
        } else if (hit.kind === 'ASSET_EXIT') {
            toggleSetId(disabledAssetIds, hit.id);
        } else if (hit.kind === 'NODE_EXIT') {
            toggleSetId(disabledNodeIds, hit.id);
        } else if (hit.kind === 'EDGE') {
            toggleSetId(disabledEdgeIds, hit.id);
        }
        runOutageAnalysis();
    }

    function toggleSetId(set, id) {
        if (id == null) return;
        if (set.has(id)) {
            set.delete(id);
        } else {
            set.add(id);
        }
    }

    async function runOutageAnalysis() {
        const body = {
            disabledExitInfraIds: Array.from(disabledInfraIds),
            disabledNodeIds: Array.from(disabledNodeIds),
            disabledEdgeIds: Array.from(disabledEdgeIds),
            disabledAssetIds: Array.from(disabledAssetIds),
            emergencyExitsOnly: Boolean(emergencyOnlyCheckbox?.checked)
        };
        try {
            outageResult = await apiPost(`/api/events/${eventId}/emergency/outage-test`, body);
            refreshOutageAndDom(true);
        } catch (err) {
            if (modeNoteEl) {
                modeNoteEl.textContent = err.message || 'Outage test failed.';
            }
        }
    }

    function refreshOutageAndDom(includeAssetsAndValidation) {
        updateKpiCards();
        renderOutageChips();
        renderOutageQuickToggles();
        renderZoneImpactTable();
        if (includeAssetsAndValidation) {
            renderValidationPanels();
            renderAssetsList();
        }
        core.requestRender();
    }

    function updateKpiCards() {
        const pct = Number(outageResult.remainingEgressCapacityPct ?? 100);
        const remW = Number(outageResult.remainingEgressWidthM ?? 0);
        const baseW = Number(outageResult.baselineTotalEgressWidthM ?? 0);
        const lossW = Number(outageResult.egressWidthLossM ?? 0);
        const lossPct = Number(outageResult.egressWidthLossPct ?? 0);
        const remThr = Number(outageResult.remainingThroughputPerMin ?? 0);
        const baseThr = Number(outageResult.baselineThroughputPerMin ?? 0);
        const remThrPct = Number(outageResult.remainingThroughputPct ?? 100);

        setText('kpiRemainingEgressPct', `${pct.toFixed(1)}%`);
        setText('kpiEgressSub', `${remW.toFixed(2)} / ${baseW.toFixed(2)} m open`);
        setText('kpiIsolatedZones', String(outageResult.newlyIsolatedZoneCount ?? 0));
        setText('kpiIsolatedSub',
            `${outageResult.reroutedZoneCount ?? 0} rerouted · ${outageResult.alreadyIsolatedZoneCount ?? 0} baseline unlinked`);

        setText('outageWidthLossText', `${lossW.toFixed(2)} m lost (${lossPct.toFixed(1)}% drop)`);
        setText('outageThroughputText', `${remThr.toFixed(1)} / ${baseThr.toFixed(1)} p/min (${remThrPct.toFixed(1)}%)`);

        const bar = document.getElementById('outageCapacityBar');
        if (bar) {
            bar.style.width = `${Math.max(0, Math.min(100, pct))}%`;
            bar.classList.toggle('is-tightest', pct < 75);
        }

        const totalDisabled = disabledInfraIds.size + disabledNodeIds.size + disabledEdgeIds.size + disabledAssetIds.size;
        const badge = document.getElementById('outageCountBadge');
        if (badge) {
            badge.textContent = `${totalDisabled} disabled`;
            badge.className = 'badge ' + (totalDisabled > 0 ? 'badge-critical' : 'badge-neutral');
        }
        const modeBadge = document.getElementById('outageModeBadge');
        if (modeBadge) {
            const isolated = outageResult.newlyIsolatedZoneCount || 0;
            if (isolated > 0) {
                modeBadge.textContent = `${isolated} Zone(s) Isolated`;
                modeBadge.className = 'badge badge-critical';
            } else if (totalDisabled > 0) {
                modeBadge.textContent = `${totalDisabled} Outage Active`;
                modeBadge.className = 'badge badge-high';
            } else {
                modeBadge.textContent = 'Outage Mode Ready';
                modeBadge.className = 'badge badge-info';
            }
        }

        const val = state.validation || {};
        setText('kpiVehicleRoutes', `${val.compliantVehicleRoutes ?? 0} / ${val.totalVehicleRoutes ?? 0}`);
        setText('kpiVehicleSub', `${val.narrowRouteCount ?? 0} narrow · ${val.obstructedRouteCount ?? 0} obstructed`);
        setText('kpiContactsCount', String(val.contactCount ?? 0));
        setText('kpiContactsSub', `${val.emergencyExitCount ?? 0} emergency exits · ${val.assemblyPointCount ?? 0} assembly pts`);

        const headerBadge = document.getElementById('headerValidationBadge');
        if (headerBadge) {
            const hasWarn = Boolean(val.hasCriticalWarnings) || (val.findings && val.findings.length > 0);
            headerBadge.textContent = hasWarn ? 'Warnings Found' : 'Route Checks Ready';
            headerBadge.className = 'badge ' + (hasWarn ? 'badge-high' : 'badge-verified');
        }
    }

    function renderOutageChips() {
        const container = document.getElementById('outageChipsContainer');
        if (!container) return;
        const chips = [];

        for (const id of disabledInfraIds) {
            const el = (state.elements || []).find(item => item.id === id);
            chips.push({ kind: 'INFRA_EXIT', id, label: `Exit: ${el ? el.label : '#' + id}` });
        }
        for (const id of disabledAssetIds) {
            const a = (state.assets || []).find(item => item.id === id);
            chips.push({ kind: 'ASSET_EXIT', id, label: `Emergency Exit: ${a ? a.label : '#' + id}` });
        }
        for (const id of disabledNodeIds) {
            const n = (state.nodes || []).find(item => item.id === id);
            chips.push({ kind: 'NODE_EXIT', id, label: `Node: ${n ? n.label : '#' + id}` });
        }
        for (const id of disabledEdgeIds) {
            const edge = (state.edges || []).find(item => item.id === id);
            chips.push({ kind: 'EDGE', id, label: `Corridor: ${edge ? edge.label : '#' + id}` });
        }

        if (chips.length === 0) {
            container.innerHTML = '<span class="text-muted" style="font-size: 12px;">No exits or corridors disabled — showing baseline emergency reachability.</span>';
            return;
        }

        container.innerHTML = '';
        for (const chip of chips) {
            const span = document.createElement('span');
            span.className = 'outage-chip';
            const text = document.createElement('span');
            text.textContent = chip.label;
            const rm = document.createElement('button');
            rm.type = 'button';
            rm.title = 'Restore';
            rm.textContent = '×';
            rm.addEventListener('click', () => toggleHitTarget(chip));
            span.appendChild(text);
            span.appendChild(rm);
            container.appendChild(span);
        }
    }

    function renderOutageQuickToggles() {
        const grid = document.getElementById('outageQuickToggleGrid');
        if (!grid) return;
        grid.innerHTML = '';

        const items = [];
        for (const el of (state.elements || [])) {
            if (!el.active || !el.exitPortal) continue;
            items.push({
                kind: 'INFRA_EXIT',
                id: el.id,
                checked: disabledInfraIds.has(el.id),
                label: `${el.shortCode}: ${el.label}`
            });
        }
        for (const asset of (state.assets || [])) {
            if (asset.assetType !== 'EMERGENCY_EXIT') continue;
            items.push({
                kind: 'ASSET_EXIT',
                id: asset.id,
                checked: disabledAssetIds.has(asset.id),
                label: `EEX Asset: ${asset.label}`
            });
        }
        for (const node of (state.nodes || [])) {
            if ((node.nodeType !== 'EXIT_GATE' && node.nodeType !== 'EMERGENCY_EXIT') || node.linkedInfraId != null) continue;
            items.push({
                kind: 'NODE_EXIT',
                id: node.id,
                checked: disabledNodeIds.has(node.id),
                label: `Exit Node: ${node.label}`
            });
        }
        for (const edge of (state.edges || [])) {
            if (!edge.active) continue;
            items.push({
                kind: 'EDGE',
                id: edge.id,
                checked: disabledEdgeIds.has(edge.id),
                label: `Corridor: ${edge.label} (${Number(edge.widthM).toFixed(1)} m)`
            });
        }

        if (items.length === 0) {
            grid.innerHTML = '<span class="text-muted" style="font-size: 12px;">No active exit portals or pathway corridors in this venue yet.</span>';
            return;
        }

        for (const item of items) {
            const label = document.createElement('label');
            label.className = 'outage-toggle-item';
            const cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.checked = item.checked;
            cb.addEventListener('change', () => toggleHitTarget(item));
            const txt = document.createElement('span');
            txt.textContent = item.label;
            label.appendChild(cb);
            label.appendChild(txt);
            grid.appendChild(label);
        }
    }

    function renderZoneImpactTable() {
        const emptyEl = document.getElementById('zoneImpactEmpty');
        const wrapEl = document.getElementById('zoneImpactTableWrap');
        const tbody = document.getElementById('zoneImpactTableBody');
        if (!tbody) return;
        const impacts = outageResult.zoneImpacts || [];

        if (impacts.length === 0) {
            if (emptyEl) emptyEl.style.display = 'flex';
            if (wrapEl) wrapEl.style.display = 'none';
            return;
        }
        if (emptyEl) emptyEl.style.display = 'none';
        if (wrapEl) wrapEl.style.display = 'block';

        tbody.innerHTML = '';
        const statusMeta = {
            UNAFFECTED: { text: 'Unaffected', cls: 'badge-verified' },
            REROUTED: { text: 'Rerouted', cls: 'badge-low' },
            NEWLY_ISOLATED: { text: 'Newly Isolated', cls: 'badge-critical' },
            ALREADY_ISOLATED: { text: 'Already Isolated', cls: 'badge-high' }
        };

        for (const z of impacts) {
            const tr = document.createElement('tr');
            const meta = statusMeta[z.status] || { text: z.status, cls: 'badge-neutral' };

            const tdZone = document.createElement('td');
            tdZone.innerHTML = `<strong class="mono">${escapeHtml(z.zoneCode || 'ZONE')}</strong>` +
                `<div class="text-secondary" style="font-size: 12px;">${escapeHtml(z.zoneName || z.hubNodeLabel || '')}</div>`;

            const tdStatus = document.createElement('td');
            tdStatus.innerHTML = `<span class="badge ${meta.cls}">${escapeHtml(meta.text)}</span>` +
                (z.lostDedicatedEmergencyExit ? ' <span class="badge badge-high">Lost Em. Exit</span>' : '');

            const tdBase = document.createElement('td');
            tdBase.innerHTML = `<span>${escapeHtml(z.baselineExitLabel || '—')}</span>` +
                (z.baselineDistanceM != null ? `<div class="mono text-muted" style="font-size: 11px;">${Number(z.baselineDistanceM).toFixed(1)} m</div>` : '');

            const tdBackup = document.createElement('td');
            tdBackup.innerHTML = `<span>${escapeHtml(z.backupExitLabel || 'No surviving exit')}</span>` +
                (z.backupDistanceM != null ? `<div class="mono text-muted" style="font-size: 11px;">${Number(z.backupDistanceM).toFixed(1)} m</div>` : '');

            const tdDetour = document.createElement('td');
            tdDetour.className = 'num';
            tdDetour.textContent = z.detourDistanceM != null ? `+${Number(z.detourDistanceM).toFixed(1)} m` : '—';

            const tdWidth = document.createElement('td');
            tdWidth.className = 'num';
            if (z.backupMinWidthM != null) {
                tdWidth.innerHTML = `<span class="${z.backupNarrowWarning ? 'form-error' : ''}">${Number(z.backupMinWidthM).toFixed(2)} m</span>`;
            } else {
                tdWidth.textContent = '—';
            }

            const tdLocate = document.createElement('td');
            if (z.locateX != null && z.locateY != null) {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'btn btn-ghost btn-sm js-locate-btn';
                btn.dataset.x = z.locateX;
                btn.dataset.y = z.locateY;
                btn.textContent = 'Locate';
                tdLocate.appendChild(btn);
            }

            tr.append(tdZone, tdStatus, tdBase, tdBackup, tdDetour, tdWidth, tdLocate);
            tbody.appendChild(tr);
        }
    }

    function renderValidationPanels() {
        const val = state.validation || {};
        const statuses = val.routeStatuses || [];
        const findings = val.findings || [];

        setText('routeCountPill', `${statuses.length} routes`);
        const emptyEl = document.getElementById('routeStatusEmpty');
        const wrapEl = document.getElementById('routeStatusTableWrap');
        const tbody = document.getElementById('routeStatusTableBody');

        if (tbody) {
            if (statuses.length === 0) {
                if (emptyEl) emptyEl.style.display = 'block';
                if (wrapEl) wrapEl.style.display = 'none';
            } else {
                if (emptyEl) emptyEl.style.display = 'none';
                if (wrapEl) wrapEl.style.display = 'block';
                tbody.innerHTML = '';
                for (const r of statuses) {
                    const tr = document.createElement('tr');
                    const obsHtml = r.obstacleCount > 0
                        ? `<span class="form-error">${escapeHtml((r.obstacleLabels || []).join(', '))}</span>`
                        : '<span class="text-secondary">0 obstacles</span>';
                    const locateHtml = (r.locateX != null && r.locateY != null)
                        ? `<button type="button" class="btn btn-ghost btn-sm js-locate-btn" data-x="${r.locateX}" data-y="${r.locateY}">Locate</button>`
                        : '';
                    tr.innerHTML = `
                        <td><strong>${escapeHtml(r.label)}</strong><div class="text-muted mono" style="font-size: 11px;">${escapeHtml(r.sourceType)}</div></td>
                        <td>${escapeHtml(r.routeRole)}</td>
                        <td class="num ${!r.widthCompliant ? 'form-error' : ''}">${r.clearWidthM != null ? Number(r.clearWidthM).toFixed(2) + ' m' : 'Missing'}</td>
                        <td>${obsHtml}</td>
                        <td><span class="badge ${r.compliant ? 'badge-verified' : 'badge-critical'}">${r.compliant ? 'Clear >= 3.5 m' : 'Action Required'}</span></td>
                        <td>${locateHtml}</td>
                    `;
                    tbody.appendChild(tr);
                }
            }
        }

        const fList = document.getElementById('validationFindingsList');
        if (fList) {
            if (findings.length === 0) {
                fList.innerHTML = '<div class="banner banner-info">All emergency vehicle route checks, assembly points, exits, and contact records are populated without open validation warnings.</div>';
            } else {
                fList.innerHTML = '';
                for (const f of findings) {
                    const div = document.createElement('div');
                    div.className = 'review-entry';
                    const sevCls = f.severity === 'CRITICAL' ? 'badge-critical'
                        : (f.severity === 'HIGH' ? 'badge-high'
                        : (f.severity === 'LOW' ? 'badge-low' : 'badge-info'));
                    const locateBtn = (f.locateX != null && f.locateY != null)
                        ? `<button type="button" class="btn btn-ghost btn-sm js-locate-btn" data-x="${f.locateX}" data-y="${f.locateY}">Locate</button>`
                        : '';
                    div.innerHTML = `
                        <div class="review-entry-header">
                            <span class="badge ${sevCls}">${escapeHtml(f.severity)}</span>
                            <strong style="flex: 1;">${escapeHtml(f.title)}</strong>
                            ${locateBtn}
                        </div>
                        <p class="text-secondary" style="font-size: 13px;">${escapeHtml(f.description)}</p>
                        <div class="mono text-muted" style="font-size: 11px;">${escapeHtml(f.measuredValue + ' · ' + f.thresholdNote)}</div>
                    `;
                    fList.appendChild(div);
                }
            }
        }
    }

    function renderAssetsList() {
        const container = document.getElementById('assetsListContainer');
        const assets = state.assets || [];
        setText('assetCountPill', `${assets.length} assets`);
        if (!container) return;

        if (assets.length === 0) {
            container.innerHTML = '<div class="empty-state" style="padding: 18px 10px;"><p>No emergency-specific assets added yet. Use the map drawing buttons or the form below to mark exits, assembly points, or vehicle access routes.</p></div>';
            return;
        }

        container.innerHTML = '';
        for (const a of assets) {
            const card = document.createElement('div');
            card.className = 'asset-item';

            const left = document.createElement('div');
            left.style.cssText = 'min-width: 0; flex: 1;';
            const metaParts = [a.assetTypeName];
            if (a.minWidthM != null) metaParts.push(`Width: ${Number(a.minWidthM).toFixed(2)} m`);
            if (a.lengthM != null) metaParts.push(`Length: ${Number(a.lengthM).toFixed(1)} m`);
            if (a.areaSqm != null) metaParts.push(`Area: ${Number(a.areaSqm).toFixed(1)} m²`);

            left.innerHTML = `
                <div class="badge-row" style="margin-bottom: 4px;">
                    <span class="badge badge-neutral mono">${escapeHtml(a.shortCode)}</span>
                    <strong>${escapeHtml(a.label)}</strong>
                </div>
                <div class="mono text-muted" style="font-size: 11px;">${escapeHtml(metaParts.join(' · '))}</div>
                ${a.capacityNotes ? `<div class="text-secondary" style="font-size: 12px; margin-top: 2px;">Capacity: ${escapeHtml(a.capacityNotes)}</div>` : ''}
                ${a.notes ? `<div class="text-muted" style="font-size: 12px;">${escapeHtml(a.notes)}</div>` : ''}
            `;

            const actions = document.createElement('div');
            actions.className = 'filter-actions';
            actions.style.flexShrink = '0';

            if (a.anchorPoint) {
                const locBtn = document.createElement('button');
                locBtn.type = 'button';
                locBtn.className = 'btn btn-ghost btn-sm js-locate-btn';
                locBtn.dataset.x = a.anchorPoint.x;
                locBtn.dataset.y = a.anchorPoint.y;
                locBtn.textContent = 'Locate';
                actions.appendChild(locBtn);
            }
            if (canEdit) {
                const delBtn = document.createElement('button');
                delBtn.type = 'button';
                delBtn.className = 'btn btn-ghost btn-sm';
                delBtn.style.color = 'var(--sev-critical)';
                delBtn.textContent = 'Delete';
                delBtn.addEventListener('click', async () => {
                    try {
                        disabledAssetIds.delete(a.id);
                        state = await apiDelete(`/api/events/${eventId}/emergency/assets/${a.id}`);
                        await runOutageAnalysis();
                    } catch (err) {
                        if (modeNoteEl) modeNoteEl.textContent = err.message || 'Delete failed.';
                    }
                });
                actions.appendChild(delBtn);
            }

            card.appendChild(left);
            card.appendChild(actions);
            container.appendChild(card);
        }
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

        const zoneImpactById = new Map();
        const zoneImpactByHub = new Map();
        for (const zi of (outageResult.zoneImpacts || [])) {
            if (zi.zoneId != null) zoneImpactById.set(zi.zoneId, zi);
            if (zi.hubNodeId != null) zoneImpactByHub.set(zi.hubNodeId, zi);
        }

        // Zones (highlighted crimson for isolated, amber for rerouted)
        for (const z of (state.zones || [])) {
            const impact = zoneImpactById.get(z.id);
            const isIso = impact && (impact.status === 'NEWLY_ISOLATED' || impact.status === 'ALREADY_ISOLATED');
            const isRerouted = impact && impact.status === 'REROUTED';
            core.drawPolygonShapes(z.shapes, {
                fill: isIso
                    ? 'rgba(185, 28, 28, 0.25)'
                    : (isRerouted ? 'rgba(217, 119, 6, 0.18)' : hexToRgba(z.fillColor || '#3B82F6', 0.14)),
                stroke: isIso ? '#B91C1C' : (isRerouted ? '#B45309' : (z.strokeColor || '#64748B')),
                lineWidth: isIso ? 2.2 : 1.1,
                hatched: Boolean(z.hatched) || Boolean(isIso),
                hatchColor: isIso ? '#B91C1C' : (z.strokeColor || '#475569'),
                label: isIso ? `${z.zoneCode} · ISOLATED` : z.zoneCode,
                labelPoint: z.labelPoint,
                labelFont: isIso ? '700 10px system-ui, sans-serif' : '600 10px system-ui, sans-serif',
                labelColor: isIso ? '#991B1B' : '#334155'
            });
        }

        // Infrastructure context + Emergency Lanes + Exit Portals
        for (const el of (state.elements || [])) {
            if (!Array.isArray(el.footprintPoints) || el.footprintPoints.length < 3) continue;
            const disabled = disabledInfraIds.has(el.id);
            const exitStatus = (outageResult.exitStatuses || []).find(es => es.infraId === el.id);
            const cutOff = exitStatus && exitStatus.cutOffByCorridorOutage;

            let stroke = el.strokeColor || '#334155';
            let fill = hexToRgba(el.fillColor || '#64748B', el.active ? 0.22 : 0.08);
            let dashed = el.emergencyLane ? [7, 4] : (el.active ? null : [4, 3]);
            if (disabled) {
                stroke = '#B91C1C';
                fill = 'rgba(185, 28, 28, 0.28)';
            } else if (cutOff) {
                stroke = '#B45309';
                fill = 'rgba(217, 119, 6, 0.24)';
            }

            core.drawPolygon(el.footprintPoints, {
                fill,
                stroke,
                lineWidth: (el.exitPortal || el.emergencyLane || disabled) ? 2.0 : 1.0,
                dashed,
                label: disabled ? `${el.shortCode} CLOSED` : (cutOff ? `${el.shortCode} CUT OFF` : (el.exitPortal || el.emergencyLane ? el.label : null)),
                labelColor: disabled ? '#991B1B' : (cutOff ? '#92400E' : '#0F172A')
            });

            if (disabled) {
                drawClosedCross(el.centerX, el.centerY, '#B91C1C');
            }
        }

        // Emergency Assets (Assembly Points, Ambulance/Fire Routes, Emergency Exits)
        for (const asset of (state.assets || [])) {
            drawEmergencyAsset(asset);
        }

        // Pathway Edges
        for (const edge of (state.edges || [])) {
            if (!edge.active) continue;
            drawPathwayEdge(edge);
        }

        // Backup Routes for Rerouted Zones (dashed teal arrows)
        for (const impact of (outageResult.zoneImpacts || [])) {
            if (impact.status === 'REROUTED' && Array.isArray(impact.backupPathPoints) && impact.backupPathPoints.length >= 2) {
                drawBackupRoute(impact.backupPathPoints);
            }
        }

        // Pathway Nodes
        for (const node of (state.nodes || [])) {
            drawPathwayNode(node, zoneImpactByHub.get(node.id));
        }

        // Active drawing trace
        if (activeTool !== 'OUTAGE_TEST' && activeTool !== 'PLACE_EXIT' && draftPoints.length > 0) {
            const color = activeTool === 'DRAW_ASSEMBLY' ? '#0F766E' : (activeTool === 'DRAW_FIRE' ? '#EA580C' : '#DC2626');
            core.drawActiveTrace(draftPoints, hoverWorld, color, 'm');
        }

        // Locate pulse ring
        if (highlightPin && highlightPin.until > Date.now()) {
            const sc = core.worldToScreen(highlightPin.x, highlightPin.y);
            const ctx = core.ctx;
            ctx.save();
            ctx.strokeStyle = '#DC2626';
            ctx.lineWidth = 3;
            ctx.beginPath();
            ctx.arc(sc.x, sc.y, 18, 0, Math.PI * 2);
            ctx.stroke();
            ctx.fillStyle = 'rgba(220, 38, 38, 0.18)';
            ctx.fill();
            ctx.restore();
            setTimeout(() => core.requestRender(), 180);
        }

        core.drawScaleBarAndCompass(true);
    }

    function drawEmergencyAsset(asset) {
        const ctx = core.ctx;
        if (asset.geometryKind === 'POLYGON' && Array.isArray(asset.shapes) && asset.shapes.length > 0) {
            core.drawPolygonShapes(asset.shapes, {
                fill: hexToRgba(asset.fillColor || '#0F766E', 0.2),
                stroke: asset.strokeColor || '#0F766E',
                lineWidth: 2.0,
                dashed: asset.vehicleRoute ? [8, 4] : null,
                label: asset.label,
                labelPoint: asset.anchorPoint,
                labelColor: '#0F172A'
            });
        } else if (asset.geometryKind === 'LINESTRING' && Array.isArray(asset.points) && asset.points.length >= 2) {
            const widthPx = Math.max(4, (asset.minWidthM || 4.0) * core.zoom);
            ctx.save();
            ctx.beginPath();
            asset.points.forEach((p, idx) => {
                const sp = core.worldToScreen(p.x, p.y);
                if (idx === 0) ctx.moveTo(sp.x, sp.y);
                else ctx.lineTo(sp.x, sp.y);
            });
            ctx.strokeStyle = hexToRgba(asset.fillColor || '#DC2626', 0.24);
            ctx.lineWidth = widthPx;
            ctx.lineCap = 'butt';
            ctx.stroke();

            ctx.strokeStyle = asset.strokeColor || '#991B1B';
            ctx.lineWidth = 2.2;
            ctx.setLineDash([8, 4]);
            ctx.stroke();
            ctx.setLineDash([]);
            if (asset.anchorPoint) {
                const sa = core.worldToScreen(asset.anchorPoint.x, asset.anchorPoint.y);
                drawLabelBadge(sa.x, sa.y - 10, `${asset.label} (${asset.minWidthM ? Number(asset.minWidthM).toFixed(1) + 'm' : '?'})`, '#FFFFFF', asset.strokeColor || '#991B1B');
            }
            ctx.restore();
        } else if (asset.anchorPoint) {
            const sp = core.worldToScreen(asset.anchorPoint.x, asset.anchorPoint.y);
            const disabled = disabledAssetIds.has(asset.id);
            ctx.save();
            ctx.fillStyle = disabled ? '#B91C1C' : (asset.fillColor || '#DC2626');
            ctx.strokeStyle = '#FFFFFF';
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.arc(sp.x, sp.y, 7, 0, Math.PI * 2);
            ctx.fill();
            ctx.stroke();
            drawLabelBadge(sp.x, sp.y - 14, disabled ? `${asset.label} CLOSED` : asset.label, '#FFFFFF', disabled ? '#B91C1C' : '#0F172A');
            ctx.restore();
            if (disabled) {
                drawClosedCross(asset.anchorPoint.x, asset.anchorPoint.y, '#B91C1C');
            }
        }
    }

    function drawPathwayEdge(edge) {
        if (!Array.isArray(edge.points) || edge.points.length < 2) return;
        const ctx = core.ctx;
        const disabled = disabledEdgeIds.has(edge.id);
        ctx.save();
        ctx.beginPath();
        edge.points.forEach((p, idx) => {
            const sp = core.worldToScreen(p.x, p.y);
            if (idx === 0) ctx.moveTo(sp.x, sp.y);
            else ctx.lineTo(sp.x, sp.y);
        });
        ctx.strokeStyle = disabled ? '#B91C1C' : (edge.colorHex || '#2563EB');
        ctx.lineWidth = disabled ? 4.0 : (edge.networkLayer === 'EMERGENCY_ACCESS' ? 3.2 : 2.2);
        ctx.setLineDash(disabled ? [4, 4] : (edge.dashed ? [6, 4] : []));
        ctx.stroke();
        ctx.setLineDash([]);

        if (disabled && edge.midpoint) {
            drawClosedCross(edge.midpoint.x, edge.midpoint.y, '#B91C1C');
            const sm = core.worldToScreen(edge.midpoint.x, edge.midpoint.y);
            drawLabelBadge(sm.x, sm.y - 14, 'BLOCKED', '#FFFFFF', '#B91C1C');
        }
        ctx.restore();
    }

    function drawBackupRoute(points) {
        const ctx = core.ctx;
        ctx.save();
        ctx.beginPath();
        points.forEach((p, idx) => {
            const sp = core.worldToScreen(p.x, p.y);
            if (idx === 0) ctx.moveTo(sp.x, sp.y);
            else ctx.lineTo(sp.x, sp.y);
        });
        ctx.strokeStyle = 'rgba(15, 118, 110, 0.35)';
        ctx.lineWidth = 8;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        ctx.stroke();

        ctx.strokeStyle = '#0F766E';
        ctx.lineWidth = 3;
        ctx.setLineDash([8, 5]);
        ctx.stroke();
        ctx.setLineDash([]);

        const last = points[points.length - 1];
        const prev = points[points.length - 2];
        const sLast = core.worldToScreen(last.x, last.y);
        const sPrev = core.worldToScreen(prev.x, prev.y);
        const angle = Math.atan2(sLast.y - sPrev.y, sLast.x - sPrev.x);
        ctx.fillStyle = '#0F766E';
        ctx.beginPath();
        ctx.moveTo(sLast.x, sLast.y);
        ctx.lineTo(sLast.x - 10 * Math.cos(angle - Math.PI / 6), sLast.y - 10 * Math.sin(angle - Math.PI / 6));
        ctx.lineTo(sLast.x - 10 * Math.cos(angle + Math.PI / 6), sLast.y - 10 * Math.sin(angle + Math.PI / 6));
        ctx.closePath();
        ctx.fill();
        ctx.restore();
    }

    function drawPathwayNode(node, zoneImpact) {
        const sp = core.worldToScreen(node.x, node.y);
        const ctx = core.ctx;
        const disabled = disabledNodeIds.has(node.id)
            || (node.linkedInfraId != null && disabledInfraIds.has(node.linkedInfraId));
        const isolated = zoneImpact && (zoneImpact.status === 'NEWLY_ISOLATED' || zoneImpact.status === 'ALREADY_ISOLATED');

        ctx.save();
        ctx.fillStyle = disabled || isolated ? '#B91C1C' : (node.fillColor || '#3B82F6');
        ctx.strokeStyle = '#FFFFFF';
        ctx.lineWidth = 1.8;
        ctx.beginPath();
        ctx.arc(sp.x, sp.y, (node.nodeType === 'EXIT_GATE' || node.nodeType === 'EMERGENCY_EXIT') ? 6.5 : 5, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();

        if (disabled) {
            drawClosedCross(node.x, node.y, '#B91C1C');
        }
        ctx.restore();
    }

    function drawClosedCross(wx, wy, color) {
        const sp = core.worldToScreen(wx, wy);
        const ctx = core.ctx;
        ctx.save();
        ctx.strokeStyle = color;
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.moveTo(sp.x - 8, sp.y - 8);
        ctx.lineTo(sp.x + 8, sp.y + 8);
        ctx.moveTo(sp.x + 8, sp.y - 8);
        ctx.lineTo(sp.x - 8, sp.y + 8);
        ctx.stroke();
        ctx.restore();
    }

    function drawLabelBadge(sx, sy, text, textColor, bgColor) {
        const ctx = core.ctx;
        ctx.save();
        ctx.font = '600 10px system-ui, sans-serif';
        const w = ctx.measureText(text).width + 8;
        ctx.fillStyle = bgColor;
        ctx.fillRect(sx - w / 2, sy - 8, w, 16);
        ctx.fillStyle = textColor;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(text, sx, sy);
        ctx.restore();
    }

    function locateCoordinate(x, y) {
        highlightPin = { x, y, until: Date.now() + 2600 };
        core.panTo(x, y, Math.max(core.zoom, 9.0));
        canvasEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }

    function fitWorkspace() {
        const plan = state.venuePlan || {};
        if (state.areaBreakdown && state.areaBreakdown.boundaryBounds) {
            core.fitToBounds(state.areaBreakdown.boundaryBounds, 44);
            return;
        }
        if (plan.workspaceMode === 'BLUEPRINT_UPLOAD' && plan.hasBlueprint && plan.pixelsPerMeter) {
            core.fitToBounds({
                minX: 0,
                minY: 0,
                maxX: (plan.blueprintWidthPx || 1000) / plan.pixelsPerMeter,
                maxY: (plan.blueprintHeightPx || 800) / plan.pixelsPerMeter
            }, 36);
            return;
        }
        core.fitToBounds({ minX: 0, minY: 0, maxX: 120, maxY: 90 }, 32);
    }

    function loadBlueprintIfNeeded(plan) {
        if (!plan.hasBlueprint || !plan.blueprintUrl) {
            blueprintImage = null;
            loadedBlueprintUrl = null;
            return;
        }
        if (loadedBlueprintUrl === plan.blueprintUrl && blueprintImage) {
            return;
        }
        loadedBlueprintUrl = plan.blueprintUrl;
        const img = new Image();
        img.onload = () => {
            blueprintImage = img;
            core.requestRender();
        };
        img.src = plan.blueprintUrl + '?t=' + Date.now();
    }

    function updateZoomLabel() {
        const btn = document.getElementById('emZoom100Btn');
        if (btn) {
            btn.textContent = Math.round((core.zoom / 6.5) * 100) + '%';
        }
    }

    function distanceToPolyline(pt, points) {
        let min = Infinity;
        for (let i = 1; i < points.length; i++) {
            const d = pointToSegmentDistance(pt, points[i - 1], points[i]);
            if (d < min) min = d;
        }
        return min;
    }

    function pointToSegmentDistance(p, a, b) {
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-9) {
            return Math.hypot(p.x - a.x, p.y - a.y);
        }
        const t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq));
        const px = a.x + t * dx;
        const py = a.y + t * dy;
        return Math.hypot(p.x - px, p.y - py);
    }

    function setText(id, val) {
        const el = document.getElementById(id);
        if (el) el.textContent = val;
    }

    function round2(v) {
        return Math.round(v * 100) / 100;
    }

    function hexToRgba(hex, alpha) {
        const clean = (hex || '#64748B').replace('#', '');
        const r = parseInt(clean.substring(0, 2), 16) || 100;
        const g = parseInt(clean.substring(2, 4), 16) || 116;
        const b = parseInt(clean.substring(4, 6), 16) || 139;
        return `rgba(${r}, ${g}, ${b}, ${alpha})`;
    }

    function escapeHtml(str) {
        return String(str ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }
}
