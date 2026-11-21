import { apiDelete, apiPost, apiPut, apiUpload } from './api-client.js';
import { VyuhaCanvasCore } from './canvas-core.js';

const rootEl = document.getElementById('venueWorkspaceRoot');
if (rootEl) {
    initVenueWorkspace(rootEl);
}

function initVenueWorkspace(root) {
    const eventId = Number(root.dataset.eventId);
    const canEdit = root.dataset.canEdit === 'true';
    const initialState = JSON.parse(root.dataset.initialState || '{}');

    const canvasEl = document.getElementById('venueCanvas');
    const hintBar = document.getElementById('canvasHintBar');
    const hintText = document.getElementById('canvasHintText');
    const emptyOverlay = document.getElementById('canvasEmptyOverlay');
    const emptyOverlayText = document.getElementById('canvasEmptyText');

    let state = initialState;
    let activeTool = 'SELECT'; // SELECT | DRAW_BOUNDARY | DRAW_EXCLUSION | CALIBRATE_PRIMARY | CALIBRATE_VERIFY
    let draftPoints = [];
    let hoverWorld = null;
    let selectedTarget = null; // { kind: 'boundary' } | { kind: 'exclusion', id }
    let dragState = null; // pan or vertex drag
    let blueprintImage = null;
    let loadedBlueprintUrl = null;
    let blueprintOpacity = 0.85;

    let calDraftStart = null;
    let activeCalPrimary = null;
    let activeCalVerify = null;

    const undoStack = [];
    const redoStack = [];
    let pendingLayoutBeforeDrag = null;

    const core = new VyuhaCanvasCore(canvasEl, {
        snapMeters: state.venuePlan ? state.venuePlan.gridSnapMeters : 1.0,
        onRender: renderScene,
        onViewChange: updateZoomLabel
    });

    bindInspectorTabs();
    bindWorkspaceControls();
    bindCanvasInteractions();
    syncFromState(state, true);

    function syncFromState(nextState, fitView = false) {
        state = nextState;
        const plan = state.venuePlan || {};
        core.snapMeters = plan.gridSnapMeters ?? 1.0;

        syncCalibrationOverlaysFromState();
        loadBlueprintIfNeeded(plan);
        updateModeButtons(plan);
        updateScalePill(plan);
        updateToolAvailability(plan);
        updateSnapButton(plan.gridSnapMeters ?? 1.0);
        updateBreakdownSummary(state.areaBreakdown, state.exclusions || []);
        updateFindingsPanel(state.findings || []);
        updateSelectionInspector();
        updateEmptyOverlay(plan, state.areaBreakdown);
        updateUndoRedoButtons();
        updateStatusBar();

        if (fitView) {
            fitWorkspaceToContent();
        } else {
            core.requestRender();
        }
    }

    function getPreviewPpm() {
        const plan = state.venuePlan || {};
        if (plan.pixelsPerMeter && plan.pixelsPerMeter > 0) {
            return plan.pixelsPerMeter;
        }
        const wPx = plan.blueprintWidthPx || (blueprintImage ? blueprintImage.naturalWidth : 1000);
        return Math.max(1, wPx / 100.0);
    }

    function worldToImagePixel(wx, wy) {
        const ppm = getPreviewPpm();
        return { px: wx * ppm, py: wy * ppm };
    }

    function imagePixelToWorld(px, py) {
        const ppm = getPreviewPpm();
        return { x: px / ppm, y: py / ppm };
    }

    function syncCalibrationOverlaysFromState() {
        const cal = state.venuePlan && state.venuePlan.calibration;
        if (!cal) {
            activeCalPrimary = null;
            activeCalVerify = null;
            return;
        }
        activeCalPrimary = {
            p1: imagePixelToWorld(cal.primaryX1Px, cal.primaryY1Px),
            p2: imagePixelToWorld(cal.primaryX2Px, cal.primaryY2Px),
            pxLen: cal.primaryPixelLength,
            realM: cal.primaryRealDistanceMeters
        };
        setInputValue('calPrimaryX1', cal.primaryX1Px);
        setInputValue('calPrimaryY1', cal.primaryY1Px);
        setInputValue('calPrimaryX2', cal.primaryX2Px);
        setInputValue('calPrimaryY2', cal.primaryY2Px);
        setInputValue('calPrimaryDist', cal.primaryRealDistanceMeters);
        setText('calPrimaryReadout', `${cal.primaryPixelLength.toFixed(1)} px = ${cal.primaryRealDistanceMeters.toFixed(2)} m`);

        if (cal.verifyX1Px != null && cal.verifyX2Px != null) {
            activeCalVerify = {
                p1: imagePixelToWorld(cal.verifyX1Px, cal.verifyY1Px),
                p2: imagePixelToWorld(cal.verifyX2Px, cal.verifyY2Px),
                pxLen: cal.verifyPixelLength,
                realM: cal.verifyRealDistanceMeters
            };
            setInputValue('calVerifyX1', cal.verifyX1Px);
            setInputValue('calVerifyY1', cal.verifyY1Px);
            setInputValue('calVerifyX2', cal.verifyX2Px);
            setInputValue('calVerifyY2', cal.verifyY2Px);
            setInputValue('calVerifyDist', cal.verifyRealDistanceMeters);
            setText('calVerifyReadout', `${cal.verifyPixelLength.toFixed(1)} px | Mismatch ${cal.mismatchPercent.toFixed(2)}%`);
        } else {
            activeCalVerify = null;
            setText('calVerifyReadout', 'Optional cross-axis check not drawn');
        }

        const orthoCheck = document.getElementById('calOrthoConfirmed');
        if (orthoCheck) {
            orthoCheck.checked = Boolean(cal.orthographicConfirmed);
        }
        renderCalibrationBanner(cal);
    }

    function renderCalibrationBanner(cal) {
        const box = document.getElementById('calibrationAuditBanner');
        if (!box) return;
        if (!cal) {
            box.innerHTML = '';
            return;
        }
        if (cal.scaleMismatchWarning || cal.distortionWarning) {
            const msgs = (cal.warningMessages || []).join(' ');
            box.innerHTML = `<div class="banner banner-danger" role="alert"><strong>Distortion / Scale Mismatch Warning:</strong> ${escapeHtml(msgs)}</div>`;
        } else if (cal.scaleVerified) {
            const pct = cal.mismatchPercent != null ? cal.mismatchPercent.toFixed(2) : '0.00';
            box.innerHTML = `<div class="banner banner-info">Scale check passed (<span class="mono">${pct}%</span> mismatch, <span class="mono">${cal.pixelsPerMeter.toFixed(2)} px/m</span>).</div>`;
        } else {
            box.innerHTML = `<div class="banner banner-warn">Calibrated at <span class="mono">${cal.pixelsPerMeter.toFixed(2)} px/m</span>. Add a perpendicular verification line to detect perspective skew.</div>`;
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
            syncCalibrationOverlaysFromState();
            if (plan.workspaceMode === 'BLUEPRINT_UPLOAD' && !state.areaBreakdown?.hasBoundary) {
                fitWorkspaceToContent();
            } else {
                core.requestRender();
            }
        };
        img.src = plan.blueprintUrl + '?t=' + Date.now();
    }

    function fitWorkspaceToContent() {
        const plan = state.venuePlan || {};
        if (state.areaBreakdown && state.areaBreakdown.boundaryBounds) {
            core.fitToBounds(state.areaBreakdown.boundaryBounds, 56);
            return;
        }
        if (plan.workspaceMode === 'BLUEPRINT_UPLOAD' && plan.hasBlueprint && plan.blueprintWidthPx && plan.blueprintHeightPx) {
            const ppm = getPreviewPpm();
            core.fitToBounds({
                minX: 0,
                minY: 0,
                maxX: plan.blueprintWidthPx / ppm,
                maxY: plan.blueprintHeightPx / ppm
            }, 40);
            return;
        }
        const fc = state.vectorMapGeoJson;
        if (fc && fc.properties && fc.properties.widthMeters && fc.properties.heightMeters) {
            core.fitToBounds({
                minX: 0,
                minY: 0,
                maxX: fc.properties.widthMeters,
                maxY: fc.properties.heightMeters
            }, 36);
            return;
        }
        core.fitToBounds({ minX: 0, minY: 0, maxX: 120, maxY: 90 }, 36);
    }

    function renderScene() {
        core.beginFrame();
        const plan = state.venuePlan || {};
        const isBlueprint = plan.workspaceMode === 'BLUEPRINT_UPLOAD';

        core.drawMetricGrid(isBlueprint && !plan.pixelsPerMeter ? 'u' : 'm');

        if (isBlueprint) {
            if (blueprintImage) {
                core.drawBlueprintImage(
                    blueprintImage,
                    getPreviewPpm(),
                    plan.blueprintWidthPx,
                    plan.blueprintHeightPx,
                    blueprintOpacity
                );
            }
        } else if (state.vectorMapGeoJson) {
            core.drawGeoJsonOverlay(state.vectorMapGeoJson);
        }

        if (plan.boundaryPoints && plan.boundaryPoints.length >= 3) {
            const isSel = selectedTarget && selectedTarget.kind === 'boundary';
            core.drawPolygon(plan.boundaryPoints, {
                fill: 'rgba(37, 99, 235, 0.08)',
                stroke: '#2563EB',
                lineWidth: isSel ? 3 : 2.2,
                selected: canEdit && isSel
            });
        }

        const exclusions = state.exclusions || [];
        for (const ex of exclusions) {
            const isSel = selectedTarget && selectedTarget.kind === 'exclusion' && selectedTarget.id === ex.id;
            core.drawPolygon(ex.points, {
                fill: hexToRgba(ex.fillColor || '#64748B', 0.22),
                stroke: isSel ? '#0F172A' : (ex.strokeColor || '#475569'),
                lineWidth: isSel ? 2.5 : 1.6,
                hatched: true,
                hatchColor: ex.strokeColor || '#475569',
                label: ex.label,
                selected: canEdit && isSel
            });
        }

        if (isBlueprint) {
            if (activeCalPrimary) {
                core.drawCalibrationSegment(
                    activeCalPrimary.p1,
                    activeCalPrimary.p2,
                    '#2563EB',
                    `Scale: ${activeCalPrimary.realM ? activeCalPrimary.realM.toFixed(1) + ' m' : Math.round(activeCalPrimary.pxLen) + ' px'}`
                );
            }
            if (activeCalVerify) {
                core.drawCalibrationSegment(
                    activeCalVerify.p1,
                    activeCalVerify.p2,
                    '#0F766E',
                    `Verify: ${activeCalVerify.realM ? activeCalVerify.realM.toFixed(1) + ' m' : Math.round(activeCalVerify.pxLen) + ' px'}`
                );
            }
            if (calDraftStart && hoverWorld && (activeTool === 'CALIBRATE_PRIMARY' || activeTool === 'CALIBRATE_VERIFY')) {
                core.drawCalibrationSegment(
                    calDraftStart,
                    hoverWorld,
                    activeTool === 'CALIBRATE_PRIMARY' ? '#2563EB' : '#0F766E',
                    'Click end point'
                );
            }
        }

        if (draftPoints.length > 0 && (activeTool === 'DRAW_BOUNDARY' || activeTool === 'DRAW_EXCLUSION')) {
            const color = activeTool === 'DRAW_BOUNDARY' ? '#2563EB' : '#C2410C';
            const unit = isBlueprint && !plan.pixelsPerMeter ? 'u' : 'm';
            core.drawActiveTrace(draftPoints, hoverWorld, color, unit);
        }

        core.drawScaleBarAndCompass(!(isBlueprint && !plan.pixelsPerMeter));
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

        const toggleBtn = document.getElementById('toggleInspectorBtn');
        const inspectorPanel = document.querySelector('.inspector-panel');
        if (toggleBtn && inspectorPanel) {
            toggleBtn.addEventListener('click', () => {
                inspectorPanel.classList.toggle('collapsed');
                setTimeout(() => core.resize(), 60);
            });
        }
    }

    function bindWorkspaceControls() {
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

        document.getElementById('snapToggleBtn')?.addEventListener('click', async () => {
            if (!canEdit) return;
            const currentSnap = state.venuePlan?.gridSnapMeters ?? 1.0;
            const nextSnap = currentSnap > 0 ? 0.0 : 1.0;
            try {
                const updated = await apiPut(`/api/events/${eventId}/venue/mode`, {
                    workspaceMode: state.venuePlan?.workspaceMode || 'INTERACTIVE_MAP',
                    sampleMapKey: state.venuePlan?.sampleMapKey || 'campus-quadrangle',
                    gridSnapMeters: nextSnap
                });
                syncFromState(updated);
                setToast(`Grid snap ${nextSnap > 0 ? 'enabled (1.0 m)' : 'disabled'}.`);
            } catch (err) {
                setErrorToast(err.message);
            }
        });

        document.getElementById('modeInteractiveBtn')?.addEventListener('click', () => switchWorkspaceMode('INTERACTIVE_MAP'));
        document.getElementById('modeBlueprintBtn')?.addEventListener('click', () => switchWorkspaceMode('BLUEPRINT_UPLOAD'));

        document.getElementById('loadSampleMapBtn')?.addEventListener('click', async () => {
            if (!canEdit) return;
            const select = document.getElementById('sampleMapSelect');
            if (!select) return;
            try {
                const updated = await apiPut(`/api/events/${eventId}/venue/mode`, {
                    workspaceMode: 'INTERACTIVE_MAP',
                    sampleMapKey: select.value
                });
                syncFromState(updated, true);
                setToast('Loaded vector site map.');
            } catch (err) {
                setErrorToast(err.message);
            }
        });

        document.getElementById('useMapPerimeterBtn')?.addEventListener('click', async () => {
            if (!canEdit) return;
            const fc = state.vectorMapGeoJson;
            const boundaryFeat = fc && Array.isArray(fc.features)
                ? fc.features.find(f => f.properties && f.properties.role === 'outer_boundary' && f.geometry?.type === 'Polygon')
                : null;
            if (!boundaryFeat) {
                setErrorToast('Current map has no built-in perimeter feature. Use Draw Boundary on the left tool rail.');
                return;
            }
            const pts = boundaryFeat.geometry.coordinates[0].slice(0, -1).map(c => ({ x: c[0], y: c[1] }));
            await executeBoundaryMutation(pts, 'METERS');
            fitWorkspaceToContent();
        });

        document.getElementById('clearBoundaryBtn')?.addEventListener('click', async () => {
            if (!canEdit || !state.venuePlan?.boundaryWkt) return;
            pushUndoSnapshot();
            try {
                const updated = await apiPut(`/api/events/${eventId}/venue/boundary`, { clear: true });
                selectedTarget = null;
                syncFromState(updated);
                setToast('Outer venue boundary cleared.');
            } catch (err) {
                undoStack.pop();
                setErrorToast(err.message);
            }
        });

        document.getElementById('uploadVectorBtn')?.addEventListener('click', async () => {
            if (!canEdit) return;
            const input = document.getElementById('vectorMapFileInput');
            if (!input || !input.files || input.files.length === 0) {
                setErrorToast('Select a .geojson, .json, or .svg file to import.');
                return;
            }
            const fd = new FormData();
            fd.append('file', input.files[0]);
            const widthInput = document.getElementById('vectorTargetWidth');
            if (widthInput && widthInput.value) {
                fd.append('widthMeters', widthInput.value);
            }
            try {
                const updated = await apiUpload(`/api/events/${eventId}/venue/vector-map`, fd);
                input.value = '';
                syncFromState(updated, true);
                setToast('Custom vector site map imported.');
            } catch (err) {
                setErrorToast(err.message);
            }
        });

        document.getElementById('uploadBlueprintBtn')?.addEventListener('click', async () => {
            if (!canEdit) return;
            const input = document.getElementById('blueprintFileInput');
            if (!input || !input.files || input.files.length === 0) {
                setErrorToast('Select a PNG, JPG, WEBP, or SVG blueprint image first.');
                return;
            }
            const fd = new FormData();
            fd.append('file', input.files[0]);
            try {
                const updated = await apiUpload(`/api/events/${eventId}/venue/blueprint`, fd);
                input.value = '';
                syncFromState(updated, true);
                setToast('Blueprint uploaded. Pick Primary Calibration Line to set scale.');
            } catch (err) {
                setErrorToast(err.message);
            }
        });

        document.getElementById('blueprintOpacityInput')?.addEventListener('input', e => {
            blueprintOpacity = Number(e.target.value) / 100.0;
            core.requestRender();
        });

        document.getElementById('pickPrimaryCalBtn')?.addEventListener('click', () => setActiveTool('CALIBRATE_PRIMARY'));
        document.getElementById('pickVerifyCalBtn')?.addEventListener('click', () => setActiveTool('CALIBRATE_VERIFY'));

        document.getElementById('applyCalibrationBtn')?.addEventListener('click', async () => {
            if (!canEdit) return;
            const payload = buildCalibrationPayload();
            if (!payload) return;
            try {
                const updated = await apiPost(`/api/events/${eventId}/venue/calibration`, payload);
                setActiveTool('SELECT');
                syncFromState(updated, true);
                setToast('Scale calibration saved.');
            } catch (err) {
                setErrorToast(err.message);
            }
        });

        document.getElementById('updateSelectedExclBtn')?.addEventListener('click', async () => {
            if (!canEdit || !selectedTarget || selectedTarget.kind !== 'exclusion') return;
            const existing = (state.exclusions || []).find(e => e.id === selectedTarget.id);
            if (!existing) return;
            const label = document.getElementById('selectedExclLabel')?.value?.trim();
            const type = document.getElementById('selectedExclType')?.value;
            const notes = document.getElementById('selectedExclNotes')?.value?.trim() || null;
            if (!label) {
                setErrorToast('Exclusion label is required.');
                return;
            }
            pushUndoSnapshot();
            try {
                const updated = await apiPut(`/api/events/${eventId}/venue/exclusions/${existing.id}`, {
                    label,
                    exclusionType: type,
                    notes,
                    polygonWkt: existing.polygonWkt
                });
                syncFromState(updated);
                setToast('Exclusion updated.');
            } catch (err) {
                undoStack.pop();
                setErrorToast(err.message);
            }
        });

        document.getElementById('deleteSelectedExclBtn')?.addEventListener('click', async () => {
            if (!canEdit || !selectedTarget || selectedTarget.kind !== 'exclusion') return;
            pushUndoSnapshot();
            try {
                const updated = await apiDelete(`/api/events/${eventId}/venue/exclusions/${selectedTarget.id}`);
                selectedTarget = null;
                syncFromState(updated);
                setToast('Exclusion deleted.');
            } catch (err) {
                undoStack.pop();
                setErrorToast(err.message);
            }
        });

        document.getElementById('undoBtn')?.addEventListener('click', performUndo);
        document.getElementById('redoBtn')?.addEventListener('click', performRedo);
        document.getElementById('saveWorkspaceBtn')?.addEventListener('click', performExplicitSave);

        document.addEventListener('keydown', e => {
            if (isTypingElement(document.activeElement)) return;
            if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !e.shiftKey) {
                e.preventDefault();
                performUndo();
            } else if ((e.ctrlKey || e.metaKey) && (e.key.toLowerCase() === 'y' || (e.shiftKey && e.key.toLowerCase() === 'z'))) {
                e.preventDefault();
                performRedo();
            } else if (e.key === 'Escape') {
                cancelActiveDrawing();
            } else if (e.key === 'Backspace' && draftPoints.length > 0) {
                e.preventDefault();
                draftPoints.pop();
                core.requestRender();
            } else if (e.key === 'Enter' && draftPoints.length >= 3) {
                e.preventDefault();
                finishPolygonTrace();
            }
        });
    }

    function bindCanvasInteractions() {
        canvasEl.addEventListener('wheel', e => {
            e.preventDefault();
            const pos = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const factor = e.deltaY < 0 ? 1.12 : 0.89;
            core.zoomAt(factor, pos.x, pos.y);
        }, { passive: false });

        canvasEl.addEventListener('mousedown', e => {
            const pos = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const rawWorld = core.screenToWorld(pos.x, pos.y);

            if (e.button === 1 || (e.button === 0 && e.shiftKey)) {
                dragState = { type: 'pan', lastX: e.clientX, lastY: e.clientY };
                return;
            }

            if (e.button !== 0) return;

            if (activeTool === 'SELECT') {
                if (canEdit) {
                    const hitVertex = findSelectedVertexAtScreen(pos.x, pos.y);
                    if (hitVertex) {
                        pendingLayoutBeforeDrag = captureLayoutSnapshot();
                        dragState = { type: 'vertex', ...hitVertex };
                        return;
                    }
                }
                const clickedTarget = findPolygonAtWorld(rawWorld);
                if (clickedTarget) {
                    selectedTarget = clickedTarget;
                    updateSelectionInspector();
                    core.requestRender();
                } else {
                    selectedTarget = null;
                    updateSelectionInspector();
                    dragState = { type: 'pan', lastX: e.clientX, lastY: e.clientY };
                    core.requestRender();
                }
            }
        });

        window.addEventListener('mousemove', e => {
            const pos = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const rawWorld = core.screenToWorld(pos.x, pos.y);
            const snapped = core.snapWorld(rawWorld.x, rawWorld.y);
            hoverWorld = (activeTool === 'DRAW_BOUNDARY' || activeTool === 'DRAW_EXCLUSION') ? snapped : rawWorld;
            updateCursorReadout(hoverWorld);

            if (dragState) {
                if (dragState.type === 'pan') {
                    core.panBy(e.clientX - dragState.lastX, e.clientY - dragState.lastY);
                    dragState.lastX = e.clientX;
                    dragState.lastY = e.clientY;
                } else if (dragState.type === 'vertex') {
                    const moved = core.snapWorld(rawWorld.x, rawWorld.y);
                    updateVertexInState(dragState.kind, dragState.id, dragState.vertexIndex, moved);
                    core.requestRender();
                }
                return;
            }

            if (draftPoints.length > 0 || calDraftStart) {
                core.requestRender();
            }
        });

        window.addEventListener('mouseup', async () => {
            if (!dragState) return;
            const finishedDrag = dragState;
            dragState = null;
            if (finishedDrag.type === 'vertex' && pendingLayoutBeforeDrag) {
                const prevSnapshot = pendingLayoutBeforeDrag;
                pendingLayoutBeforeDrag = null;
                await commitDraggedGeometry(finishedDrag, prevSnapshot);
            }
        });

        canvasEl.addEventListener('click', e => {
            if (e.shiftKey || !canEdit) return;
            const pos = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const rawWorld = core.screenToWorld(pos.x, pos.y);

            if (activeTool === 'CALIBRATE_PRIMARY' || activeTool === 'CALIBRATE_VERIFY') {
                handleCalibrationClick(rawWorld);
                return;
            }

            if (activeTool === 'DRAW_BOUNDARY' || activeTool === 'DRAW_EXCLUSION') {
                const snapped = core.snapWorld(rawWorld.x, rawWorld.y);
                if (draftPoints.length >= 3) {
                    const firstScreen = core.worldToScreen(draftPoints[0].x, draftPoints[0].y);
                    if (Math.hypot(pos.x - firstScreen.x, pos.y - firstScreen.y) <= 10) {
                        finishPolygonTrace();
                        return;
                    }
                }
                const last = draftPoints[draftPoints.length - 1];
                if (!last || Math.hypot(snapped.x - last.x, snapped.y - last.y) > 0.05) {
                    draftPoints.push(snapped);
                }
                core.requestRender();
            }
        });

        canvasEl.addEventListener('dblclick', e => {
            if (!canEdit) return;
            if ((activeTool === 'DRAW_BOUNDARY' || activeTool === 'DRAW_EXCLUSION') && draftPoints.length >= 3) {
                e.preventDefault();
                finishPolygonTrace();
            }
        });
    }

    function handleCalibrationClick(worldPt) {
        if (!state.venuePlan?.hasBlueprint) {
            setErrorToast('Upload a blueprint image first.');
            return;
        }
        if (!calDraftStart) {
            calDraftStart = worldPt;
            setHint(activeTool === 'CALIBRATE_PRIMARY'
                ? 'Click second landmark endpoint for Primary Calibration Line'
                : 'Click second landmark endpoint for Verification Line');
            core.requestRender();
            return;
        }
        const p1Px = worldToImagePixel(calDraftStart.x, calDraftStart.y);
        const p2Px = worldToImagePixel(worldPt.x, worldPt.y);
        const pxLen = Math.hypot(p2Px.px - p1Px.px, p2Px.py - p1Px.py);
        if (pxLen < 2) {
            setErrorToast('Calibration endpoints must be at least 2 pixels apart.');
            return;
        }

        if (activeTool === 'CALIBRATE_PRIMARY') {
            setInputValue('calPrimaryX1', p1Px.px.toFixed(1));
            setInputValue('calPrimaryY1', p1Px.py.toFixed(1));
            setInputValue('calPrimaryX2', p2Px.px.toFixed(1));
            setInputValue('calPrimaryY2', p2Px.py.toFixed(1));
            activeCalPrimary = { p1: calDraftStart, p2: worldPt, pxLen, realM: Number(document.getElementById('calPrimaryDist')?.value || 0) || null };
            setText('calPrimaryReadout', `${pxLen.toFixed(1)} px — enter real distance & click Apply`);
            document.getElementById('calPrimaryDist')?.focus();
        } else {
            setInputValue('calVerifyX1', p1Px.px.toFixed(1));
            setInputValue('calVerifyY1', p1Px.py.toFixed(1));
            setInputValue('calVerifyX2', p2Px.px.toFixed(1));
            setInputValue('calVerifyY2', p2Px.py.toFixed(1));
            activeCalVerify = { p1: calDraftStart, p2: worldPt, pxLen, realM: Number(document.getElementById('calVerifyDist')?.value || 0) || null };
            setText('calVerifyReadout', `${pxLen.toFixed(1)} px — enter real distance & click Apply`);
            document.getElementById('calVerifyDist')?.focus();
        }
        calDraftStart = null;
        setActiveTool('SELECT');
        core.requestRender();
    }

    async function finishPolygonTrace() {
        if (draftPoints.length < 3) {
            setErrorToast('A polygon requires at least 3 distinct points.');
            return;
        }
        const plan = state.venuePlan || {};
        if (plan.workspaceMode === 'BLUEPRINT_UPLOAD' && (!plan.pixelsPerMeter || plan.pixelsPerMeter <= 0)) {
            setErrorToast('Calibrate blueprint scale before tracing boundaries or exclusions.');
            cancelActiveDrawing();
            return;
        }
        const pts = draftPoints.slice();
        const modeTool = activeTool;
        draftPoints = [];
        setActiveTool('SELECT');

        if (modeTool === 'DRAW_BOUNDARY') {
            await executeBoundaryMutation(pts, 'METERS');
        } else if (modeTool === 'DRAW_EXCLUSION') {
            await executeCreateExclusion(pts);
        }
    }

    async function executeBoundaryMutation(points, coordinateSpace) {
        pushUndoSnapshot();
        try {
            const updated = await apiPut(`/api/events/${eventId}/venue/boundary`, {
                points,
                coordinateSpace,
                clear: false
            });
            selectedTarget = { kind: 'boundary' };
            syncFromState(updated);
            setToast('Outer venue boundary saved.');
        } catch (err) {
            undoStack.pop();
            updateUndoRedoButtons();
            setErrorToast(err.message);
        }
    }

    async function executeCreateExclusion(points) {
        const typeSelect = document.getElementById('newExclusionType');
        const labelInput = document.getElementById('newExclusionLabel');
        const notesInput = document.getElementById('newExclusionNotes');
        const exType = typeSelect ? typeSelect.value : 'BUILDING';
        const defaultLabel = `${typeSelect?.selectedOptions?.[0]?.textContent?.split('/')[0]?.trim() || 'Exclusion'} ${(state.exclusions?.length || 0) + 1}`;
        const label = (labelInput && labelInput.value.trim()) ? labelInput.value.trim() : defaultLabel;
        const notes = notesInput && notesInput.value.trim() ? notesInput.value.trim() : null;

        pushUndoSnapshot();
        try {
            const updated = await apiPost(`/api/events/${eventId}/venue/exclusions`, {
                label,
                exclusionType: exType,
                notes,
                points,
                coordinateSpace: 'METERS'
            });
            if (labelInput) labelInput.value = '';
            if (notesInput) notesInput.value = '';
            const created = (updated.exclusions || []).slice(-1)[0];
            selectedTarget = created ? { kind: 'exclusion', id: created.id } : null;
            syncFromState(updated);
            setToast(`Added exclusion "${label}".`);
        } catch (err) {
            undoStack.pop();
            updateUndoRedoButtons();
            setErrorToast(err.message);
        }
    }

    async function commitDraggedGeometry(finishedDrag, prevSnapshot) {
        const plan = state.venuePlan || {};
        undoStack.push(prevSnapshot);
        redoStack.length = 0;
        try {
            if (finishedDrag.kind === 'boundary') {
                const updated = await apiPut(`/api/events/${eventId}/venue/boundary`, {
                    points: plan.boundaryPoints,
                    coordinateSpace: 'METERS',
                    clear: false
                });
                syncFromState(updated);
            } else {
                const ex = (state.exclusions || []).find(item => item.id === finishedDrag.id);
                if (ex) {
                    const updated = await apiPut(`/api/events/${eventId}/venue/exclusions/${ex.id}`, {
                        label: ex.label,
                        exclusionType: ex.exclusionType,
                        notes: ex.notes,
                        points: ex.points,
                        coordinateSpace: 'METERS'
                    });
                    syncFromState(updated);
                }
            }
            setToast('Polygon vertex updated.');
        } catch (err) {
            undoStack.pop();
            await syncSnapshotWithServer(prevSnapshot);
            setErrorToast(err.message);
        }
    }

    async function switchWorkspaceMode(nextMode) {
        if (!canEdit) return;
        try {
            const updated = await apiPut(`/api/events/${eventId}/venue/mode`, {
                workspaceMode: nextMode,
                sampleMapKey: state.venuePlan?.sampleMapKey || 'campus-quadrangle'
            });
            cancelActiveDrawing();
            syncFromState(updated, true);
            setToast(`Switched to ${nextMode === 'BLUEPRINT_UPLOAD' ? 'Blueprint & Photo' : 'Interactive Map'} mode.`);
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    function buildCalibrationPayload() {
        const x1 = parseFloat(document.getElementById('calPrimaryX1')?.value);
        const y1 = parseFloat(document.getElementById('calPrimaryY1')?.value);
        const x2 = parseFloat(document.getElementById('calPrimaryX2')?.value);
        const y2 = parseFloat(document.getElementById('calPrimaryY2')?.value);
        const d1 = parseFloat(document.getElementById('calPrimaryDist')?.value);
        if (![x1, y1, x2, y2, d1].every(Number.isFinite) || d1 <= 0) {
            setErrorToast('Pick the Primary Calibration Line on the canvas and enter a positive real-world distance (m).');
            return null;
        }
        const vx1 = parseFloat(document.getElementById('calVerifyX1')?.value);
        const vy1 = parseFloat(document.getElementById('calVerifyY1')?.value);
        const vx2 = parseFloat(document.getElementById('calVerifyX2')?.value);
        const vy2 = parseFloat(document.getElementById('calVerifyY2')?.value);
        const vd = parseFloat(document.getElementById('calVerifyDist')?.value);
        const hasVerify = [vx1, vy1, vx2, vy2, vd].some(Number.isFinite);
        if (hasVerify && (![vx1, vy1, vx2, vy2, vd].every(Number.isFinite) || vd <= 0)) {
            setErrorToast('Complete all Verification Line endpoints and enter a positive verification distance (m), or clear them.');
            return null;
        }
        const ortho = Boolean(document.getElementById('calOrthoConfirmed')?.checked);
        return {
            primaryX1Px: x1,
            primaryY1Px: y1,
            primaryX2Px: x2,
            primaryY2Px: y2,
            primaryRealDistanceMeters: d1,
            verifyX1Px: hasVerify ? vx1 : null,
            verifyY1Px: hasVerify ? vy1 : null,
            verifyX2Px: hasVerify ? vx2 : null,
            verifyY2Px: hasVerify ? vy2 : null,
            verifyRealDistanceMeters: hasVerify ? vd : null,
            orthographicConfirmed: ortho,
            rescaleExistingGeometry: true
        };
    }

    function captureLayoutSnapshot() {
        return {
            boundaryWkt: state.venuePlan?.boundaryWkt || null,
            exclusions: (state.exclusions || []).map(ex => ({
                label: ex.label,
                exclusionType: ex.exclusionType,
                notes: ex.notes,
                polygonWkt: ex.polygonWkt
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
        setToast('Undid last layout change.');
    }

    async function performRedo() {
        if (!canEdit || redoStack.length === 0) return;
        undoStack.push(captureLayoutSnapshot());
        const next = redoStack.pop();
        await syncSnapshotWithServer(next);
        setToast('Redid layout change.');
    }

    async function performExplicitSave() {
        if (!canEdit) return;
        await syncSnapshotWithServer(captureLayoutSnapshot());
        setToast('All venue geometry and space calculations saved.');
    }

    async function syncSnapshotWithServer(snapshot) {
        try {
            const updated = await apiPut(`/api/events/${eventId}/venue/layout`, snapshot);
            selectedTarget = null;
            syncFromState(updated);
        } catch (err) {
            setErrorToast(err.message);
        }
    }

    function setActiveTool(tool) {
        activeTool = tool;
        if (tool !== 'DRAW_BOUNDARY' && tool !== 'DRAW_EXCLUSION') {
            draftPoints = [];
        }
        if (tool !== 'CALIBRATE_PRIMARY' && tool !== 'CALIBRATE_VERIFY') {
            calDraftStart = null;
        }
        document.querySelectorAll('.tool-rail-btn[data-tool]').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tool === tool);
        });
        updateHintForTool(tool);
        updateStatusBar();
        core.requestRender();
    }

    function cancelActiveDrawing() {
        draftPoints = [];
        calDraftStart = null;
        setActiveTool('SELECT');
    }

    function updateHintForTool(tool) {
        if (!hintBar || !hintText) return;
        const hints = {
            DRAW_BOUNDARY: 'Click boundary vertices on canvas. Click first point, double-click, or press Enter to close.',
            DRAW_EXCLUSION: 'Click exclusion polygon vertices. Click first point, double-click, or press Enter to finish.',
            CALIBRATE_PRIMARY: 'Click start point of a known real-world span on the blueprint.',
            CALIBRATE_VERIFY: 'Click start point of a perpendicular verification span on the blueprint.'
        };
        if (hints[tool]) {
            hintText.textContent = hints[tool];
            hintBar.style.display = 'flex';
        } else {
            hintBar.style.display = 'none';
        }
    }

    function updateModeButtons(plan) {
        const isBlueprint = plan.workspaceMode === 'BLUEPRINT_UPLOAD';
        document.getElementById('modeInteractiveBtn')?.classList.toggle('active', !isBlueprint);
        document.getElementById('modeBlueprintBtn')?.classList.toggle('active', isBlueprint);

        const mapPanel = document.getElementById('interactiveMapSection');
        const bpPanel = document.getElementById('blueprintUploadSection');
        if (mapPanel) mapPanel.style.display = isBlueprint ? 'none' : 'flex';
        if (bpPanel) bpPanel.style.display = isBlueprint ? 'flex' : 'none';

        const mapSel = document.getElementById('sampleMapSelect');
        if (mapSel && plan.sampleMapKey && Array.from(mapSel.options).some(o => o.value === plan.sampleMapKey)) {
            mapSel.value = plan.sampleMapKey;
        }
        setText('blueprintFileMeta', plan.hasBlueprint
            ? `${plan.blueprintOriginalFilename} (${plan.blueprintWidthPx} × ${plan.blueprintHeightPx} px)`
            : 'No blueprint uploaded yet');
    }

    function updateScalePill(plan) {
        const pill = document.getElementById('scaleStatusPill');
        if (!pill) return;
        if (plan.workspaceMode === 'INTERACTIVE_MAP') {
            pill.textContent = 'Scale: 1.00 m Grid';
            pill.className = 'scale-pill mono';
            return;
        }
        if (!plan.hasBlueprint || !plan.pixelsPerMeter) {
            pill.textContent = 'Scale: Uncalibrated';
            pill.className = 'scale-pill mono';
        } else if (plan.distortionWarning) {
            pill.textContent = `Scale: ${plan.pixelsPerMeter.toFixed(2)} px/m (Skew Alert)`;
            pill.className = 'scale-pill mono badge-critical';
        } else if (plan.scaleVerified) {
            pill.textContent = `Scale: ${plan.pixelsPerMeter.toFixed(2)} px/m (Verified)`;
            pill.className = 'scale-pill mono badge-verified';
        } else {
            pill.textContent = `Scale: ${plan.pixelsPerMeter.toFixed(2)} px/m`;
            pill.className = 'scale-pill mono';
        }
    }

    function updateToolAvailability(plan) {
        const isBlueprint = plan.workspaceMode === 'BLUEPRINT_UPLOAD';
        const needsCalibration = isBlueprint && !plan.pixelsPerMeter;
        const primaryBtn = document.querySelector('.tool-rail-btn[data-tool="CALIBRATE_PRIMARY"]');
        const verifyBtn = document.querySelector('.tool-rail-btn[data-tool="CALIBRATE_VERIFY"]');
        const boundaryBtn = document.querySelector('.tool-rail-btn[data-tool="DRAW_BOUNDARY"]');
        const exclBtn = document.querySelector('.tool-rail-btn[data-tool="DRAW_EXCLUSION"]');

        if (primaryBtn) primaryBtn.disabled = !canEdit || !isBlueprint || !plan.hasBlueprint;
        if (verifyBtn) verifyBtn.disabled = !canEdit || !isBlueprint || !plan.hasBlueprint;
        if (boundaryBtn) boundaryBtn.disabled = !canEdit || needsCalibration;
        if (exclBtn) exclBtn.disabled = !canEdit || needsCalibration;
    }

    function updateSnapButton(snapMeters) {
        const btn = document.getElementById('snapToggleBtn');
        if (btn) {
            btn.classList.toggle('active', snapMeters > 0);
        }
    }

    function updateBreakdownSummary(breakdown, exclusions) {
        const b = breakdown || {};
        setText('sumGrossArea', formatSqm(b.grossAreaSqm));
        setText('sumRestrictedArea', formatSqm(b.restrictedAreaSqm));
        setText('sumCorridorsArea', formatSqm(b.corridorsAreaSqm));
        setText('sumExcludedTotal', formatSqm(b.excludedTotalAreaSqm));
        setText('sumUsableArea', formatSqm(b.usableAreaSqm));
        setText('sumPerimeter', b.hasBoundary ? `${Number(b.boundaryPerimeterM || 0).toFixed(2)} m` : '—');
        setText('sumUsablePct', b.hasBoundary ? `${Number(b.usablePercent || 0).toFixed(1)}% of Gross` : 'Draw boundary');

        const segUsable = document.getElementById('segUsable');
        const segRestricted = document.getElementById('segRestricted');
        const segCorridor = document.getElementById('segCorridor');
        if (segUsable && segRestricted && segCorridor) {
            segUsable.style.width = `${Math.max(0, Math.min(100, b.usablePercent || 0))}%`;
            segRestricted.style.width = `${Math.max(0, Math.min(100, b.restrictedPercent || 0))}%`;
            segCorridor.style.width = `${Math.max(0, Math.min(100, b.corridorsPercent || 0))}%`;
        }

        const dedupBanner = document.getElementById('overlapDedupNotice');
        if (dedupBanner) {
            if (b.overlapDeduplicatedSqm && b.overlapDeduplicatedSqm > 0.01) {
                dedupBanner.style.display = 'block';
                dedupBanner.textContent = `Overlap deduplication active: ${b.overlapDeduplicatedSqm.toFixed(2)} m² shared overlap merged via JTS union (zero double-counting).`;
            } else {
                dedupBanner.style.display = 'none';
            }
        }

        const listEl = document.getElementById('exclusionsList');
        if (!listEl) return;
        if (!exclusions || exclusions.length === 0) {
            listEl.innerHTML = '<div class="text-muted" style="font-size:12px;">No exclusions drawn yet.</div>';
            return;
        }
        listEl.innerHTML = exclusions.map(ex => {
            const isSel = selectedTarget && selectedTarget.kind === 'exclusion' && selectedTarget.id === ex.id;
            return `<div class="exclusion-row ${isSel ? 'selected' : ''}" data-excl-id="${ex.id}">
                <div style="min-width:0;">
                    <div style="font-size:12px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${escapeHtml(ex.label)}</div>
                    <div class="text-muted" style="font-size:11px;">${escapeHtml(ex.exclusionTypeName)}</div>
                </div>
                <span class="mono" style="font-size:12px;white-space:nowrap;">${Number(ex.clippedAreaSqm || ex.rawAreaSqm).toFixed(1)} m²</span>
            </div>`;
        }).join('');

        listEl.querySelectorAll('.exclusion-row[data-excl-id]').forEach(row => {
            row.addEventListener('click', () => {
                const id = Number(row.dataset.exclId);
                const target = (state.exclusions || []).find(item => item.id === id);
                selectedTarget = { kind: 'exclusion', id };
                updateSelectionInspector();
                updateBreakdownSummary(state.areaBreakdown, state.exclusions);
                if (target && target.centroid) {
                    core.panTo(target.centroid.x, target.centroid.y);
                } else {
                    core.requestRender();
                }
            });
        });
    }

    function updateFindingsPanel(findings) {
        const badgeCount = document.getElementById('findingsCountBadge');
        if (badgeCount) {
            badgeCount.textContent = String(findings.length);
        }
        const listEl = document.getElementById('findingsList');
        if (!listEl) return;
        if (findings.length === 0) {
            listEl.innerHTML = '<div class="banner banner-info">No open geometric measurement warnings on the current site plan.</div>';
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

    function updateSelectionInspector() {
        const panel = document.getElementById('selectedPolygonSection');
        if (!panel) return;
        if (!selectedTarget || selectedTarget.kind !== 'exclusion') {
            panel.style.display = 'none';
            return;
        }
        const ex = (state.exclusions || []).find(e => e.id === selectedTarget.id);
        if (!ex) {
            panel.style.display = 'none';
            return;
        }
        panel.style.display = 'flex';
        setInputValue('selectedExclLabel', ex.label);
        setInputValue('selectedExclType', ex.exclusionType);
        setInputValue('selectedExclNotes', ex.notes || '');
        setText('selectedExclArea', `${Number(ex.clippedAreaSqm || ex.rawAreaSqm).toFixed(2)} m²`);
    }

    function updateEmptyOverlay(plan, breakdown) {
        if (!emptyOverlay || !emptyOverlayText) return;
        if (plan.workspaceMode === 'BLUEPRINT_UPLOAD' && !plan.hasBlueprint) {
            emptyOverlayText.textContent = 'Upload an aerial photo or orthographic blueprint in the right inspector to begin.';
            emptyOverlay.style.display = 'flex';
        } else if (plan.workspaceMode === 'BLUEPRINT_UPLOAD' && !plan.pixelsPerMeter) {
            emptyOverlayText.textContent = 'Blueprint loaded — calibrate scale in the right inspector before drawing polygons.';
            emptyOverlay.style.display = 'flex';
        } else if (!breakdown || !breakdown.hasBoundary) {
            emptyOverlayText.textContent = 'No boundary drawn yet — use Draw Boundary on the left rail or Trace Map Perimeter.';
            emptyOverlay.style.display = 'flex';
        } else {
            emptyOverlay.style.display = 'none';
        }
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

    function updateCursorReadout(worldPt) {
        const isBlueprintUncal = state.venuePlan?.workspaceMode === 'BLUEPRINT_UPLOAD' && !state.venuePlan?.pixelsPerMeter;
        const unit = isBlueprintUncal ? 'u' : 'm';
        setText('cursorCoordsReadout', `X: ${worldPt.x.toFixed(1)} ${unit}, Y: ${worldPt.y.toFixed(1)} ${unit}`);
    }

    function updateStatusBar() {
        const modeLabel = state.venuePlan?.workspaceMode === 'BLUEPRINT_UPLOAD' ? 'Blueprint Upload' : 'Interactive Map';
        const snap = (state.venuePlan?.gridSnapMeters ?? 1.0) > 0 ? `${state.venuePlan.gridSnapMeters}m Snap` : 'Free Snap';
        setText('statusLayerText', `Site Plan · ${modeLabel} · ${activeTool} · ${snap}`);
    }

    function findSelectedVertexAtScreen(sx, sy) {
        if (!selectedTarget) return null;
        const pts = selectedTarget.kind === 'boundary'
            ? (state.venuePlan?.boundaryPoints || [])
            : ((state.exclusions || []).find(e => e.id === selectedTarget.id)?.points || []);
        for (let i = 0; i < pts.length; i++) {
            const p = core.worldToScreen(pts[i].x, pts[i].y);
            if (Math.hypot(sx - p.x, sy - p.y) <= 8) {
                return { kind: selectedTarget.kind, id: selectedTarget.id || null, vertexIndex: i };
            }
        }
        return null;
    }

    function updateVertexInState(kind, id, index, newPoint) {
        if (kind === 'boundary' && state.venuePlan?.boundaryPoints?.[index]) {
            state.venuePlan.boundaryPoints[index] = newPoint;
        } else if (kind === 'exclusion') {
            const ex = (state.exclusions || []).find(e => e.id === id);
            if (ex && ex.points?.[index]) {
                ex.points[index] = newPoint;
            }
        }
    }

    function findPolygonAtWorld(pt) {
        const exclusions = state.exclusions || [];
        for (let i = exclusions.length - 1; i >= 0; i--) {
            if (pointInPolygon(pt, exclusions[i].points)) {
                return { kind: 'exclusion', id: exclusions[i].id };
            }
        }
        if (state.venuePlan?.boundaryPoints && pointInPolygon(pt, state.venuePlan.boundaryPoints)) {
            return { kind: 'boundary' };
        }
        return null;
    }

    function pointInPolygon(pt, ring) {
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
        el.textContent = msg;
    }

    function setErrorToast(msg) {
        const el = document.getElementById('workspaceMessage');
        if (!el) return;
        el.className = '';
        el.style.color = '#B91C1C';
        el.textContent = msg;
    }

    function setHint(msg) {
        if (hintBar && hintText) {
            hintText.textContent = msg;
            hintBar.style.display = 'flex';
        }
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
    const clean = hex.replace('#', '');
    if (clean.length !== 6) return `rgba(100, 116, 139, ${alpha})`;
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
