import { apiDelete, apiPost, apiPut } from './api-client.js';
import { VyuhaCanvasCore } from './canvas-core.js';

const rootEl = document.getElementById('infraWorkspaceRoot');
if (rootEl) {
    initInfrastructureWorkspace(rootEl);
}

function initInfrastructureWorkspace(root) {
    const eventId = Number(root.dataset.eventId);
    const canEdit = root.dataset.canEdit === 'true';
    const canReview = root.dataset.canReview === 'true';
    const initialState = JSON.parse(root.dataset.initialState || '{}');

    const canvasEl = document.getElementById('infraCanvas');
    const hintBar = document.getElementById('canvasHintBar');
    const hintText = document.getElementById('canvasHintText');

    let state = initialState;
    let activeTool = 'SELECT'; // SELECT | PLACE
    let activePlaceType = document.getElementById('placeTypeSelect')?.value || 'ENTRY_GATE';
    let selectedElementId = null;
    let snapEnabled = true;
    let showAprons = true;
    let blueprintImage = null;
    let loadedBlueprintUrl = null;
    let dragState = null; // { type: 'pan' | 'move' | 'resize' | 'rotate', ... }
    let previewOverride = null; // { id, centerX, centerY, widthM, heightM, rotationDeg }
    let highlightedFinding = null; // { x, y, elementIds, until }
    let hoverWorld = null;

    const core = new VyuhaCanvasCore(canvasEl, {
        snapMeters: state.venuePlan?.gridSnapMeters ?? 1.0,
        showGrid: true,
        onRender: renderScene,
        onViewChange: updateZoomLabel
    });

    bindInspectorTabs();
    bindControls();
    bindCanvasInteractions();
    syncPlaceDefaultsFromType(activePlaceType);
    syncFromState(state, true);

    function syncFromState(nextState, fitView = false) {
        state = nextState;
        const plan = state.venuePlan || {};
        core.snapMeters = snapEnabled ? (plan.gridSnapMeters ?? 1.0) : 0;

        loadBlueprintIfNeeded(plan);

        if (state.lastModifiedElementId != null
                && (state.elements || []).some(e => e.id === state.lastModifiedElementId)) {
            selectedElementId = state.lastModifiedElementId;
        } else if (selectedElementId != null
                && !(state.elements || []).some(e => e.id === selectedElementId)) {
            selectedElementId = null;
        }

        updateStatusPill();
        updateUndoRedoButtons();
        updateSelectedElementInspector();
        updateRevisionHistory(state.history || {});
        updateSummaryPanel(state.summary || {}, state.elements || []);
        updateFindingsPanel(state.activeFindings || [], state.resolvedHistoryFindings || []);
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

        for (const ex of (state.exclusions || [])) {
            core.drawPolygon(ex.points, {
                fill: hexToRgba(ex.fillColor || '#64748B', 0.16),
                stroke: ex.strokeColor || '#64748B',
                lineWidth: 1.1,
                hatched: true,
                hatchColor: ex.strokeColor || '#64748B'
            });
        }

        for (const zone of (state.zones || [])) {
            core.drawPolygonShapes(zone.shapes, {
                fill: hexToRgba(zone.fillColor || '#3B82F6', 0.18),
                stroke: zone.strokeColor || '#64748B',
                lineWidth: 1.2,
                hatched: Boolean(zone.hatched),
                hatchColor: zone.strokeColor || '#475569',
                label: zone.zoneCode,
                labelPoint: zone.labelPoint,
                labelFont: '600 10px system-ui, sans-serif',
                labelColor: '#475569'
            });
        }

        const elements = state.elements || [];
        const conflictSeverityByElement = buildElementConflictSeverityMap(state.activeFindings || []);

        // 1. Exit clearance aprons underneath rigid structures
        if (showAprons) {
            for (const el of elements) {
                if (!el.active || !el.exitPortal) continue;
                const isDraggingThis = previewOverride && previewOverride.id === el.id;
                const apronPts = (!isDraggingThis && Array.isArray(el.clearanceApronPoints) && el.clearanceApronPoints.length >= 3)
                        ? el.clearanceApronPoints
                        : computeApronPolygonPoints(effectiveElementGeometry(el), el.clearanceBufferM || 3.0);
                if (apronPts.length >= 3) {
                    const hasCritical = conflictSeverityByElement.get(el.id) === 'CRITICAL';
                    core.drawPolygon(apronPts, {
                        fill: hasCritical ? 'rgba(185, 28, 28, 0.14)' : 'rgba(5, 150, 105, 0.09)',
                        stroke: hasCritical ? '#B91C1C' : '#059669',
                        lineWidth: 1.2,
                        dashed: [5, 4]
                    });
                }
            }
        }

        // 2. Emergency vehicle lanes first so rigid intrusions remain visible above
        for (const el of elements) {
            if (el.emergencyLane) {
                drawInfrastructureElement(el, conflictSeverityByElement.get(el.id));
            }
        }

        // 3. Rigid structures & gates
        for (const el of elements) {
            if (!el.emergencyLane) {
                drawInfrastructureElement(el, conflictSeverityByElement.get(el.id));
            }
        }

        // 4. Placement ghost preview
        if (activeTool === 'PLACE' && hoverWorld && canEdit) {
            const w = parseFloat(document.getElementById('placeWidthInput')?.value) || 4.0;
            const h = parseFloat(document.getElementById('placeHeightInput')?.value) || 3.0;
            const rot = parseFloat(document.getElementById('placeRotationInput')?.value) || 0.0;
            const snapped = snapEnabled ? core.snapWorld(hoverWorld.x, hoverWorld.y) : hoverWorld;
            const corners = rotatedRectCorners(snapped.x, snapped.y, w, h, rot);
            core.drawPolygon(corners, {
                fill: 'rgba(37, 99, 235, 0.16)',
                stroke: '#2563EB',
                lineWidth: 1.8,
                dashed: [4, 3],
                label: activePlaceType
            });
        }

        // 5. Conflict pins for active findings
        drawConflictMarkers(state.activeFindings || []);

        // 6. Selected element handles on top
        if (selectedElementId != null) {
            const sel = elements.find(e => e.id === selectedElementId);
            if (sel) {
                drawSelectionHandles(effectiveElementGeometry(sel));
            }
        }

        // 7. Locate pulse ring
        if (highlightedFinding && highlightedFinding.until > Date.now()) {
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

    function drawInfrastructureElement(el, conflictSeverity) {
        const live = effectiveElementGeometry(el);
        const corners = rotatedRectCorners(live.centerX, live.centerY, live.widthM, live.heightM, live.rotationDeg);
        const isSel = selectedElementId === el.id;
        const isLocateTarget = highlightedFinding && highlightedFinding.until > Date.now()
                && Array.isArray(highlightedFinding.elementIds)
                && highlightedFinding.elementIds.includes(el.id);

        let stroke = el.strokeColor || '#334155';
        let lineWidth = el.emergencyLane ? 2.0 : 1.8;
        if (conflictSeverity === 'CRITICAL') {
            stroke = '#B91C1C';
            lineWidth = 2.6;
        } else if (conflictSeverity === 'HIGH') {
            stroke = '#C2410C';
            lineWidth = 2.4;
        }
        if (isSel || isLocateTarget) {
            stroke = '#0F172A';
            lineWidth = 2.8;
        }

        const fillAlpha = !el.active ? 0.14 : (el.emergencyLane ? 0.22 : (isSel ? 0.56 : 0.40));
        core.drawPolygon(corners, {
            fill: hexToRgba(el.fillColor || '#64748B', fillAlpha),
            stroke,
            lineWidth,
            dashed: !el.active ? [4, 4] : (el.emergencyLane ? [8, 4] : null),
            hatched: el.emergencyLane
                    || (el.elementType === 'BARRICADE' && el.linearAligned),
            hatchColor: stroke
        });

        // Orientation marker on front/top edge & crisp label pill
        const ctx = core.ctx;
        const sc = core.worldToScreen(live.centerX, live.centerY);
        const frontWorld = localToWorld(live.centerX, live.centerY, live.rotationDeg, 0, -live.heightM / 2);
        const frontScreen = core.worldToScreen(frontWorld.x, frontWorld.y);

        ctx.save();
        ctx.strokeStyle = stroke;
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        ctx.moveTo(sc.x, sc.y);
        ctx.lineTo(frontScreen.x, frontScreen.y);
        ctx.stroke();

        const labelText = el.active ? `${el.shortCode} · ${el.label}` : `${el.shortCode} (OFF)`;
        ctx.font = '600 10px system-ui, sans-serif';
        const tw = ctx.measureText(labelText).width;
        ctx.fillStyle = 'rgba(255, 255, 255, 0.90)';
        ctx.fillRect(sc.x - tw / 2 - 4, sc.y - 8, tw + 8, 16);
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillStyle = el.active ? '#0F172A' : '#64748B';
        ctx.fillText(labelText, sc.x, sc.y);
        ctx.restore();
    }

    function drawSelectionHandles(live) {
        if (!canEdit) return;
        const ctx = core.ctx;
        const handles = getElementHandles(live);
        ctx.save();

        // Stem to rotation knob
        const topMid = localToWorld(live.centerX, live.centerY, live.rotationDeg, 0, -live.heightM / 2);
        const topScreen = core.worldToScreen(topMid.x, topMid.y);
        const rotScreen = core.worldToScreen(handles.rotate.x, handles.rotate.y);
        ctx.strokeStyle = '#2563EB';
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        ctx.moveTo(topScreen.x, topScreen.y);
        ctx.lineTo(rotScreen.x, rotScreen.y);
        ctx.stroke();

        // Rotation handle
        ctx.fillStyle = '#FFFFFF';
        ctx.strokeStyle = '#2563EB';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(rotScreen.x, rotScreen.y, 5.5, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();

        // 4 corner resize handles
        for (const corner of handles.corners) {
            const sp = core.worldToScreen(corner.x, corner.y);
            ctx.fillStyle = '#FFFFFF';
            ctx.strokeStyle = '#1D4ED8';
            ctx.lineWidth = 1.8;
            ctx.fillRect(sp.x - 4.5, sp.y - 4.5, 9, 9);
            ctx.strokeRect(sp.x - 4.5, sp.y - 4.5, 9, 9);
        }
        ctx.restore();
    }

    function drawConflictMarkers(findings) {
        const ctx = core.ctx;
        ctx.save();
        for (const f of findings) {
            if (f.locationX == null || f.locationY == null) continue;
            const sp = core.worldToScreen(f.locationX, f.locationY);
            const color = severityColor(f.severity);

            ctx.fillStyle = color;
            ctx.strokeStyle = '#FFFFFF';
            ctx.lineWidth = 1.8;
            ctx.beginPath();
            ctx.arc(sp.x, sp.y, 7.5, 0, Math.PI * 2);
            ctx.fill();
            ctx.stroke();

            ctx.fillStyle = '#FFFFFF';
            ctx.font = 'bold 10px ui-monospace, monospace';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(f.severity === 'CRITICAL' ? '!' : (f.severity === 'HIGH' ? 'H' : 'L'), sp.x, sp.y + 0.5);
        }
        ctx.restore();
    }

    function bindInspectorTabs() {
        const tabs = document.querySelectorAll('.inspector-tab[data-tab]');
        const panes = document.querySelectorAll('.inspector-pane[data-pane]');
        tabs.forEach(btn => {
            btn.addEventListener('click', () => {
                activateInspectorTab(btn.dataset.tab);
            });
        });

        document.getElementById('toggleInspectorBtn')?.addEventListener('click', () => {
            document.querySelector('.inspector-panel')?.classList.toggle('collapsed');
            setTimeout(() => core.resize(), 60);
        });
    }

    function activateInspectorTab(target) {
        document.querySelectorAll('.inspector-tab[data-tab]').forEach(t => {
            t.classList.toggle('active', t.dataset.tab === target);
        });
        document.querySelectorAll('.inspector-pane[data-pane]').forEach(p => {
            p.classList.toggle('active', p.dataset.pane === target);
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

        document.querySelector('.tool-rail-btn[data-tool="SELECT"]')?.addEventListener('click', () => {
            setSelectTool();
        });

        document.querySelectorAll('.tool-rail-btn[data-place-type]').forEach(btn => {
            btn.addEventListener('click', () => {
                if (!canEdit) return;
                setPlaceTool(btn.dataset.placeType);
            });
        });

        document.getElementById('placeTypeSelect')?.addEventListener('change', e => {
            syncPlaceDefaultsFromType(e.target.value);
            if (activeTool === 'PLACE') {
                setPlaceTool(e.target.value);
            }
        });

        document.getElementById('placeModeToggleBtn')?.addEventListener('click', () => {
            if (!canEdit) return;
            const type = document.getElementById('placeTypeSelect')?.value || activePlaceType;
            if (activeTool === 'PLACE') {
                setSelectTool();
            } else {
                setPlaceTool(type);
            }
        });

        document.getElementById('placeAtCenterBtn')?.addEventListener('click', async () => {
            if (!canEdit) return;
            const pt = computeVenueCenterPoint();
            await createElementAtWorld(pt.x, pt.y);
        });

        document.getElementById('snapToggleBtn')?.addEventListener('click', e => {
            snapEnabled = !snapEnabled;
            e.currentTarget.classList.toggle('active', snapEnabled);
            core.snapMeters = snapEnabled ? (state.venuePlan?.gridSnapMeters ?? 1.0) : 0;
            setToast(`Grid snap ${snapEnabled ? 'enabled' : 'disabled'}.`);
        });

        document.getElementById('apronToggleBtn')?.addEventListener('click', e => {
            showAprons = !showAprons;
            e.currentTarget.classList.toggle('active', showAprons);
            core.requestRender();
        });

        document.querySelectorAll('.quick-rotate-btn').forEach(btn => {
            btn.addEventListener('click', async () => {
                if (!canEdit || selectedElementId == null) return;
                const el = (state.elements || []).find(item => item.id === selectedElementId);
                if (!el) return;
                const nextRot = btn.dataset.abs !== undefined
                        ? Number(btn.dataset.abs)
                        : normalizeDeg(el.rotationDeg + Number(btn.dataset.delta || 0));
                setInputValue('selRotationInput', nextRot);
                await commitElementUpdate(el, {
                    ...collectSelectedInputValues(el),
                    rotationDeg: nextRot,
                    commandType: 'ROTATE'
                });
            });
        });

        document.getElementById('applyElementBtn')?.addEventListener('click', async () => {
            if (!canEdit || selectedElementId == null) return;
            const el = (state.elements || []).find(item => item.id === selectedElementId);
            if (!el) return;
            await commitElementUpdate(el, { ...collectSelectedInputValues(el), commandType: 'UPDATE' });
        });

        document.getElementById('duplicateElementBtn')?.addEventListener('click', duplicateSelectedElement);
        document.getElementById('deleteElementBtn')?.addEventListener('click', deleteSelectedElement);

        document.getElementById('undoBtn')?.addEventListener('click', performUndo);
        document.getElementById('redoBtn')?.addEventListener('click', performRedo);
        document.getElementById('runCheckBtn')?.addEventListener('click', runConflictCheck);
        document.getElementById('saveInfraBtn')?.addEventListener('click', async () => {
            if (!canEdit) return;
            if (selectedElementId != null) {
                const el = (state.elements || []).find(item => item.id === selectedElementId);
                if (el && hasPendingSelectedChanges(el)) {
                    await commitElementUpdate(el, { ...collectSelectedInputValues(el), commandType: 'UPDATE' });
                    setToast('Infrastructure changes saved and conflict checks updated.');
                    return;
                }
            }
            await runConflictCheck();
            setToast('Infrastructure layout verified and saved.');
        });

        document.addEventListener('keydown', async e => {
            if (isTypingElement(document.activeElement)) return;
            if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !e.shiftKey) {
                e.preventDefault();
                await performUndo();
            } else if ((e.ctrlKey || e.metaKey) && (e.key.toLowerCase() === 'y' || (e.shiftKey && e.key.toLowerCase() === 'z'))) {
                e.preventDefault();
                await performRedo();
            } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'd' && canEdit && selectedElementId != null) {
                e.preventDefault();
                await duplicateSelectedElement();
            } else if ((e.key === 'Delete' || e.key === 'Backspace') && canEdit && selectedElementId != null) {
                e.preventDefault();
                await deleteSelectedElement();
            } else if (e.key === 'Escape') {
                setSelectTool();
            }
        });
    }

    function bindCanvasInteractions() {
        canvasEl.addEventListener('wheel', e => {
            e.preventDefault();
            const pos = core.getCanvasLocalCoords(e.clientX, e.clientY);
            core.zoomAt(e.deltaY < 0 ? 1.12 : 0.89, pos.x, pos.y);
        }, { passive: false });

        canvasEl.addEventListener('mousedown', async e => {
            const pos = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const rawWorld = core.screenToWorld(pos.x, pos.y);

            if (e.button === 1 || (e.button === 0 && e.shiftKey)) {
                dragState = { type: 'pan', lastX: e.clientX, lastY: e.clientY };
                return;
            }
            if (e.button !== 0) return;

            if (activeTool === 'PLACE' && canEdit) {
                const pt = snapEnabled ? core.snapWorld(rawWorld.x, rawWorld.y) : rawWorld;
                await createElementAtWorld(pt.x, pt.y);
                return;
            }

            // 1. Check handles on currently selected element
            if (canEdit && selectedElementId != null) {
                const sel = (state.elements || []).find(item => item.id === selectedElementId);
                if (sel) {
                    const live = effectiveElementGeometry(sel);
                    const handles = getElementHandles(live);
                    const rotScreen = core.worldToScreen(handles.rotate.x, handles.rotate.y);
                    if (Math.hypot(pos.x - rotScreen.x, pos.y - rotScreen.y) <= 9) {
                        dragState = { type: 'rotate', element: sel };
                        previewOverride = { ...live, id: sel.id };
                        return;
                    }
                    for (const corner of handles.corners) {
                        const sp = core.worldToScreen(corner.x, corner.y);
                        if (Math.abs(pos.x - sp.x) <= 8 && Math.abs(pos.y - sp.y) <= 8) {
                            const anchorWorld = localToWorld(
                                    live.centerX, live.centerY, live.rotationDeg,
                                    -corner.sx * live.widthM / 2, -corner.sy * live.heightM / 2);
                            dragState = {
                                type: 'resize',
                                element: sel,
                                sx: corner.sx,
                                sy: corner.sy,
                                anchorWorld,
                                rotationDeg: live.rotationDeg
                            };
                            previewOverride = { ...live, id: sel.id };
                            return;
                        }
                    }
                }
            }

            // 2. Hit test elements (topmost rigid elements first, then lanes)
            const hit = findElementAtWorld(rawWorld);
            if (hit) {
                selectedElementId = hit.id;
                updateSelectedElementInspector();
                updateSummaryPanel(state.summary || {}, state.elements || []);
                if (canEdit) {
                    dragState = {
                        type: 'move',
                        element: hit,
                        offsetX: rawWorld.x - hit.centerX,
                        offsetY: rawWorld.y - hit.centerY
                    };
                    previewOverride = {
                        id: hit.id,
                        centerX: hit.centerX,
                        centerY: hit.centerY,
                        widthM: hit.widthM,
                        heightM: hit.heightM,
                        rotationDeg: hit.rotationDeg
                    };
                }
                core.requestRender();
                return;
            }

            // 3. Deselect and pan
            selectedElementId = null;
            updateSelectedElementInspector();
            updateSummaryPanel(state.summary || {}, state.elements || []);
            dragState = { type: 'pan', lastX: e.clientX, lastY: e.clientY };
            core.requestRender();
        });

        window.addEventListener('mousemove', e => {
            const pos = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const rawWorld = core.screenToWorld(pos.x, pos.y);
            hoverWorld = rawWorld;
            setText('cursorCoordsReadout', `X: ${rawWorld.x.toFixed(1)} m, Y: ${rawWorld.y.toFixed(1)} m`);

            if (!dragState) {
                if (activeTool === 'PLACE') {
                    core.requestRender();
                }
                return;
            }

            if (dragState.type === 'pan') {
                core.panBy(e.clientX - dragState.lastX, e.clientY - dragState.lastY);
                dragState.lastX = e.clientX;
                dragState.lastY = e.clientY;
                return;
            }

            if (dragState.type === 'move' && previewOverride) {
                const rawCx = rawWorld.x - dragState.offsetX;
                const rawCy = rawWorld.y - dragState.offsetY;
                const snapped = snapEnabled ? core.snapWorld(rawCx, rawCy) : { x: rawCx, y: rawCy };
                previewOverride.centerX = round2(snapped.x);
                previewOverride.centerY = round2(snapped.y);
                setInputValue('selCenterXInput', previewOverride.centerX);
                setInputValue('selCenterYInput', previewOverride.centerY);
                core.requestRender();
                return;
            }

            if (dragState.type === 'resize' && previewOverride) {
                const cursor = snapEnabled ? core.snapWorld(rawWorld.x, rawWorld.y) : rawWorld;
                const rad = (dragState.rotationDeg * Math.PI) / 180;
                const cos = Math.cos(rad);
                const sin = Math.sin(rad);
                const dx = cursor.x - dragState.anchorWorld.x;
                const dy = cursor.y - dragState.anchorWorld.y;
                const du = dx * cos + dy * sin;
                const dv = -dx * sin + dy * cos;
                const newW = round2(Math.max(0.5, Math.min(200.0, dragState.sx * du)));
                const newH = round2(Math.max(0.5, Math.min(200.0, dragState.sy * dv)));
                const centerOffset = localToWorld(
                        dragState.anchorWorld.x, dragState.anchorWorld.y, dragState.rotationDeg,
                        dragState.sx * (newW / 2), dragState.sy * (newH / 2));
                previewOverride.widthM = newW;
                previewOverride.heightM = newH;
                previewOverride.centerX = round2(centerOffset.x);
                previewOverride.centerY = round2(centerOffset.y);
                setInputValue('selWidthInput', previewOverride.widthM);
                setInputValue('selHeightInput', previewOverride.heightM);
                setInputValue('selCenterXInput', previewOverride.centerX);
                setInputValue('selCenterYInput', previewOverride.centerY);
                core.requestRender();
                return;
            }

            if (dragState.type === 'rotate' && previewOverride) {
                const el = dragState.element;
                const angleDeg = (Math.atan2(rawWorld.y - el.centerY, rawWorld.x - el.centerX) * 180) / Math.PI + 90;
                let norm = normalizeDeg(angleDeg);
                if (e.shiftKey) {
                    norm = normalizeDeg(Math.round(norm / 15) * 15);
                } else {
                    norm = round2(norm);
                }
                previewOverride.rotationDeg = norm;
                setInputValue('selRotationInput', previewOverride.rotationDeg);
                core.requestRender();
            }
        });

        window.addEventListener('mouseup', async () => {
            if (!dragState) return;
            const finished = dragState;
            const override = previewOverride;
            dragState = null;
            previewOverride = null;

            if (finished.type === 'pan' || !override || !finished.element) {
                return;
            }
            const orig = finished.element;
            const changed = Math.abs(orig.centerX - override.centerX) > 1e-3
                    || Math.abs(orig.centerY - override.centerY) > 1e-3
                    || Math.abs(orig.widthM - override.widthM) > 1e-3
                    || Math.abs(orig.heightM - override.heightM) > 1e-3
                    || Math.abs(orig.rotationDeg - override.rotationDeg) > 1e-3;

            if (!changed) {
                core.requestRender();
                return;
            }

            const cmdType = finished.type === 'move' ? 'MOVE'
                    : (finished.type === 'resize' ? 'RESIZE'
                    : (finished.type === 'rotate' ? 'ROTATE' : 'UPDATE'));

            await commitElementUpdate(orig, {
                label: orig.label,
                centerX: override.centerX,
                centerY: override.centerY,
                widthM: override.widthM,
                heightM: override.heightM,
                rotationDeg: override.rotationDeg,
                throughputPersonsPerMin: orig.throughputPersonsPerMin,
                active: orig.active,
                clearanceBufferM: orig.clearanceBufferM,
                linearAligned: orig.linearAligned,
                notes: orig.notes,
                commandType: cmdType
            });
        });
    }

    async function createElementAtWorld(x, y) {
        const type = document.getElementById('placeTypeSelect')?.value || activePlaceType;
        const label = document.getElementById('placeLabelInput')?.value?.trim() || null;
        const widthM = parseFloat(document.getElementById('placeWidthInput')?.value);
        const heightM = parseFloat(document.getElementById('placeHeightInput')?.value);
        const rotationDeg = parseFloat(document.getElementById('placeRotationInput')?.value || '0');
        const throughputEl = document.getElementById('placeThroughputInput');
        const throughputGroup = document.getElementById('placeThroughputGroup');
        const throughputPersonsPerMin = throughputGroup && throughputGroup.style.display !== 'none'
                ? parseFloat(throughputEl?.value)
                : null;

        try {
            const updated = await apiPost(`/api/events/${eventId}/infrastructure/elements`, {
                elementType: type,
                label,
                centerX: round2(x),
                centerY: round2(y),
                widthM: Number.isFinite(widthM) ? widthM : null,
                heightM: Number.isFinite(heightM) ? heightM : null,
                rotationDeg: Number.isFinite(rotationDeg) ? rotationDeg : 0,
                throughputPersonsPerMin: Number.isFinite(throughputPersonsPerMin) ? throughputPersonsPerMin : null
            });
            if (document.getElementById('placeLabelInput')) {
                document.getElementById('placeLabelInput').value = '';
            }
            setSelectTool();
            syncFromState(updated);
            setToast('Placed infrastructure element.');
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    async function commitElementUpdate(orig, payload) {
        try {
            const updated = await apiPut(`/api/events/${eventId}/infrastructure/elements/${orig.id}`, payload);
            syncFromState(updated);
            setToast(`Updated ${payload.label || orig.label}.`);
        } catch (err) {
            updateSelectedElementInspector();
            core.requestRender();
            setErrorToast(err.message);
        }
    }

    async function duplicateSelectedElement() {
        if (!canEdit || selectedElementId == null) return;
        try {
            const updated = await apiPost(`/api/events/${eventId}/infrastructure/elements/${selectedElementId}/duplicate`, {});
            syncFromState(updated);
            setToast('Duplicated infrastructure element.');
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    async function deleteSelectedElement() {
        if (!canEdit || selectedElementId == null) return;
        try {
            const updated = await apiDelete(`/api/events/${eventId}/infrastructure/elements/${selectedElementId}`);
            selectedElementId = null;
            syncFromState(updated);
            setToast('Deleted infrastructure element.');
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    async function performUndo() {
        if (!canEdit || !state.history?.canUndo) return;
        try {
            const updated = await apiPost(`/api/events/${eventId}/infrastructure/undo`, {});
            syncFromState(updated);
            setToast('Undid last infrastructure change.');
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    async function performRedo() {
        if (!canEdit || !state.history?.canRedo) return;
        try {
            const updated = await apiPost(`/api/events/${eventId}/infrastructure/redo`, {});
            syncFromState(updated);
            setToast('Redid infrastructure change.');
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    async function runConflictCheck() {
        try {
            const updated = await apiPost(`/api/events/${eventId}/infrastructure/check`, {});
            syncFromState(updated);
            const count = (updated.activeFindings || []).length;
            setToast(`Conflict check complete: ${count} active ${count === 1 ? 'finding' : 'findings'}.`);
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    function setSelectTool() {
        activeTool = 'SELECT';
        document.querySelector('.tool-rail-btn[data-tool="SELECT"]')?.classList.add('active');
        document.querySelectorAll('.tool-rail-btn[data-place-type]').forEach(b => b.classList.remove('active'));
        const toggleBtn = document.getElementById('placeModeToggleBtn');
        if (toggleBtn) toggleBtn.textContent = 'Click Canvas to Place';
        if (hintBar) hintBar.style.display = 'none';
        updateStatusBar();
        core.requestRender();
    }

    function setPlaceTool(type) {
        activeTool = 'PLACE';
        activePlaceType = type;
        const select = document.getElementById('placeTypeSelect');
        if (select && select.value !== type) {
            select.value = type;
            syncPlaceDefaultsFromType(type);
        }
        document.querySelector('.tool-rail-btn[data-tool="SELECT"]')?.classList.remove('active');
        document.querySelectorAll('.tool-rail-btn[data-place-type]').forEach(b => {
            b.classList.toggle('active', b.dataset.placeType === type);
        });
        const toggleBtn = document.getElementById('placeModeToggleBtn');
        if (toggleBtn) toggleBtn.textContent = 'Cancel Placement';
        if (hintBar && hintText) {
            const label = select?.selectedOptions?.[0]?.textContent || type;
            hintText.textContent = `Click on the canvas to place: ${label} (Esc to cancel).`;
            hintBar.style.display = 'flex';
        }
        updateStatusBar();
        core.requestRender();
    }

    function syncPlaceDefaultsFromType(type) {
        activePlaceType = type;
        const select = document.getElementById('placeTypeSelect');
        const opt = select ? Array.from(select.options).find(o => o.value === type) : null;
        if (!opt) return;
        setInputValue('placeWidthInput', opt.dataset.defaultW || '4.0');
        setInputValue('placeHeightInput', opt.dataset.defaultH || '3.0');
        const supportsThroughput = opt.dataset.supportsThroughput === 'true';
        const tpGroup = document.getElementById('placeThroughputGroup');
        if (tpGroup) tpGroup.style.display = supportsThroughput ? 'flex' : 'none';
        if (supportsThroughput) {
            setInputValue('placeThroughputInput', opt.dataset.defaultThroughput || '60');
        }
    }

    function updateSelectedElementInspector() {
        const panel = document.getElementById('selectedElementSection');
        if (!panel) return;
        const el = (state.elements || []).find(item => item.id === selectedElementId);
        if (!el) {
            panel.style.display = 'none';
            return;
        }
        panel.style.display = 'flex';
        setText('selElementMeta', `${el.elementTypeName} · ${Number(el.areaSqm).toFixed(2)} m²${el.active ? '' : ' · INACTIVE'}`);
        setInputValue('selLabelInput', el.label);
        setInputValue('selCenterXInput', el.centerX);
        setInputValue('selCenterYInput', el.centerY);
        setInputValue('selWidthInput', el.widthM);
        setInputValue('selHeightInput', el.heightM);
        setInputValue('selRotationInput', el.rotationDeg);
        setInputValue('selNotesInput', el.notes || '');

        const activeCb = document.getElementById('selActiveCheckbox');
        if (activeCb) activeCb.checked = Boolean(el.active);

        const tpGroup = document.getElementById('selThroughputGroup');
        if (tpGroup) {
            tpGroup.style.display = el.supportsThroughput ? 'flex' : 'none';
            if (el.supportsThroughput) {
                setInputValue('selThroughputInput', el.throughputPersonsPerMin ?? '');
            }
        }

        const clrGroup = document.getElementById('selClearanceGroup');
        if (clrGroup) {
            clrGroup.style.display = el.exitPortal ? 'flex' : 'none';
            if (el.exitPortal) {
                setInputValue('selClearanceInput', el.clearanceBufferM ?? 3.0);
            }
        }

        const linGroup = document.getElementById('selLinearGroup');
        const linCb = document.getElementById('selLinearCheckbox');
        if (linGroup && linCb) {
            linGroup.style.display = el.elementType === 'BARRICADE' ? 'flex' : 'none';
            linCb.checked = Boolean(el.linearAligned);
        }
    }

    function collectSelectedInputValues(orig) {
        const centerX = parseFloat(document.getElementById('selCenterXInput')?.value);
        const centerY = parseFloat(document.getElementById('selCenterYInput')?.value);
        const widthM = parseFloat(document.getElementById('selWidthInput')?.value);
        const heightM = parseFloat(document.getElementById('selHeightInput')?.value);
        const rotationDeg = parseFloat(document.getElementById('selRotationInput')?.value);
        const tpVal = parseFloat(document.getElementById('selThroughputInput')?.value);
        const clrVal = parseFloat(document.getElementById('selClearanceInput')?.value);
        return {
            label: document.getElementById('selLabelInput')?.value?.trim() || orig.label,
            centerX: Number.isFinite(centerX) ? centerX : orig.centerX,
            centerY: Number.isFinite(centerY) ? centerY : orig.centerY,
            widthM: Number.isFinite(widthM) ? widthM : orig.widthM,
            heightM: Number.isFinite(heightM) ? heightM : orig.heightM,
            rotationDeg: Number.isFinite(rotationDeg) ? rotationDeg : orig.rotationDeg,
            throughputPersonsPerMin: orig.supportsThroughput
                    ? (Number.isFinite(tpVal) ? tpVal : orig.throughputPersonsPerMin)
                    : null,
            active: document.getElementById('selActiveCheckbox')?.checked ?? orig.active,
            clearanceBufferM: orig.exitPortal
                    ? (Number.isFinite(clrVal) ? clrVal : orig.clearanceBufferM)
                    : null,
            linearAligned: orig.elementType === 'BARRICADE'
                    ? (document.getElementById('selLinearCheckbox')?.checked ?? orig.linearAligned)
                    : null,
            notes: document.getElementById('selNotesInput')?.value?.trim() || ''
        };
    }

    function hasPendingSelectedChanges(orig) {
        const next = collectSelectedInputValues(orig);
        return next.label !== orig.label
                || Math.abs(next.centerX - orig.centerX) > 1e-3
                || Math.abs(next.centerY - orig.centerY) > 1e-3
                || Math.abs(next.widthM - orig.widthM) > 1e-3
                || Math.abs(next.heightM - orig.heightM) > 1e-3
                || Math.abs(next.rotationDeg - orig.rotationDeg) > 1e-3
                || Boolean(next.active) !== Boolean(orig.active)
                || (next.notes || '') !== (orig.notes || '');
    }

    function updateRevisionHistory(history) {
        const revisions = history.revisions || [];
        setText('revisionCountLabel', String(history.totalRevisions || 0));
        const listEl = document.getElementById('revisionHistoryList');
        if (!listEl) return;
        if (revisions.length === 0) {
            listEl.innerHTML = '<div class="text-muted" style="font-size:11px;">No revisions recorded yet.</div>';
            return;
        }
        listEl.innerHTML = revisions.map(rev => `
            <div class="revision-row ${rev.current ? 'current' : ''}">
                <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">
                    <strong class="mono">#${rev.revisionNumber}</strong> ${escapeHtml(rev.summary)}
                </span>
                <span class="mono text-muted">${escapeHtml(rev.commandType)}</span>
            </div>
        `).join('');
    }

    function updateSummaryPanel(summary, elements) {
        setText('sumTotalElements', `${summary.activeElements || 0} / ${summary.totalElements || 0}`);
        setText('sumEntryStats', `${summary.entryGateCount || 0} · ${Number(summary.totalEntryThroughputPerMin || 0).toFixed(0)} p/m`);
        const exitRate = Number(summary.totalExitThroughputPerMin || 0) + Number(summary.totalEmergencyExitThroughputPerMin || 0);
        setText('sumExitStats', `${summary.exitPortalCount || 0} · ${exitRate.toFixed(0)} p/m`);
        setText('sumLaneCount', String(summary.emergencyLaneCount || 0));
        setText('sumConflictsSummary', `${summary.criticalCount || 0} / ${summary.highCount || 0} / ${summary.lowCount || 0}`);

        const listEl = document.getElementById('elementsList');
        if (!listEl) return;
        if (!elements || elements.length === 0) {
            listEl.innerHTML = '<div class="text-muted" style="font-size:12px;">No infrastructure elements placed yet. Pick a tool on the left rail to start.</div>';
            return;
        }
        listEl.innerHTML = elements.map(el => {
            const isSel = selectedElementId === el.id;
            return `<div class="exclusion-row ${isSel ? 'selected' : ''}" data-element-id="${el.id}">
                <div style="display:flex;align-items:center;gap:8px;min-width:0;">
                    <span class="infra-swatch" style="background:${escapeHtml(el.fillColor)};border-color:${escapeHtml(el.strokeColor)};"></span>
                    <div style="min-width:0;">
                        <div style="font-size:12px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">
                            ${escapeHtml(el.label)}
                        </div>
                        <div class="text-muted mono" style="font-size:11px;">
                            ${escapeHtml(el.elementTypeName)} · ${Number(el.widthM).toFixed(1)}×${Number(el.heightM).toFixed(1)} m
                            ${!el.active ? ' · OFF' : ''}
                        </div>
                    </div>
                </div>
                <span class="mono text-secondary" style="font-size:11px;white-space:nowrap;">
                    ${Number(el.rotationDeg).toFixed(0)}°
                </span>
            </div>`;
        }).join('');

        listEl.querySelectorAll('.exclusion-row[data-element-id]').forEach(row => {
            row.addEventListener('click', () => {
                const id = Number(row.dataset.elementId);
                const el = (state.elements || []).find(item => item.id === id);
                selectedElementId = id;
                updateSelectedElementInspector();
                updateSummaryPanel(state.summary || {}, state.elements || []);
                if (el) {
                    core.panTo(el.centerX, el.centerY);
                } else {
                    core.requestRender();
                }
            });
        });
    }

    function updateFindingsPanel(activeFindings, historyFindings) {
        setText('findingsCountBadge', String(activeFindings.length));
        const listEl = document.getElementById('activeFindingsList');
        if (listEl) {
            if (!activeFindings || activeFindings.length === 0) {
                listEl.innerHTML = '<div class="banner banner-info">No automated spatial conflicts detected across active infrastructure. Human reviewer inspection still required.</div>';
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
            ${hasLoc ? `<div><button type="button" class="btn btn-secondary btn-sm locate-finding-btn" data-locate-idx="${idx}">Locate on Canvas</button></div>` : ''}
            ${reviewControl}
        </div>`;
    }

    function bindFindingCardEvents(container, findings) {
        container.querySelectorAll('.locate-finding-btn[data-locate-idx]').forEach(btn => {
            btn.addEventListener('click', () => {
                const f = findings[Number(btn.dataset.locateIdx)];
                if (f && f.locationX != null && f.locationY != null) {
                    if (Array.isArray(f.involvedElementIds) && f.involvedElementIds.length > 0) {
                        selectedElementId = f.involvedElementIds[0];
                        updateSelectedElementInspector();
                    }
                    highlightedFinding = {
                        x: f.locationX,
                        y: f.locationY,
                        elementIds: f.involvedElementIds || [],
                        until: Date.now() + 2600
                    };
                    core.panTo(f.locationX, f.locationY, 10.0);
                    setTimeout(() => core.requestRender(), 2650);
                }
            });
        });

        container.querySelectorAll('.save-finding-review-btn[data-finding-id]').forEach(btn => {
            btn.addEventListener('click', async () => {
                if (!canReview) return;
                const fid = Number(btn.dataset.findingId);
                const statusEl = container.querySelector(`.finding-status-select[data-finding-id="${fid}"]`);
                const noteEl = container.querySelector(`.finding-note-input[data-finding-id="${fid}"]`);
                try {
                    const updated = await apiPost(`/api/events/${eventId}/infrastructure/findings/${fid}/review`, {
                        reviewStatus: statusEl?.value || 'REVIEWER_NOTED',
                        reviewerNote: noteEl?.value?.trim() || null
                    });
                    syncFromState(updated);
                    activateInspectorTab('findings');
                    setToast('Reviewer finding note saved.');
                } catch (err) {
                    setErrorToast(err.message);
                }
            });
        });
    }

    function updateStatusPill() {
        const elCount = state.summary?.activeElements || 0;
        const findingCount = (state.activeFindings || []).length;
        setText('infraStatusPill', `${elCount} ${elCount === 1 ? 'element' : 'elements'} · ${findingCount} ${findingCount === 1 ? 'finding' : 'findings'}`);
    }

    function updateUndoRedoButtons() {
        const history = state.history || {};
        const undoBtn = document.getElementById('undoBtn');
        const redoBtn = document.getElementById('redoBtn');
        if (undoBtn) {
            undoBtn.disabled = !canEdit || !history.canUndo;
            undoBtn.title = history.undoLabel ? `Undo: ${history.undoLabel} (Ctrl+Z)` : 'Undo (Ctrl+Z)';
        }
        if (redoBtn) {
            redoBtn.disabled = !canEdit || !history.canRedo;
            redoBtn.title = history.redoLabel ? `Redo: ${history.redoLabel} (Ctrl+Y)` : 'Redo (Ctrl+Y)';
        }
    }

    function updateZoomLabel() {
        const pct = Math.round((core.zoom / 6.5) * 100);
        setText('zoom100Btn', `${pct}%`);
    }

    function updateStatusBar() {
        const toolText = activeTool === 'PLACE' ? `PLACE ${activePlaceType}` : 'SELECT';
        setText('statusLayerText', `Infrastructure · ${toolText}`);
    }

    function computeVenueCenterPoint() {
        const bounds = state.areaBreakdown?.boundaryBounds;
        if (bounds) {
            return {
                x: round2((bounds.minX + bounds.maxX) / 2),
                y: round2((bounds.minY + bounds.maxY) / 2)
            };
        }
        return { x: 50.0, y: 40.0 };
    }

    function effectiveElementGeometry(el) {
        if (previewOverride && previewOverride.id === el.id) {
            return previewOverride;
        }
        return {
            centerX: el.centerX,
            centerY: el.centerY,
            widthM: el.widthM,
            heightM: el.heightM,
            rotationDeg: el.rotationDeg
        };
    }

    function findElementAtWorld(pt) {
        const elements = state.elements || [];
        // Prefer rigid / gate elements before emergency lanes
        const ordered = [
            ...elements.filter(e => !e.emergencyLane).reverse(),
            ...elements.filter(e => e.emergencyLane).reverse()
        ];
        for (const el of ordered) {
            const live = effectiveElementGeometry(el);
            if (pointInRotatedRect(pt, live)) {
                return el;
            }
        }
        return null;
    }

    function getElementHandles(live) {
        const hw = live.widthM / 2;
        const hh = live.heightM / 2;
        const rotateOffsetWorld = 18 / Math.max(core.zoom, 1);
        return {
            corners: [
                { ...localToWorld(live.centerX, live.centerY, live.rotationDeg, -hw, -hh), sx: -1, sy: -1 },
                { ...localToWorld(live.centerX, live.centerY, live.rotationDeg, hw, -hh), sx: 1, sy: -1 },
                { ...localToWorld(live.centerX, live.centerY, live.rotationDeg, hw, hh), sx: 1, sy: 1 },
                { ...localToWorld(live.centerX, live.centerY, live.rotationDeg, -hw, hh), sx: -1, sy: 1 }
            ],
            rotate: localToWorld(live.centerX, live.centerY, live.rotationDeg, 0, -hh - rotateOffsetWorld)
        };
    }

    function computeApronPolygonPoints(live, bufferM) {
        const w = live.widthM + bufferM * 2;
        const h = live.heightM + bufferM * 2;
        return rotatedRectCorners(live.centerX, live.centerY, w, h, live.rotationDeg);
    }

    function buildElementConflictSeverityMap(findings) {
        const rank = { CRITICAL: 1, HIGH: 2, LOW: 3, INFO: 4 };
        const map = new Map();
        for (const f of findings) {
            for (const elId of (f.involvedElementIds || [])) {
                const prev = map.get(elId);
                if (!prev || (rank[f.severity] || 9) < (rank[prev] || 9)) {
                    map.set(elId, f.severity);
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

function localToWorld(cx, cy, rotDeg, lx, ly) {
    const rad = (rotDeg * Math.PI) / 180;
    const cos = Math.cos(rad);
    const sin = Math.sin(rad);
    return {
        x: cx + (lx * cos - ly * sin),
        y: cy + (lx * sin + ly * cos)
    };
}

function rotatedRectCorners(cx, cy, widthM, heightM, rotDeg) {
    const hw = widthM / 2;
    const hh = heightM / 2;
    return [
        localToWorld(cx, cy, rotDeg, -hw, -hh),
        localToWorld(cx, cy, rotDeg, hw, -hh),
        localToWorld(cx, cy, rotDeg, hw, hh),
        localToWorld(cx, cy, rotDeg, -hw, hh)
    ];
}

function pointInRotatedRect(pt, rect) {
    const rad = (rect.rotationDeg * Math.PI) / 180;
    const cos = Math.cos(rad);
    const sin = Math.sin(rad);
    const dx = pt.x - rect.centerX;
    const dy = pt.y - rect.centerY;
    const lx = dx * cos + dy * sin;
    const ly = -dx * sin + dy * cos;
    return Math.abs(lx) <= rect.widthM / 2 && Math.abs(ly) <= rect.heightM / 2;
}

function normalizeDeg(deg) {
    let norm = ((deg % 360) + 360) % 360;
    if (norm > 180) norm -= 360;
    if (Math.abs(norm + 180) < 1e-9) norm = 180;
    return norm;
}

function round2(v) {
    return Math.round(v * 100) / 100;
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
