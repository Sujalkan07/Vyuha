import { apiDelete, apiPost, apiPut } from './api-client.js';
import { VyuhaCanvasCore } from './canvas-core.js';

const rootEl = document.getElementById('zonesWorkspaceRoot');
if (rootEl) {
    initZonesWorkspace(rootEl);
}

function initZonesWorkspace(root) {
    const eventId = Number(root.dataset.eventId);
    const canEdit = root.dataset.canEdit === 'true';
    const initialState = JSON.parse(root.dataset.initialState || '{}');

    const canvasEl = document.getElementById('zonesCanvas');
    const hintBar = document.getElementById('canvasHintBar');
    const hintText = document.getElementById('canvasHintText');
    const emptyOverlay = document.getElementById('canvasEmptyOverlay');

    let state = initialState;
    let activeTool = 'SELECT'; // SELECT | PAINT_CELLS | ERASE_CELLS | DRAW_ZONE
    let showClippedGrid = true;
    let selectedZoneId = null;
    let stagedCellRefs = new Set();
    let draftPoints = [];
    let hoverWorld = null;
    let dragState = null; // { type: 'pan', ... } | { type: 'paint' } | { type: 'erase', removeRefs: Set }
    let blueprintImage = null;
    let loadedBlueprintUrl = null;

    const undoStack = [];
    const redoStack = [];

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
        core.snapMeters = plan.gridSnapMeters ?? 1.0;

        loadBlueprintIfNeeded(plan);
        if (selectedZoneId != null && !(state.zones || []).some(z => z.id === selectedZoneId)) {
            selectedZoneId = null;
        }

        updateGridInputs(state.gridConfig || {});
        updateGridStatusPill(state.gridConfig || {});
        updateToolAvailability(state.coverage || {});
        updateStagedUi();
        updateSelectedZoneInspector();
        updateSummaryPanel(state.coverage || {}, state.zones || []);
        updateFindingsPanel(state.findings || []);
        updateEmptyOverlay(state.coverage || {});
        updateUndoRedoButtons();
        updateStatusBar();

        if (fitView) {
            fitWorkspaceToContent();
        } else {
            core.requestRender();
        }
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
            core.drawBlueprintImage(blueprintImage, plan.pixelsPerMeter, plan.blueprintWidthPx, plan.blueprintHeightPx, 0.45);
        } else if (state.vectorMapGeoJson) {
            core.drawGeoJsonOverlay(state.vectorMapGeoJson);
        }

        if (plan.boundaryPoints && plan.boundaryPoints.length >= 3) {
            core.drawPolygon(plan.boundaryPoints, {
                fill: 'rgba(255, 255, 255, 0.35)',
                stroke: '#1E293B',
                lineWidth: 2.2
            });
        }

        // Draw exclusions subtly underneath functional zones
        for (const ex of (state.exclusions || [])) {
            core.drawPolygon(ex.points, {
                fill: hexToRgba(ex.fillColor || '#64748B', 0.16),
                stroke: ex.strokeColor || '#64748B',
                lineWidth: 1.1,
                hatched: true,
                hatchColor: ex.strokeColor || '#64748B'
            });
        }

        // Draw clipped square grid overlay
        if (showClippedGrid) {
            for (const cell of (state.gridCells || [])) {
                const isStaged = stagedCellRefs.has(cell.cellRef);
                core.drawPolygonShapes(cell.shapes, {
                    fill: isStaged ? 'rgba(37, 99, 235, 0.32)' : 'rgba(255, 255, 255, 0.01)',
                    stroke: isStaged ? '#1D4ED8' : (cell.partial ? '#94A3B8' : '#CBD5E1'),
                    lineWidth: isStaged ? 1.8 : 0.8
                });
            }
        }

        // Draw functional zones
        for (const zone of (state.zones || [])) {
            const isSel = selectedZoneId === zone.id;
            core.drawPolygonShapes(zone.shapes, {
                fill: hexToRgba(zone.fillColor || '#3B82F6', isSel ? 0.45 : 0.30),
                stroke: zone.isolated ? '#B91C1C' : (isSel ? '#0F172A' : (zone.strokeColor || '#1D4ED8')),
                lineWidth: isSel || zone.isolated ? 2.6 : 1.6,
                dashed: zone.isolated ? [6, 3] : null,
                hatched: Boolean(zone.hatched),
                hatchColor: zone.strokeColor || '#475569',
                label: `${zone.zoneCode} · ${zone.name}`,
                labelPoint: zone.labelPoint
            });
        }

        // Re-draw staged cells on top so selections stay visible over existing zones
        if (stagedCellRefs.size > 0) {
            for (const cell of (state.gridCells || [])) {
                if (!stagedCellRefs.has(cell.cellRef)) continue;
                core.drawPolygonShapes(cell.shapes, {
                    fill: 'rgba(37, 99, 235, 0.36)',
                    stroke: '#1D4ED8',
                    lineWidth: 2.0
                });
            }
        }

        if (activeTool === 'DRAW_ZONE' && draftPoints.length > 0) {
            core.drawActiveTrace(draftPoints, hoverWorld, '#2563EB', 'm');
        }

        core.drawScaleBarAndCompass(true);
    }

    function bindInspectorTabs() {
        const tabs = document.querySelectorAll('.inspector-tab[data-tab]');
        const panes = document.querySelectorAll('.inspector-pane[data-pane]');
        tabs.forEach(btn => {
            btn.addEventListener('click', () => {
                const target = btn.dataset.tab;
                tabs.forEach(t => t.classList.toggle('active', t.dataset.tab === target));
                panes.forEach(p => p.classList.toggle('active', p.dataset.pane === target));
            });
        });

        document.getElementById('toggleInspectorBtn')?.addEventListener('click', () => {
            document.querySelector('.inspector-panel')?.classList.toggle('collapsed');
            setTimeout(() => core.resize(), 60);
        });
    }

    function bindControls() {
        document.getElementById('zoomInBtn')?.addEventListener('click', () => core.zoomAt(1.25));
        document.getElementById('zoomOutBtn')?.addEventListener('click', () => core.zoomAt(0.8));
        document.getElementById('zoom100Btn')?.addEventListener('click', () => core.resetZoom100());
        document.getElementById('zoomFitBtn')?.addEventListener('click', () => fitWorkspaceToContent());

        const legendBtn = document.getElementById('legendToggleBtn');
        const legendList = document.getElementById('legendList');
        if (legendBtn && legendList) {
            legendBtn.addEventListener('click', () => {
                const hidden = legendList.style.display === 'none';
                legendList.style.display = hidden ? 'flex' : 'none';
                legendBtn.textContent = hidden ? 'Hide' : 'Show';
            });
        }

        document.querySelectorAll('.tool-rail-btn[data-tool]').forEach(btn => {
            btn.addEventListener('click', () => {
                if (!canEdit && btn.dataset.tool !== 'SELECT') return;
                setActiveTool(btn.dataset.tool);
            });
        });

        document.getElementById('toggleGridOverlayBtn')?.addEventListener('click', e => {
            showClippedGrid = !showClippedGrid;
            e.currentTarget.classList.toggle('active', showClippedGrid);
            core.requestRender();
        });

        document.querySelectorAll('.grid-preset-btn[data-size]').forEach(btn => {
            btn.addEventListener('click', async () => {
                if (!canEdit) return;
                const input = document.getElementById('gridCellSizeInput');
                if (input) input.value = btn.dataset.size;
                await applyGridConfigFromInputs();
            });
        });

        document.getElementById('applyGridConfigBtn')?.addEventListener('click', applyGridConfigFromInputs);
        document.getElementById('createZoneFromCellsBtn')?.addEventListener('click', createZoneFromStagedCells);
        document.getElementById('addCellsToZoneBtn')?.addEventListener('click', () => mutateSelectedZoneCells('ADD'));
        document.getElementById('removeCellsFromZoneBtn')?.addEventListener('click', () => mutateSelectedZoneCells('REMOVE'));
        document.getElementById('clearStagedCellsBtn')?.addEventListener('click', () => {
            stagedCellRefs.clear();
            updateStagedUi();
            core.requestRender();
        });

        document.getElementById('updateSelectedZoneBtn')?.addEventListener('click', updateSelectedZoneMetadata);
        document.getElementById('deleteSelectedZoneBtn')?.addEventListener('click', deleteSelectedZone);

        document.getElementById('undoBtn')?.addEventListener('click', performUndo);
        document.getElementById('redoBtn')?.addEventListener('click', performRedo);
        document.getElementById('saveZonesBtn')?.addEventListener('click', async () => {
            if (!canEdit) return;
            await syncSnapshotWithServer(captureLayoutSnapshot());
            setToast('Grid configuration and functional zones saved.');
        });

        document.addEventListener('keydown', e => {
            if (isTypingElement(document.activeElement)) return;
            if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !e.shiftKey) {
                e.preventDefault();
                performUndo();
            } else if ((e.ctrlKey || e.metaKey) && (e.key.toLowerCase() === 'y' || (e.shiftKey && e.key.toLowerCase() === 'z'))) {
                e.preventDefault();
                performRedo();
            } else if (e.key === 'Escape') {
                draftPoints = [];
                setActiveTool('SELECT');
            } else if (e.key === 'Backspace' && draftPoints.length > 0) {
                e.preventDefault();
                draftPoints.pop();
                core.requestRender();
            } else if (e.key === 'Enter' && activeTool === 'DRAW_ZONE' && draftPoints.length >= 3) {
                e.preventDefault();
                finishFreeformZoneTrace();
            }
        });
    }

    function bindCanvasInteractions() {
        canvasEl.addEventListener('wheel', e => {
            e.preventDefault();
            const pos = core.getCanvasLocalCoords(e.clientX, e.clientY);
            core.zoomAt(e.deltaY < 0 ? 1.12 : 0.89, pos.x, pos.y);
        }, { passive: false });

        canvasEl.addEventListener('mousedown', e => {
            const pos = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const rawWorld = core.screenToWorld(pos.x, pos.y);

            if (e.button === 1 || (e.button === 0 && e.shiftKey)) {
                dragState = { type: 'pan', lastX: e.clientX, lastY: e.clientY };
                return;
            }
            if (e.button !== 0) return;

            if (activeTool === 'PAINT_CELLS' && canEdit) {
                dragState = { type: 'paint' };
                const cell = findCellAtWorld(rawWorld);
                if (cell) {
                    stagedCellRefs.add(cell.cellRef);
                    updateStagedUi();
                    core.requestRender();
                }
                return;
            }

            if (activeTool === 'ERASE_CELLS' && canEdit) {
                dragState = { type: 'erase', removeRefs: new Set() };
                handleEraseAtWorld(rawWorld, dragState.removeRefs);
                return;
            }

            if (activeTool === 'SELECT') {
                const hitZone = findZoneAtWorld(rawWorld);
                if (hitZone) {
                    selectedZoneId = hitZone.id;
                    updateSelectedZoneInspector();
                    updateSummaryPanel(state.coverage || {}, state.zones || []);
                    core.requestRender();
                } else {
                    selectedZoneId = null;
                    updateSelectedZoneInspector();
                    updateSummaryPanel(state.coverage || {}, state.zones || []);
                    dragState = { type: 'pan', lastX: e.clientX, lastY: e.clientY };
                    core.requestRender();
                }
            }
        });

        window.addEventListener('mousemove', e => {
            const pos = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const rawWorld = core.screenToWorld(pos.x, pos.y);
            const snapped = core.snapWorld(rawWorld.x, rawWorld.y);
            hoverWorld = activeTool === 'DRAW_ZONE' ? snapped : rawWorld;
            setText('cursorCoordsReadout', `X: ${hoverWorld.x.toFixed(1)} m, Y: ${hoverWorld.y.toFixed(1)} m`);

            if (dragState) {
                if (dragState.type === 'pan') {
                    core.panBy(e.clientX - dragState.lastX, e.clientY - dragState.lastY);
                    dragState.lastX = e.clientX;
                    dragState.lastY = e.clientY;
                } else if (dragState.type === 'paint') {
                    const cell = findCellAtWorld(rawWorld);
                    if (cell && !stagedCellRefs.has(cell.cellRef)) {
                        stagedCellRefs.add(cell.cellRef);
                        updateStagedUi();
                        core.requestRender();
                    }
                } else if (dragState.type === 'erase') {
                    handleEraseAtWorld(rawWorld, dragState.removeRefs);
                }
                return;
            }

            if (activeTool === 'DRAW_ZONE' && draftPoints.length > 0) {
                core.requestRender();
            }
        });

        window.addEventListener('mouseup', async () => {
            if (!dragState) return;
            const finished = dragState;
            dragState = null;
            if (finished.type === 'erase' && finished.removeRefs && finished.removeRefs.size > 0 && selectedZoneId != null) {
                await executeCellMutationOnZone(selectedZoneId, 'REMOVE', Array.from(finished.removeRefs));
            }
        });

        canvasEl.addEventListener('click', e => {
            if (e.shiftKey || !canEdit || activeTool !== 'DRAW_ZONE') return;
            const pos = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const rawWorld = core.screenToWorld(pos.x, pos.y);
            const snapped = core.snapWorld(rawWorld.x, rawWorld.y);

            if (draftPoints.length >= 3) {
                const firstScreen = core.worldToScreen(draftPoints[0].x, draftPoints[0].y);
                if (Math.hypot(pos.x - firstScreen.x, pos.y - firstScreen.y) <= 10) {
                    finishFreeformZoneTrace();
                    return;
                }
            }
            const last = draftPoints[draftPoints.length - 1];
            if (!last || Math.hypot(snapped.x - last.x, snapped.y - last.y) > 0.05) {
                draftPoints.push(snapped);
            }
            core.requestRender();
        });

        canvasEl.addEventListener('dblclick', e => {
            if (!canEdit || activeTool !== 'DRAW_ZONE' || draftPoints.length < 3) return;
            e.preventDefault();
            finishFreeformZoneTrace();
        });
    }

    function handleEraseAtWorld(worldPt, removeRefs) {
        const cell = findCellAtWorld(worldPt);
        if (!cell) return;
        if (stagedCellRefs.has(cell.cellRef)) {
            stagedCellRefs.delete(cell.cellRef);
            updateStagedUi();
            core.requestRender();
            return;
        }
        if (selectedZoneId != null) {
            const zone = (state.zones || []).find(z => z.id === selectedZoneId);
            if (zone && ((zone.gridCellRefs && zone.gridCellRefs.includes(cell.cellRef))
                || pointInPolygonShapes(worldPt, zone.shapes))) {
                removeRefs.add(cell.cellRef);
            }
        }
    }

    async function applyGridConfigFromInputs() {
        if (!canEdit) return;
        const cellSize = parseFloat(document.getElementById('gridCellSizeInput')?.value);
        const originX = parseFloat(document.getElementById('gridOriginXInput')?.value || '0');
        const originY = parseFloat(document.getElementById('gridOriginYInput')?.value || '0');
        const rotationDeg = parseFloat(document.getElementById('gridRotationInput')?.value || '0');
        if (!Number.isFinite(cellSize) || cellSize < 2 || cellSize > 20) {
            setErrorToast('Grid cell size must be between 2.0 m and 20.0 m.');
            return;
        }
        pushUndoSnapshot();
        try {
            const updated = await apiPut(`/api/events/${eventId}/grid/config`, {
                cellSizeMeters: cellSize,
                originX: Number.isFinite(originX) ? originX : 0,
                originY: Number.isFinite(originY) ? originY : 0,
                rotationDeg: Number.isFinite(rotationDeg) ? rotationDeg : 0
            });
            stagedCellRefs.clear();
            syncFromState(updated);
            setToast(`Grid updated to ${cellSize.toFixed(1)} m (${updated.gridConfig.totalCells} clipped cells).`);
        } catch (err) {
            undoStack.pop();
            updateUndoRedoButtons();
            setErrorToast(err.message);
        }
    }

    async function createZoneFromStagedCells() {
        if (!canEdit || stagedCellRefs.size === 0) return;
        const zoneType = document.getElementById('newZoneType')?.value || 'AUDIENCE';
        const zoneCode = document.getElementById('newZoneCode')?.value?.trim() || null;
        const rawName = document.getElementById('newZoneName')?.value?.trim();
        const typeLabel = document.getElementById('newZoneType')?.selectedOptions?.[0]?.textContent?.split('/')[0]?.trim() || 'Zone';
        const name = rawName || `${typeLabel} ${(state.zones?.length || 0) + 1}`;
        const notes = document.getElementById('newZoneNotes')?.value?.trim() || null;

        pushUndoSnapshot();
        try {
            const updated = await apiPost(`/api/events/${eventId}/grid/zones`, {
                zoneCode,
                name,
                zoneType,
                notes,
                gridCellRefs: Array.from(stagedCellRefs)
            });
            stagedCellRefs.clear();
            if (document.getElementById('newZoneCode')) document.getElementById('newZoneCode').value = '';
            if (document.getElementById('newZoneName')) document.getElementById('newZoneName').value = '';
            if (document.getElementById('newZoneNotes')) document.getElementById('newZoneNotes').value = '';
            const created = (updated.zones || []).slice(-1)[0];
            selectedZoneId = created ? created.id : null;
            syncFromState(updated);
            setToast(`Created zone ${created?.zoneCode || name}.`);
        } catch (err) {
            undoStack.pop();
            updateUndoRedoButtons();
            setErrorToast(err.message);
        }
    }

    async function finishFreeformZoneTrace() {
        if (draftPoints.length < 3) return;
        const pts = draftPoints.slice();
        draftPoints = [];
        setActiveTool('SELECT');

        const zoneType = document.getElementById('newZoneType')?.value || 'AUDIENCE';
        const zoneCode = document.getElementById('newZoneCode')?.value?.trim() || null;
        const rawName = document.getElementById('newZoneName')?.value?.trim();
        const typeLabel = document.getElementById('newZoneType')?.selectedOptions?.[0]?.textContent?.split('/')[0]?.trim() || 'Zone';
        const name = rawName || `${typeLabel} ${(state.zones?.length || 0) + 1}`;
        const notes = document.getElementById('newZoneNotes')?.value?.trim() || null;

        pushUndoSnapshot();
        try {
            const updated = await apiPost(`/api/events/${eventId}/grid/zones`, {
                zoneCode,
                name,
                zoneType,
                notes,
                points: pts
            });
            if (document.getElementById('newZoneCode')) document.getElementById('newZoneCode').value = '';
            if (document.getElementById('newZoneName')) document.getElementById('newZoneName').value = '';
            if (document.getElementById('newZoneNotes')) document.getElementById('newZoneNotes').value = '';
            const created = (updated.zones || []).slice(-1)[0];
            selectedZoneId = created ? created.id : null;
            syncFromState(updated);
            setToast(`Created polygon zone ${created?.zoneCode || name}.`);
        } catch (err) {
            undoStack.pop();
            updateUndoRedoButtons();
            setErrorToast(err.message);
        }
    }

    async function mutateSelectedZoneCells(action) {
        if (!canEdit || selectedZoneId == null || stagedCellRefs.size === 0) return;
        await executeCellMutationOnZone(selectedZoneId, action, Array.from(stagedCellRefs));
        stagedCellRefs.clear();
        updateStagedUi();
    }

    async function executeCellMutationOnZone(zoneId, action, refs) {
        pushUndoSnapshot();
        try {
            const updated = await apiPut(`/api/events/${eventId}/grid/zones/${zoneId}/cells`, {
                action,
                gridCellRefs: refs
            });
            syncFromState(updated);
            setToast(`Updated zone cells (${action.toLowerCase()}).`);
        } catch (err) {
            undoStack.pop();
            updateUndoRedoButtons();
            setErrorToast(err.message);
        }
    }

    async function updateSelectedZoneMetadata() {
        if (!canEdit || selectedZoneId == null) return;
        const zone = (state.zones || []).find(z => z.id === selectedZoneId);
        if (!zone) return;

        const zoneCode = document.getElementById('selectedZoneCode')?.value?.trim();
        const name = document.getElementById('selectedZoneName')?.value?.trim();
        const zoneType = document.getElementById('selectedZoneType')?.value;
        const notes = document.getElementById('selectedZoneNotes')?.value?.trim() || null;
        if (!name) {
            setErrorToast('Zone name is required.');
            return;
        }
        pushUndoSnapshot();
        try {
            const updated = await apiPut(`/api/events/${eventId}/grid/zones/${zone.id}`, {
                zoneCode,
                name,
                zoneType,
                notes
            });
            syncFromState(updated);
            setToast(`Updated zone ${zoneCode || zone.zoneCode}.`);
        } catch (err) {
            undoStack.pop();
            updateUndoRedoButtons();
            setErrorToast(err.message);
        }
    }

    async function deleteSelectedZone() {
        if (!canEdit || selectedZoneId == null) return;
        pushUndoSnapshot();
        try {
            const updated = await apiDelete(`/api/events/${eventId}/grid/zones/${selectedZoneId}`);
            selectedZoneId = null;
            syncFromState(updated);
            setToast('Zone deleted.');
        } catch (err) {
            undoStack.pop();
            updateUndoRedoButtons();
            setErrorToast(err.message);
        }
    }

    function captureLayoutSnapshot() {
        const gc = state.gridConfig || {};
        return {
            gridConfig: {
                cellSizeMeters: gc.cellSizeMeters ?? 5.0,
                originX: gc.originX ?? 0.0,
                originY: gc.originY ?? 0.0,
                rotationDeg: gc.rotationDeg ?? 0.0
            },
            zones: (state.zones || []).map(z => ({
                id: z.id,
                zoneCode: z.zoneCode,
                name: z.name,
                zoneType: z.zoneType,
                notes: z.notes,
                polygonWkt: z.polygonWkt,
                gridCellRefs: z.gridCellRefs || []
            }))
        };
    }

    function pushUndoSnapshot() {
        undoStack.push(captureLayoutSnapshot());
        if (undoStack.length > 40) undoStack.shift();
        redoStack.length = 0;
        updateUndoRedoButtons();
    }

    async function performUndo() {
        if (!canEdit || undoStack.length === 0) return;
        redoStack.push(captureLayoutSnapshot());
        const prev = undoStack.pop();
        await syncSnapshotWithServer(prev);
        setToast('Undid last zone layout change.');
    }

    async function performRedo() {
        if (!canEdit || redoStack.length === 0) return;
        undoStack.push(captureLayoutSnapshot());
        const next = redoStack.pop();
        await syncSnapshotWithServer(next);
        setToast('Redid zone layout change.');
    }

    async function syncSnapshotWithServer(snapshot) {
        try {
            const updated = await apiPut(`/api/events/${eventId}/grid/layout`, snapshot);
            syncFromState(updated);
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    function setActiveTool(tool) {
        activeTool = tool;
        if (tool !== 'DRAW_ZONE') {
            draftPoints = [];
        }
        document.querySelectorAll('.tool-rail-btn[data-tool]').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tool === tool);
        });
        const hints = {
            PAINT_CELLS: 'Click or drag across clipped grid cells to stage them, then click Create Zone from Cells.',
            ERASE_CELLS: 'Click or drag to clear staged cells or remove cells from the selected zone.',
            DRAW_ZONE: 'Click zone polygon vertices. Click first vertex, double-click, or press Enter to close.'
        };
        if (hintBar && hintText) {
            if (hints[tool]) {
                hintText.textContent = hints[tool];
                hintBar.style.display = 'flex';
            } else {
                hintBar.style.display = 'none';
            }
        }
        updateStatusBar();
        core.requestRender();
    }

    function updateGridInputs(gc) {
        setInputValue('gridCellSizeInput', gc.cellSizeMeters ?? 5.0);
        setInputValue('gridOriginXInput', gc.originX ?? 0.0);
        setInputValue('gridOriginYInput', gc.originY ?? 0.0);
        setInputValue('gridRotationInput', gc.rotationDeg ?? 0.0);
        document.querySelectorAll('.grid-preset-btn[data-size]').forEach(btn => {
            btn.classList.toggle('active', Math.abs(Number(btn.dataset.size) - Number(gc.cellSizeMeters || 5)) < 0.01);
        });
    }

    function updateGridStatusPill(gc) {
        const size = Number(gc.cellSizeMeters || 5).toFixed(1);
        const cells = gc.totalCells || 0;
        setText('gridStatusPill', `Grid: ${size} m · ${cells} cells`);
    }

    function updateToolAvailability(coverage) {
        const hasBoundary = Boolean(coverage.hasBoundary);
        ['PAINT_CELLS', 'ERASE_CELLS', 'DRAW_ZONE'].forEach(t => {
            const btn = document.querySelector(`.tool-rail-btn[data-tool="${t}"]`);
            if (btn) btn.disabled = !canEdit || !hasBoundary;
        });
    }

    function updateStagedUi() {
        const count = stagedCellRefs.size;
        setText('stagedCellCount', `${count} ${count === 1 ? 'cell' : 'cells'}`);
        const createBtn = document.getElementById('createZoneFromCellsBtn');
        const addBtn = document.getElementById('addCellsToZoneBtn');
        const removeBtn = document.getElementById('removeCellsFromZoneBtn');
        const clearBtn = document.getElementById('clearStagedCellsBtn');
        if (createBtn) createBtn.disabled = !canEdit || count === 0;
        if (clearBtn) clearBtn.disabled = !canEdit || count === 0;
        if (addBtn) {
            addBtn.style.display = selectedZoneId != null ? 'inline-flex' : 'none';
            addBtn.disabled = !canEdit || count === 0 || selectedZoneId == null;
        }
        if (removeBtn) {
            removeBtn.style.display = selectedZoneId != null && count > 0 ? 'inline-flex' : 'none';
        }
    }

    function updateSelectedZoneInspector() {
        const panel = document.getElementById('selectedZoneSection');
        if (!panel) return;
        const zone = (state.zones || []).find(z => z.id === selectedZoneId);
        if (!zone) {
            panel.style.display = 'none';
            updateStagedUi();
            return;
        }
        panel.style.display = 'flex';
        setText('selectedZoneMeta', `${zone.zoneCode} · ${Number(zone.usableAreaSqm).toFixed(2)} m² usable${zone.isolated ? ' · ISOLATED' : ''}`);
        setInputValue('selectedZoneCode', zone.zoneCode);
        setInputValue('selectedZoneName', zone.name);
        setInputValue('selectedZoneType', zone.zoneType);
        setInputValue('selectedZoneNotes', zone.notes || '');
        updateStagedUi();
    }

    function updateSummaryPanel(cov, zones) {
        const pct = Number(cov.assignedPercent || 0);
        const bar = document.getElementById('zoneCoverageBar');
        if (bar) bar.style.width = `${Math.max(0, Math.min(100, pct))}%`;
        setText('zoneAssignedPct', `${pct.toFixed(1)}% usable assigned`);
        setText('zoneCountLabel', `${cov.zoneCount || 0} zones`);
        setText('covGrossArea', formatSqm(cov.grossAreaSqm));
        setText('covUsableArea', formatSqm(cov.totalUsableAreaSqm));
        setText('covAssignedArea', formatSqm(cov.assignedUsableAreaSqm));
        setText('covUnassignedArea', formatSqm(cov.unassignedUsableAreaSqm));
        setText('covOverlapArea', formatSqm(cov.overlapAreaSqm));

        const listEl = document.getElementById('zonesList');
        if (!listEl) return;
        if (!zones || zones.length === 0) {
            listEl.innerHTML = '<div class="text-muted" style="font-size:12px;">No functional zones created yet.</div>';
            return;
        }
        listEl.innerHTML = zones.map(z => {
            const isSel = selectedZoneId === z.id;
            return `<div class="exclusion-row ${isSel ? 'selected' : ''}" data-zone-id="${z.id}">
                <div style="display:flex;align-items:center;gap:8px;min-width:0;">
                    <span class="zone-swatch-dot" style="background:${escapeHtml(z.fillColor)};"></span>
                    <div style="min-width:0;">
                        <div style="font-size:12px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">
                            <span class="mono">${escapeHtml(z.zoneCode)}</span> · ${escapeHtml(z.name)}
                        </div>
                        <div class="text-muted" style="font-size:11px;">
                            ${escapeHtml(z.zoneTypeName)}
                            ${z.isolated ? '<span class="badge badge-critical" style="margin-left:4px;">Isolated</span>' : ''}
                        </div>
                    </div>
                </div>
                <span class="mono" style="font-size:12px;white-space:nowrap;">${Number(z.usableAreaSqm).toFixed(1)} m²</span>
            </div>`;
        }).join('');

        listEl.querySelectorAll('.exclusion-row[data-zone-id]').forEach(row => {
            row.addEventListener('click', () => {
                const id = Number(row.dataset.zoneId);
                selectedZoneId = id;
                const z = (state.zones || []).find(item => item.id === id);
                updateSelectedZoneInspector();
                updateSummaryPanel(state.coverage || {}, state.zones || []);
                if (z && z.labelPoint) {
                    core.panTo(z.labelPoint.x, z.labelPoint.y);
                } else {
                    core.requestRender();
                }
            });
        });
    }

    function updateFindingsPanel(findings) {
        setText('findingsCountBadge', String(findings.length));
        const listEl = document.getElementById('zoneFindingsList');
        if (!listEl) return;
        if (!findings || findings.length === 0) {
            listEl.innerHTML = '<div class="banner banner-info">All usable floor space is assigned with zero overlaps or isolated zones.</div>';
            return;
        }
        listEl.innerHTML = findings.map((f, idx) => {
            const sevClass = severityBadgeClass(f.severity);
            const hasLoc = f.locateX != null && f.locateY != null;
            return `<div class="finding-card">
                <div style="display:flex;align-items:center;justify-content:space-between;gap:6px;">
                    <span class="badge ${sevClass}">${escapeHtml(f.severity)}</span>
                    <span class="mono text-muted" style="font-size:11px;">${escapeHtml(f.measuredValue || '')}</span>
                </div>
                <div style="font-size:12px;font-weight:600;">${escapeHtml(f.title)}</div>
                <div class="text-secondary" style="font-size:12px;">${escapeHtml(f.detail)}</div>
                ${hasLoc ? `<div><button type="button" class="btn btn-secondary btn-sm" data-locate-idx="${idx}">Locate on Canvas</button></div>` : ''}
            </div>`;
        }).join('');

        listEl.querySelectorAll('button[data-locate-idx]').forEach(btn => {
            btn.addEventListener('click', () => {
                const f = findings[Number(btn.dataset.locateIdx)];
                if (f && f.locateX != null && f.locateY != null) {
                    core.panTo(f.locateX, f.locateY, 8.0);
                }
            });
        });
    }

    function updateEmptyOverlay(coverage) {
        if (!emptyOverlay) return;
        emptyOverlay.style.display = coverage && coverage.hasBoundary ? 'none' : 'flex';
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
        const size = Number(state.gridConfig?.cellSizeMeters || 5).toFixed(1);
        setText('statusLayerText', `Zones & Grid · ${size} m Grid · ${activeTool}`);
    }

    function findCellAtWorld(pt) {
        for (const cell of (state.gridCells || [])) {
            if (pointInPolygonShapes(pt, cell.shapes)) {
                return cell;
            }
        }
        return null;
    }

    function findZoneAtWorld(pt) {
        const zones = state.zones || [];
        for (let i = zones.length - 1; i >= 0; i--) {
            if (pointInPolygonShapes(pt, zones[i].shapes)) {
                return zones[i];
            }
        }
        return null;
    }

    function pointInPolygonShapes(pt, shapes) {
        if (!shapes || shapes.length === 0) return false;
        for (const shape of shapes) {
            if (!pointInRing(pt, shape.exterior)) continue;
            let inHole = false;
            if (Array.isArray(shape.holes)) {
                for (const hole of shape.holes) {
                    if (pointInRing(pt, hole)) {
                        inHole = true;
                        break;
                    }
                }
            }
            if (!inHole) return true;
        }
        return false;
    }

    function pointInRing(pt, ring) {
        if (!ring || ring.length < 3) return false;
        let inside = false;
        for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            const xi = ring[i].x, yi = ring[i].y;
            const xj = ring[j].x, yj = ring[j].y;
            const intersect = ((yi > pt.y) !== (yj > pt.y))
                && (pt.x < ((xj - xi) * (pt.y - yi)) / ((yj - yi) || 1e-12) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
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

function severityBadgeClass(sev) {
    switch (sev) {
        case 'CRITICAL': return 'badge-critical';
        case 'HIGH': return 'badge-high';
        case 'LOW': return 'badge-low';
        default: return 'badge-info';
    }
}

function formatSqm(v) {
    if (v == null || !Number.isFinite(Number(v))) return '0.00 m²';
    return `${Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} m²`;
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
