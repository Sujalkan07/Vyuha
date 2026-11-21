import { apiGet, apiPost, apiDelete } from './api-client.js';
import { VyuhaCanvasCore } from './canvas-core.js';

const root = document.getElementById('simWorkspaceRoot');
if (root) {
    const eventId = root.dataset.eventId;
    const canManageSnapshots = root.dataset.canManageSnapshots === 'true';
    let workspace = JSON.parse(root.dataset.initialState || '{}');

    // UI Elements
    const modePlaybackBtn = document.getElementById('modePlaybackBtn');
    const modeCompareBtn = document.getElementById('modeCompareBtn');
    const playbackStage = document.getElementById('playbackStage');
    const compareStage = document.getElementById('compareStage');
    const singleRunSection = document.getElementById('singleRunSection');
    const compareSetupSection = document.getElementById('compareSetupSection');
    const inspectorRunBtn = document.getElementById('inspectorRunBtn');
    const inspectorCompareBtn = document.getElementById('inspectorCompareBtn');
    const barRunSimBtn = document.getElementById('barRunSimBtn');
    const barScenarioSelect = document.getElementById('barScenarioSelect');
    const scenarioSelect = document.getElementById('scenarioSelect');
    const scenarioDescText = document.getElementById('scenarioDescText');
    const targetEdgeGroup = document.getElementById('targetEdgeGroup');
    const targetEdgeSelect = document.getElementById('targetEdgeSelect');
    const targetExitGroup = document.getElementById('targetExitGroup');
    const targetExitSelect = document.getElementById('targetExitSelect');
    const disruptionTimeGroup = document.getElementById('disruptionTimeGroup');
    const disruptionStartInput = document.getElementById('disruptionStartInput');

    const runSnapshotSelect = document.getElementById('runSnapshotSelect');
    const beforeSnapshotSelect = document.getElementById('beforeSnapshotSelect');
    const afterSnapshotSelect = document.getElementById('afterSnapshotSelect');
    const seedInput = document.getElementById('seedInput');
    const agentCountInput = document.getElementById('agentCountInput');
    const durationInput = document.getElementById('durationInput');
    const densityThresholdInput = document.getElementById('densityThresholdInput');

    const playPauseBtn = document.getElementById('playPauseBtn');
    const railPlayPauseBtn = document.getElementById('railPlayPauseBtn');
    const resetPlaybackBtn = document.getElementById('resetPlaybackBtn');
    const railResetBtn = document.getElementById('railResetBtn');
    const simScrubber = document.getElementById('simScrubber');
    const simClockReadout = document.getElementById('simClockReadout');
    const telActive = document.getElementById('telActive');
    const telQueue = document.getElementById('telQueue');
    const telCongested = document.getElementById('telCongested');
    const telExited = document.getElementById('telExited');
    const telPeakDensity = document.getElementById('telPeakDensity');

    const simStatusPill = document.getElementById('simStatusPill');
    const snapshotCountBadge = document.getElementById('snapshotCountBadge');
    const runsCountLabel = document.getElementById('runsCountLabel');
    const runsList = document.getElementById('runsList');
    const snapshotsList = document.getElementById('snapshotsList');
    const metricsTableBody = document.getElementById('metricsTableBody');
    const metricsColAHeader = document.getElementById('metricsColAHeader');
    const metricsColBHeader = document.getElementById('metricsColBHeader');
    const hotspotsList = document.getElementById('hotspotsList');
    const compareNotesSection = document.getElementById('compareNotesSection');
    const compareNotesList = document.getElementById('compareNotesList');
    const parityWarningBanner = document.getElementById('parityWarningBanner');

    const cursorCoordsReadout = document.getElementById('cursorCoordsReadout');
    const statusSimText = document.getElementById('statusSimText');
    const workspaceMessage = document.getElementById('workspaceMessage');

    const toggleHeatmapBtn = document.getElementById('toggleHeatmapBtn');
    const toggleAgentsBtn = document.getElementById('toggleAgentsBtn');
    const toggleNetworkBtn = document.getElementById('toggleNetworkBtn');
    const toggleInspectorBtn = document.getElementById('toggleInspectorBtn');
    const inspectorPanel = document.querySelector('.inspector-panel');

    const snapshotDialog = document.getElementById('snapshotDialog');
    const openSnapshotDialogBtn = document.getElementById('openSnapshotDialogBtn');
    const closeSnapshotDialogBtn = document.getElementById('closeSnapshotDialogBtn');
    const cancelCaptureSnapshotBtn = document.getElementById('cancelCaptureSnapshotBtn');
    const confirmCaptureSnapshotBtn = document.getElementById('confirmCaptureSnapshotBtn');
    const modalSnapshotLabel = document.getElementById('modalSnapshotLabel');
    const modalSnapshotNotes = document.getElementById('modalSnapshotNotes');
    const inlineSnapshotLabel = document.getElementById('inlineSnapshotLabel');
    const inlineSnapshotNotes = document.getElementById('inlineSnapshotNotes');
    const inlineCaptureSnapshotBtn = document.getElementById('inlineCaptureSnapshotBtn');

    // Playback state
    let viewMode = 'PLAYBACK'; // 'PLAYBACK' | 'COMPARE'
    let showHeatmap = true;
    let showAgents = true;
    let showNetwork = true;
    let highlightPoint = null;

    let activeRun = null;
    let activeLayout = workspace.currentLayout || null;
    let activeFrames = null;

    let compareData = null;
    let beforeFrames = null;
    let afterFrames = null;

    let isPlaying = false;
    let playbackSpeed = 1.0;
    let simClockSec = 0.0;
    let lastRafTimestamp = null;
    let playbackRafId = null;

    const blueprintCache = new Map();

    // Initialize Canvases
    const mainCanvasEl = document.getElementById('simCanvas');
    const beforeCanvasEl = document.getElementById('simBeforeCanvas');
    const afterCanvasEl = document.getElementById('simAfterCanvas');

    const mainCore = new VyuhaCanvasCore(mainCanvasEl, {
        onRender: () => renderSingleScene()
    });
    const beforeCore = new VyuhaCanvasCore(beforeCanvasEl, {
        onRender: () => renderCompareScene(beforeCore, compareData?.beforeLayout, beforeFrames, compareData?.beforeRun)
    });
    const afterCore = new VyuhaCanvasCore(afterCanvasEl, {
        onRender: () => renderCompareScene(afterCore, compareData?.afterLayout, afterFrames, compareData?.afterRun)
    });

    bindPanZoom(mainCore, mainCanvasEl);
    bindPanZoom(beforeCore, beforeCanvasEl);
    bindPanZoom(afterCore, afterCanvasEl);

    function setMessage(text, isError = false) {
        workspaceMessage.textContent = text || '';
        workspaceMessage.style.color = isError ? 'var(--sev-critical)' : 'var(--text-secondary)';
    }

    function bindPanZoom(core, canvasEl) {
        let panning = false;
        let lastPx = 0;
        let lastPy = 0;

        canvasEl.addEventListener('mousedown', (e) => {
            if (e.button === 0 || e.button === 1) {
                panning = true;
                lastPx = e.clientX;
                lastPy = e.clientY;
            }
        });
        window.addEventListener('mouseup', () => {
            panning = false;
        });
        window.addEventListener('mousemove', (e) => {
            if (panning) {
                const dx = e.clientX - lastPx;
                const dy = e.clientY - lastPy;
                lastPx = e.clientX;
                lastPy = e.clientY;
                core.panBy(dx, dy);
            }
        });
        canvasEl.addEventListener('mousemove', (e) => {
            const local = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const w = core.screenToWorld(local.x, local.y);
            cursorCoordsReadout.textContent = `X: ${w.x.toFixed(1)} m, Y: ${w.y.toFixed(1)} m`;
        });
        canvasEl.addEventListener('wheel', (e) => {
            e.preventDefault();
            const local = core.getCanvasLocalCoords(e.clientX, e.clientY);
            const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12;
            core.zoomAt(factor, local.x, local.y);
        }, { passive: false });
    }

    function ensureBlueprintLoaded(layout) {
        if (!layout || !layout.hasBlueprint || !layout.blueprintUrl || !layout.pixelsPerMeter) {
            return null;
        }
        const key = layout.blueprintUrl;
        if (blueprintCache.has(key)) {
            return blueprintCache.get(key);
        }
        const img = new Image();
        img.onload = () => {
            mainCore.requestRender();
            beforeCore.requestRender();
            afterCore.requestRender();
        };
        img.src = key;
        blueprintCache.set(key, img);
        return img;
    }

    function renderLayoutScene(core, layout, frameStream, runSummary) {
        core.beginFrame();
        core.drawMetricGrid('m');

        if (!layout) {
            core.drawScaleBarAndCompass(true);
            return;
        }

        const bpImg = ensureBlueprintLoaded(layout);
        if (bpImg && bpImg.complete) {
            core.drawBlueprintImage(bpImg, layout.pixelsPerMeter, layout.blueprintWidthPx, layout.blueprintHeightPx, 0.72);
        }

        if (layout.boundaryPoints && layout.boundaryPoints.length >= 3) {
            core.drawPolygon(layout.boundaryPoints, {
                fill: bpImg ? 'rgba(255,255,255,0.12)' : '#FFFFFF',
                stroke: '#2563EB',
                lineWidth: 2
            });
        }

        if (layout.zones) {
            for (const z of layout.zones) {
                core.drawPolygonShapes(z.shapes, {
                    fill: hexToRgba(z.fillColor || '#3B82F6', 0.16),
                    stroke: z.strokeColor || '#1D4ED8',
                    lineWidth: 1.2,
                    hatched: z.hatched,
                    hatchColor: z.strokeColor,
                    label: z.zoneCode,
                    labelPoint: z.labelPoint
                });
            }
        }

        if (layout.exclusions) {
            for (const ex of layout.exclusions) {
                core.drawPolygon(ex.points, {
                    fill: hexToRgba(ex.fillColor || '#94A3B8', 0.28),
                    stroke: ex.strokeColor || '#475569',
                    lineWidth: 1.2,
                    hatched: !ex.corridor,
                    label: ex.label
                });
            }
        }

        // Density Heatmap Overlay (interpolated cell squares)
        if (showHeatmap && frameStream && frameStream.frames && frameStream.frames.length > 0) {
            const frame = sampleFrameAtClock(frameStream, simClockSec).curr;
            if (frame && frame.hotCells) {
                drawHeatmapOverlay(core, frameStream, frame.hotCells);
            }
        }

        if (showNetwork && layout.edges) {
            drawEdges(core, layout.edges, runSummary);
        }

        if (layout.elements) {
            for (const el of layout.elements) {
                if (!el.active) continue;
                const closedExit = isClosedExitElement(el, runSummary);
                core.drawPolygon(el.footprintPoints, {
                    fill: hexToRgba(closedExit ? '#B91C1C' : (el.fillColor || '#64748B'), 0.45),
                    stroke: closedExit ? '#991B1B' : (el.strokeColor || '#334155'),
                    lineWidth: closedExit ? 2.5 : 1.5,
                    dashed: closedExit ? [4, 3] : null,
                    label: el.label
                });
            }
        }

        if (showNetwork && layout.nodes) {
            drawNodes(core, layout.nodes, runSummary);
        }

        if (showAgents && frameStream && frameStream.frames && frameStream.frames.length > 0) {
            drawInterpolatedAgents(core, frameStream, simClockSec);
        }

        if (highlightPoint) {
            const sc = core.worldToScreen(highlightPoint.x, highlightPoint.y);
            const ctx = core.ctx;
            ctx.save();
            ctx.strokeStyle = '#DC2626';
            ctx.lineWidth = 2.5;
            ctx.beginPath();
            ctx.arc(sc.x, sc.y, 16, 0, Math.PI * 2);
            ctx.stroke();
            ctx.beginPath();
            ctx.arc(sc.x, sc.y, 6, 0, Math.PI * 2);
            ctx.stroke();
            ctx.restore();
        }

        core.drawScaleBarAndCompass(true);
    }

    function renderSingleScene() {
        renderLayoutScene(mainCore, activeLayout, activeFrames, activeRun);
    }

    function renderCompareScene(core, layout, frameStream, runSummary) {
        renderLayoutScene(core, layout, frameStream, runSummary);
    }

    function drawHeatmapOverlay(core, frameStream, hotCells) {
        const ctx = core.ctx;
        const threshold = frameStream.highDensityThresholdPsm || 2.0;
        const sizeM = frameStream.cellSizeM || 4.0;
        const ox = frameStream.gridOriginX || 0;
        const oy = frameStream.gridOriginY || 0;

        ctx.save();
        for (const hc of hotCells) {
            const col = hc[0];
            const row = hc[1];
            const density = hc[2];
            const wx = ox + col * sizeM;
            const wy = oy + row * sizeM;
            const p0 = core.worldToScreen(wx, wy);
            const p1 = core.worldToScreen(wx + sizeM, wy + sizeM);
            const w = p1.x - p0.x;
            const h = p1.y - p0.y;

            let fill;
            if (density >= threshold * 1.4) {
                fill = 'rgba(185, 28, 28, 0.44)';
            } else if (density >= threshold) {
                fill = 'rgba(220, 38, 38, 0.34)';
            } else if (density >= threshold * 0.65) {
                fill = 'rgba(249, 115, 22, 0.26)';
            } else {
                fill = 'rgba(234, 179, 8, 0.18)';
            }
            ctx.fillStyle = fill;
            ctx.fillRect(p0.x, p0.y, w, h);
        }
        ctx.restore();
    }

    function drawEdges(core, edges, runSummary) {
        const ctx = core.ctx;
        const cfg = runSummary?.config;
        const isBlockedScenario = cfg && cfg.scenarioType === 'BLOCKED_CORRIDOR'
            && simClockSec >= (cfg.disruptionStartSec || 0);

        ctx.save();
        for (const e of edges) {
            if (!e.active || !e.points || e.points.length < 2) continue;
            const blocked = isBlockedScenario && isTargetedEdge(e, cfg);

            ctx.beginPath();
            e.points.forEach((p, idx) => {
                const s = core.worldToScreen(p.x, p.y);
                if (idx === 0) ctx.moveTo(s.x, s.y);
                else ctx.lineTo(s.x, s.y);
            });

            ctx.strokeStyle = blocked
                ? 'rgba(185, 28, 28, 0.72)'
                : hexToRgba(e.colorHex || '#2563EB', 0.22);
            ctx.lineWidth = Math.max(3, e.widthM * core.zoom);
            ctx.lineCap = 'round';
            ctx.lineJoin = 'round';
            if (blocked) ctx.setLineDash([6, 4]);
            else ctx.setLineDash([]);
            ctx.stroke();

            ctx.strokeStyle = blocked ? '#B91C1C' : (e.colorHex || '#2563EB');
            ctx.lineWidth = 1.6;
            if (e.dashed || blocked) ctx.setLineDash([5, 4]);
            else ctx.setLineDash([]);
            ctx.stroke();
        }
        ctx.setLineDash([]);
        ctx.restore();
    }

    function drawNodes(core, nodes, runSummary) {
        const ctx = core.ctx;
        const cfg = runSummary?.config;
        const isClosedScenario = cfg && cfg.scenarioType === 'CLOSED_EXIT'
            && simClockSec >= (cfg.disruptionStartSec || 0);

        ctx.save();
        for (const n of nodes) {
            const s = core.worldToScreen(n.x, n.y);
            const closed = isClosedScenario && isTargetedExit(n, cfg);
            ctx.fillStyle = closed ? '#B91C1C' : (n.fillColor || '#3B82F6');
            ctx.strokeStyle = '#FFFFFF';
            ctx.lineWidth = 1.5;
            ctx.beginPath();
            ctx.arc(s.x, s.y, 4.5, 0, Math.PI * 2);
            ctx.fill();
            ctx.stroke();

            if (closed) {
                ctx.strokeStyle = '#B91C1C';
                ctx.lineWidth = 2;
                ctx.beginPath();
                ctx.moveTo(s.x - 7, s.y - 7);
                ctx.lineTo(s.x + 7, s.y + 7);
                ctx.moveTo(s.x + 7, s.y - 7);
                ctx.lineTo(s.x - 7, s.y + 7);
                ctx.stroke();
            }
        }
        ctx.restore();
    }

    function isTargetedEdge(edge, cfg) {
        if (!cfg) return false;
        if (cfg.targetEdgeUid) return edge.edgeUid === cfg.targetEdgeUid;
        if (cfg.targetEdgeId) return edge.id === cfg.targetEdgeId;
        return false;
    }

    function isTargetedExit(node, cfg) {
        if (!cfg) return false;
        if (cfg.targetExitNodeUid) return node.nodeUid === cfg.targetExitNodeUid;
        if (cfg.targetExitInfraId) return node.linkedInfraId === cfg.targetExitInfraId;
        return false;
    }

    function isClosedExitElement(el, runSummary) {
        const cfg = runSummary?.config;
        if (!cfg || cfg.scenarioType !== 'CLOSED_EXIT') return false;
        if (simClockSec < (cfg.disruptionStartSec || 0)) return false;
        return cfg.targetExitInfraId && el.id === cfg.targetExitInfraId;
    }

    function drawInterpolatedAgents(core, frameStream, clockSec) {
        const sample = sampleFrameAtClock(frameStream, clockSec);
        const curr = sample.curr;
        const next = sample.next;
        const alpha = sample.alpha;
        if (!curr || !curr.agents) return;

        const nextById = new Map();
        if (next && next.agents && alpha > 0.001) {
            for (const na of next.agents) {
                nextById.set(na[0], na);
            }
        }

        const ctx = core.ctx;
        const r = Math.max(2.6, Math.min(5.0, core.zoom * 0.36));
        ctx.save();
        for (const a of curr.agents) {
            let wx = a[1];
            let wy = a[2];
            const state = a[3];
            const na = nextById.get(a[0]);
            if (na) {
                wx = wx + (na[1] - wx) * alpha;
                wy = wy + (na[2] - wy) * alpha;
            }
            const sc = core.worldToScreen(wx, wy);
            ctx.fillStyle = agentColorForState(state);
            ctx.beginPath();
            ctx.arc(sc.x, sc.y, r, 0, Math.PI * 2);
            ctx.fill();
        }
        ctx.restore();
    }

    function agentColorForState(stateCode) {
        switch (stateCode) {
            case 1: return '#F59E0B'; // Queuing
            case 2: return '#DC2626'; // Congested
            case 3: return '#0F766E'; // Exiting
            case 4: return '#7F1D1D'; // Blocked
            default: return '#2563EB'; // Walking
        }
    }

    function sampleFrameAtClock(frameStream, clockSec) {
        const frames = frameStream.frames;
        if (!frames || frames.length === 0) {
            return { curr: null, next: null, alpha: 0 };
        }
        if (clockSec <= frames[0].t) {
            return { curr: frames[0], next: frames[1] || null, alpha: 0 };
        }
        const last = frames[frames.length - 1];
        if (clockSec >= last.t) {
            return { curr: last, next: null, alpha: 0 };
        }
        const step = Math.max(0.1, frameStream.sampleIntervalSec || 1.0);
        const approxIdx = Math.max(0, Math.min(frames.length - 2, Math.floor((clockSec - frames[0].t) / step)));
        let idx = approxIdx;
        while (idx < frames.length - 2 && frames[idx + 1].t <= clockSec) idx++;
        while (idx > 0 && frames[idx].t > clockSec) idx--;
        const f0 = frames[idx];
        const f1 = frames[idx + 1];
        const span = Math.max(1e-6, f1.t - f0.t);
        const alpha = Math.max(0, Math.min(1, (clockSec - f0.t) / span));
        return { curr: f0, next: f1, alpha };
    }

    function maxDurationSec() {
        if (viewMode === 'COMPARE') {
            return Math.max(
                beforeFrames?.durationSeconds || 0,
                afterFrames?.durationSeconds || 0
            );
        }
        return activeFrames?.durationSeconds || 0;
    }

    function updatePlaybackHud() {
        const totalSec = maxDurationSec();
        simScrubber.max = String(totalSec);
        simScrubber.value = String(Math.min(totalSec, Math.round(simClockSec)));
        simClockReadout.textContent = `${formatClock(simClockSec)} / ${formatClock(totalSec)}`;

        if (viewMode === 'PLAYBACK' && activeFrames && activeFrames.frames?.length) {
            const f = sampleFrameAtClock(activeFrames, simClockSec).curr;
            if (f) {
                telActive.textContent = f.active;
                telQueue.textContent = f.queuing;
                telCongested.textContent = f.congested;
                telExited.textContent = f.exited;
                telPeakDensity.textContent = Number(f.peakDensity || 0).toFixed(2);
            }
        } else if (viewMode === 'COMPARE') {
            const fb = beforeFrames?.frames?.length ? sampleFrameAtClock(beforeFrames, simClockSec).curr : null;
            const fa = afterFrames?.frames?.length ? sampleFrameAtClock(afterFrames, simClockSec).curr : null;
            document.getElementById('beforePaneLive').textContent = fb
                ? `ρ ${Number(fb.peakDensity).toFixed(2)} p/m² · Exited ${fb.exited}`
                : '—';
            document.getElementById('afterPaneLive').textContent = fa
                ? `ρ ${Number(fa.peakDensity).toFixed(2)} p/m² · Exited ${fa.exited}`
                : '—';
            const f = fa || fb;
            if (f) {
                telActive.textContent = f.active;
                telQueue.textContent = f.queuing;
                telCongested.textContent = f.congested;
                telExited.textContent = f.exited;
                telPeakDensity.textContent = Number(f.peakDensity || 0).toFixed(2);
            }
        } else {
            telActive.textContent = '0';
            telQueue.textContent = '0';
            telCongested.textContent = '0';
            telExited.textContent = '0';
            telPeakDensity.textContent = '0.00';
        }
    }

    function startPlaybackLoop() {
        if (playbackRafId) return;
        lastRafTimestamp = null;
        const step = (ts) => {
            if (!isPlaying) {
                playbackRafId = null;
                return;
            }
            if (lastRafTimestamp !== null) {
                const dtReal = Math.min(0.1, (ts - lastRafTimestamp) / 1000.0);
                simClockSec += dtReal * playbackSpeed;
                const total = maxDurationSec();
                if (simClockSec >= total) {
                    simClockSec = total;
                    setPlaying(false);
                }
            }
            lastRafTimestamp = ts;
            updatePlaybackHud();
            requestActiveRender();
            if (isPlaying) {
                playbackRafId = requestAnimationFrame(step);
            } else {
                playbackRafId = null;
            }
        };
        playbackRafId = requestAnimationFrame(step);
    }

    function setPlaying(playing) {
        const total = maxDurationSec();
        if (playing && total <= 0) {
            setMessage('Run a simulation first to play back crowd movement.', true);
            return;
        }
        if (playing && simClockSec >= total) {
            simClockSec = 0.0;
        }
        isPlaying = playing;
        playPauseBtn.textContent = isPlaying ? 'Pause' : 'Play';
        railPlayPauseBtn.classList.toggle('active', isPlaying);
        statusSimText.textContent = isPlaying
            ? `Simulation · Playing (${playbackSpeed}x)`
            : `Simulation · ${viewMode === 'COMPARE' ? 'Compare Ready' : 'Paused'}`;
        if (isPlaying) {
            startPlaybackLoop();
        }
    }

    function requestActiveRender() {
        if (viewMode === 'COMPARE') {
            beforeCore.requestRender();
            afterCore.requestRender();
        } else {
            mainCore.requestRender();
        }
    }

    function fitActiveCanvases() {
        if (viewMode === 'COMPARE') {
            if (compareData?.beforeLayout?.bounds) beforeCore.fitToBounds(compareData.beforeLayout.bounds, 36);
            if (compareData?.afterLayout?.bounds) afterCore.fitToBounds(compareData.afterLayout.bounds, 36);
        } else if (activeLayout?.bounds) {
            mainCore.fitToBounds(activeLayout.bounds, 44);
        }
    }

    function syncScenarioUi() {
        const scVal = scenarioSelect.value;
        barScenarioSelect.value = scVal;
        const option = (workspace.scenarios || []).find(s => s.type === scVal);
        scenarioDescText.textContent = option ? option.description : '';
        targetEdgeGroup.style.display = option?.requiresTargetEdge ? 'flex' : 'none';
        targetExitGroup.style.display = option?.requiresTargetExit ? 'flex' : 'none';
        disruptionTimeGroup.style.display = (option?.requiresTargetEdge || option?.requiresTargetExit) ? 'flex' : 'none';
    }

    function populateScenarioTargets(layout) {
        const edges = (layout?.edges || []).filter(e => e.active);
        const exits = (layout?.nodes || []).filter(n => n.nodeType === 'EXIT_GATE' || n.nodeType === 'EMERGENCY_EXIT');

        targetEdgeSelect.innerHTML = '<option value="">Auto-select primary corridor</option>';
        for (const e of edges) {
            const opt = document.createElement('option');
            opt.value = e.edgeUid || '';
            opt.textContent = `${e.label || 'Corridor'} (${Number(e.widthM).toFixed(1)} m)`;
            targetEdgeSelect.appendChild(opt);
        }

        targetExitSelect.innerHTML = '<option value="">Auto-select primary exit</option>';
        for (const ex of exits) {
            const opt = document.createElement('option');
            opt.value = ex.nodeUid || '';
            opt.textContent = `${ex.label} (${ex.nodeType === 'EMERGENCY_EXIT' ? 'Emergency' : 'Exit'})`;
            targetExitSelect.appendChild(opt);
        }
    }

    function populateSelectorsAndLists() {
        const snaps = workspace.snapshots || [];
        const runs = workspace.runs || [];

        simStatusPill.textContent = `${snaps.length} snapshots · ${runs.length} runs`;
        snapshotCountBadge.textContent = String(snaps.length);
        runsCountLabel.textContent = String(runs.length);

        const prevRunSnap = runSnapshotSelect.value;
        const prevBefore = beforeSnapshotSelect.value;
        const prevAfter = afterSnapshotSelect.value;

        runSnapshotSelect.innerHTML = '<option value="">Live Current Layout (Auto-Snapshot)</option>';
        beforeSnapshotSelect.innerHTML = '<option value="">Select Before Snapshot...</option>';
        afterSnapshotSelect.innerHTML = '<option value="">Select After Snapshot...</option>';

        for (const s of snaps) {
            const label = `V${s.versionNumber} · ${s.label} (${s.nodeCount}n / ${s.edgeCount}e)`;
            [runSnapshotSelect, beforeSnapshotSelect, afterSnapshotSelect].forEach(sel => {
                const opt = document.createElement('option');
                opt.value = String(s.id);
                opt.textContent = label;
                sel.appendChild(opt);
            });
        }

        if (prevRunSnap) runSnapshotSelect.value = prevRunSnap;
        if (prevBefore) beforeSnapshotSelect.value = prevBefore;
        else if (snaps.length >= 2) beforeSnapshotSelect.value = String(snaps[snaps.length - 1].id);
        if (prevAfter) afterSnapshotSelect.value = prevAfter;
        else if (snaps.length >= 1) afterSnapshotSelect.value = String(snaps[0].id);

        // Snapshots Cards
        snapshotsList.innerHTML = '';
        if (snaps.length === 0) {
            snapshotsList.innerHTML = '<p class="text-muted" style="font-size:12px;">No layout snapshots saved yet. Capture a baseline snapshot before modifying your layout.</p>';
        } else {
            for (const s of snaps) {
                const card = document.createElement('div');
                card.className = 'sim-snapshot-card';
                card.innerHTML = `
                    <img class="sim-snapshot-thumb" src="${escapeHtml(s.thumbnailUrl)}" alt="Snapshot V${s.versionNumber}">
                    <div class="sim-snapshot-meta">
                        <div style="display:flex;align-items:center;justify-content:space-between;gap:4px;">
                            <span class="badge badge-neutral mono">V${s.versionNumber}</span>
                            <span class="mono text-muted" style="font-size:11px;">${s.nodeCount}n · ${s.edgeCount}e</span>
                        </div>
                        <div style="font-size:12px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${escapeHtml(s.label)}</div>
                        <div style="display:flex;flex-wrap:wrap;gap:4px;margin-top:2px;">
                            <button type="button" class="btn btn-secondary btn-sm snap-use-btn" data-id="${s.id}" style="padding:2px 6px;font-size:11px;">Load</button>
                            <button type="button" class="btn btn-ghost btn-sm snap-before-btn" data-id="${s.id}" style="padding:2px 6px;font-size:11px;">Set Before</button>
                            <button type="button" class="btn btn-ghost btn-sm snap-after-btn" data-id="${s.id}" style="padding:2px 6px;font-size:11px;">Set After</button>
                            ${canManageSnapshots ? `<button type="button" class="btn btn-ghost btn-sm snap-del-btn" data-id="${s.id}" style="padding:2px 6px;font-size:11px;color:var(--sev-critical);">Del</button>` : ''}
                        </div>
                    </div>
                `;
                snapshotsList.appendChild(card);
            }
        }

        // Runs List
        runsList.innerHTML = '';
        if (runs.length === 0) {
            runsList.innerHTML = '<p class="text-muted" style="font-size:12px;">No simulation runs recorded yet.</p>';
        } else {
            for (const r of runs) {
                const row = document.createElement('div');
                row.className = 'network-item-row' + (activeRun && activeRun.id === r.id ? ' selected' : '');
                row.innerHTML = `
                    <div style="min-width:0;flex:1;">
                        <div style="font-size:12px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${escapeHtml(r.label || r.scenarioDisplayName)}</div>
                        <div class="mono text-secondary" style="font-size:11px;">
                            V${r.snapshotVersion} · seed ${r.randomSeed} · peak ${Number(r.metrics?.peakLocalDensityPsm || 0).toFixed(2)} p/m²
                        </div>
                    </div>
                    <div style="display:flex;gap:4px;flex-shrink:0;">
                        <button type="button" class="btn btn-secondary btn-sm run-load-btn" data-id="${r.id}" style="padding:2px 7px;font-size:11px;">Play</button>
                        ${canManageSnapshots ? `<button type="button" class="btn btn-ghost btn-sm run-del-btn" data-id="${r.id}" style="padding:2px 6px;font-size:11px;color:var(--sev-critical);">×</button>` : ''}
                    </div>
                `;
                runsList.appendChild(row);
            }
        }
    }

    function renderSingleMetrics(runSummary) {
        parityWarningBanner.style.display = 'none';
        compareNotesSection.style.display = 'none';
        metricsColAHeader.textContent = 'Value';
        metricsColBHeader.style.display = 'none';

        const m = runSummary?.metrics;
        if (!m) {
            metricsTableBody.innerHTML = '<tr><td colspan="2" class="text-muted">Run or select a simulation to inspect metrics.</td></tr>';
            hotspotsList.innerHTML = '<p class="text-muted" style="font-size:12px;">No hotspot data available.</p>';
            return;
        }

        const rows = [
            ['Peak Local Density', `${m.peakLocalDensityPsm.toFixed(2)} p/m²`],
            ['Peak Density Time / Loc', `${m.peakDensityTimeSec.toFixed(1)} s · ${escapeHtml(m.peakDensityLabel || '')}`],
            ['Peak Queue Length', `${m.peakQueueLength} persons`],
            ['Max / Avg Queue Wait', `${m.maxQueueWaitTimeSec.toFixed(1)} s / ${m.avgQueueWaitTimeSec.toFixed(1)} s`],
            ['Congestion Duration', `${m.bottleneckCongestionDurationSec.toFixed(1)} s (≥ ${m.highDensityThresholdPsm.toFixed(1)} p/m²)`],
            ['Avg Clearance Time', `${m.avgTravelClearanceTimeSec.toFixed(1)} s`],
            ['95th-Pct Clearance Time', `${m.p95TravelClearanceTimeSec.toFixed(1)} s`],
            ['Exited / Scheduled', `${m.totalAgentsExited} / ${m.totalAgentsScheduled} (${m.clearanceRatePct.toFixed(1)}%)`],
            ['Unserved / Blocked', `${m.unservedBlockedAgentCount} (${m.blockedNoRouteCount} no-route)`]
        ];
        metricsTableBody.innerHTML = rows.map(r => `<tr><td>${r[0]}</td><td class="num mono">${r[1]}</td></tr>`).join('');

        renderHotspots(m.hotspots || []);
    }

    function renderComparisonMetrics(comp) {
        if (!comp) return;
        if (!comp.parityMatched && comp.parityWarning) {
            parityWarningBanner.textContent = comp.parityWarning;
            parityWarningBanner.style.display = 'block';
        } else {
            parityWarningBanner.style.display = 'none';
        }

        metricsColAHeader.textContent = 'Before';
        metricsColBHeader.textContent = 'After';
        metricsColBHeader.style.display = '';

        const deltas = [
            comp.peakDensity,
            comp.peakQueueLength,
            comp.maxQueueWaitTime,
            comp.congestionDuration,
            comp.avgTravelTime,
            comp.p95TravelTime,
            comp.unservedBlockedAgents
        ];
        metricsTableBody.innerHTML = deltas.map(d => `
            <tr>
                <td>${escapeHtml(d.label)}</td>
                <td class="num mono">${formatVal(d.beforeValue, d.unit)}</td>
                <td class="num mono">${formatVal(d.afterValue, d.unit)}</td>
            </tr>
        `).join('');

        updateDeltaCard('deltaCardDensity', 'deltaDensityVal', 'deltaDensityBadge', 'deltaDensitySub', comp.peakDensity,
            `Before ${comp.peakDensity.beforeValue.toFixed(2)} → After ${comp.peakDensity.afterValue.toFixed(2)} p/m²`);
        updateDeltaCard('deltaCardQueue', 'deltaQueueVal', 'deltaQueueBadge', 'deltaQueueSub', comp.peakQueueLength,
            `Wait ${comp.maxQueueWaitTime.beforeValue.toFixed(1)}s → ${comp.maxQueueWaitTime.afterValue.toFixed(1)}s`);
        updateDeltaCard('deltaCardCongestion', 'deltaCongestionVal', 'deltaCongestionBadge', 'deltaCongestionSub', comp.congestionDuration,
            `Before ${comp.congestionDuration.beforeValue.toFixed(1)}s → After ${comp.congestionDuration.afterValue.toFixed(1)}s`);
        updateDeltaCard('deltaCardClearance', 'deltaClearanceVal', 'deltaClearanceBadge', 'deltaClearanceSub', comp.p95TravelTime,
            `Blocked ${Math.round(comp.unservedBlockedAgents.beforeValue)} → ${Math.round(comp.unservedBlockedAgents.afterValue)}`);

        document.getElementById('beforePaneTitle').textContent =
            `V${comp.beforeRun.snapshotVersion} · ${comp.beforeRun.snapshotLabel}`;
        document.getElementById('afterPaneTitle').textContent =
            `V${comp.afterRun.snapshotVersion} · ${comp.afterRun.snapshotLabel}`;

        compareNotesSection.style.display = 'flex';
        compareNotesList.innerHTML = (comp.narrativeNotes || []).map(n => `<li>${escapeHtml(n)}</li>`).join('');
        renderHotspots(comp.afterRun?.metrics?.hotspots || []);
    }

    function updateDeltaCard(cardId, valId, badgeId, subId, kpi, subText) {
        const card = document.getElementById(cardId);
        const valEl = document.getElementById(valId);
        const badgeEl = document.getElementById(badgeId);
        const subEl = document.getElementById(subId);

        card.classList.remove('is-improved', 'is-worse');
        badgeEl.className = 'badge';
        if (kpi.trend === 'IMPROVED') {
            card.classList.add('is-improved');
            badgeEl.classList.add('badge-verified');
        } else if (kpi.trend === 'WORSE') {
            card.classList.add('is-worse');
            badgeEl.classList.add('badge-critical');
        } else {
            badgeEl.classList.add('badge-neutral');
        }
        badgeEl.textContent = kpi.trend;

        const sign = kpi.delta > 0 ? '+' : '';
        const pct = kpi.deltaPct !== null && kpi.deltaPct !== undefined ? ` (${sign}${kpi.deltaPct.toFixed(1)}%)` : '';
        valEl.textContent = `${sign}${kpi.delta.toFixed(2)} ${kpi.unit}${pct}`;
        subEl.textContent = subText;
    }

    function renderHotspots(hotspots) {
        hotspotsList.innerHTML = '';
        if (!hotspots || hotspots.length === 0) {
            hotspotsList.innerHTML = '<p class="text-muted" style="font-size:12px;">No corridor congestion hotspots recorded.</p>';
            return;
        }
        for (const h of hotspots) {
            const el = document.createElement('div');
            el.className = 'sim-hotspot-card';
            const pinched = h.effectiveWidthM + 0.05 < h.nominalWidthM;
            el.innerHTML = `
                <div style="display:flex;align-items:center;justify-content:space-between;gap:6px;">
                    <strong style="font-size:12px;">${escapeHtml(h.label)}</strong>
                    <span class="badge ${h.peakDensityPsm >= 2.0 ? 'badge-critical' : 'badge-info'} mono">${h.peakDensityPsm.toFixed(2)} p/m²</span>
                </div>
                <div class="mono text-secondary" style="font-size:11px;">
                    Width ${h.effectiveWidthM.toFixed(1)}/${h.nominalWidthM.toFixed(1)} m${pinched ? ' (pinched)' : ''} · Congested ${h.congestedDurationSec.toFixed(1)} s
                </div>
                <div>
                    <button type="button" class="btn btn-ghost btn-sm locate-hotspot-btn"
                            data-x="${h.locationX}" data-y="${h.locationY}" style="padding:2px 6px;font-size:11px;">
                        Locate on Canvas
                    </button>
                </div>
            `;
            hotspotsList.appendChild(el);
        }
    }

    async function refreshWorkspace() {
        workspace = await apiGet(`/api/events/${eventId}/simulation`);
        populateSelectorsAndLists();
        populateScenarioTargets(activeLayout || workspace.currentLayout);
    }

    async function captureSnapshot(label, notes) {
        try {
            setMessage('Capturing layout snapshot...');
            let thumbUrl = null;
            if (viewMode === 'PLAYBACK' && mainCanvasEl.width > 0) {
                thumbUrl = mainCore.exportPngDataUrl();
            }
            const detail = await apiPost(`/api/events/${eventId}/simulation/snapshots`, {
                label: label || null,
                notes: notes || null,
                thumbnailDataUrl: thumbUrl
            });
            await refreshWorkspace();
            runSnapshotSelect.value = String(detail.summary.id);
            setMessage(`Captured Snapshot V${detail.summary.versionNumber}: ${detail.summary.label}`);
            return detail;
        } catch (err) {
            setMessage(err.message, true);
            return null;
        }
    }

    async function runSingleSimulation() {
        try {
            setPlaying(false);
            setMessage('Running deterministic Java crowd simulation...');
            const payload = buildConfigPayload();
            payload.snapshotId = runSnapshotSelect.value ? Number(runSnapshotSelect.value) : null;

            const detail = await apiPost(`/api/events/${eventId}/simulation/runs`, payload);
            activeRun = detail.run;
            activeLayout = detail.layout;
            activeFrames = detail.frameStream;
            simClockSec = 0.0;
            highlightPoint = null;

            await refreshWorkspace();
            renderSingleMetrics(activeRun);
            fitActiveCanvases();
            updatePlaybackHud();
            setPlaying(true);
            setMessage(`Simulation complete: ${activeRun.metrics.totalAgentsExited}/${activeRun.agentCount} exited, peak ${activeRun.metrics.peakLocalDensityPsm.toFixed(2)} p/m².`);
        } catch (err) {
            setMessage(err.message, true);
        }
    }

    async function loadExistingRun(runId) {
        try {
            setPlaying(false);
            setMessage('Loading saved simulation run...');
            const detail = await apiGet(`/api/events/${eventId}/simulation/runs/${runId}`);
            switchViewMode('PLAYBACK');
            activeRun = detail.run;
            activeLayout = detail.layout;
            activeFrames = detail.frameStream;
            simClockSec = 0.0;
            highlightPoint = null;
            populateSelectorsAndLists();
            renderSingleMetrics(activeRun);
            fitActiveCanvases();
            updatePlaybackHud();
            mainCore.requestRender();
            setMessage(`Loaded run: ${activeRun.label}`);
        } catch (err) {
            setMessage(err.message, true);
        }
    }

    async function runPairedComparison() {
        const beforeId = Number(beforeSnapshotSelect.value);
        const afterId = Number(afterSnapshotSelect.value);
        if (!beforeId || !afterId) {
            setMessage('Select both a Before snapshot and an After snapshot to run paired comparison.', true);
            return;
        }
        try {
            setPlaying(false);
            setMessage('Running paired Before/After simulations with identical seed...');
            const payload = buildConfigPayload();
            payload.beforeSnapshotId = beforeId;
            payload.afterSnapshotId = afterId;

            const comp = await apiPost(`/api/events/${eventId}/simulation/compare`, payload);
            const [bFrames, aFrames] = await Promise.all([
                apiGet(comp.beforeFramesUrl),
                apiGet(comp.afterFramesUrl)
            ]);
            compareData = comp;
            beforeFrames = bFrames;
            afterFrames = aFrames;
            simClockSec = 0.0;
            highlightPoint = null;

            await refreshWorkspace();
            renderComparisonMetrics(comp);
            beforeCore.resize();
            afterCore.resize();
            fitActiveCanvases();
            updatePlaybackHud();
            setPlaying(true);
            setMessage(`Paired comparison complete (seed ${comp.afterRun.randomSeed}).`);
        } catch (err) {
            setMessage(err.message, true);
        }
    }

    async function loadExistingComparison(beforeRunId, afterRunId) {
        try {
            setPlaying(false);
            setMessage('Loading simulation comparison...');
            const comp = await apiGet(`/api/events/${eventId}/simulation/compare?beforeRunId=${beforeRunId}&afterRunId=${afterRunId}`);
            const [bFrames, aFrames] = await Promise.all([
                apiGet(comp.beforeFramesUrl),
                apiGet(comp.afterFramesUrl)
            ]);
            switchViewMode('COMPARE');
            compareData = comp;
            beforeFrames = bFrames;
            afterFrames = aFrames;
            simClockSec = 0.0;
            highlightPoint = null;
            renderComparisonMetrics(comp);
            beforeCore.resize();
            afterCore.resize();
            fitActiveCanvases();
            updatePlaybackHud();
            setMessage(`Loaded comparison: ${comp.beforeRun.label} vs ${comp.afterRun.label}`);
        } catch (err) {
            setMessage(err.message, true);
        }
    }

    function buildConfigPayload() {
        return {
            scenarioType: scenarioSelect.value,
            randomSeed: Number(seedInput.value || 42),
            agentCount: Number(agentCountInput.value || 350),
            durationSeconds: Number(durationInput.value || 300),
            highDensityThresholdPsm: Number(densityThresholdInput.value || 2.0),
            targetEdgeUid: targetEdgeSelect.value || null,
            targetExitNodeUid: targetExitSelect.value || null,
            disruptionStartSec: Number(disruptionStartInput.value || 0)
        };
    }

    function switchViewMode(mode) {
        setPlaying(false);
        viewMode = mode;
        const isCompare = mode === 'COMPARE';
        modePlaybackBtn.classList.toggle('active', !isCompare);
        modeCompareBtn.classList.toggle('active', isCompare);
        playbackStage.style.display = isCompare ? 'none' : 'block';
        compareStage.style.display = isCompare ? 'flex' : 'none';
        singleRunSection.style.display = isCompare ? 'none' : 'flex';
        compareSetupSection.style.display = isCompare ? 'flex' : 'none';
        inspectorRunBtn.style.display = isCompare ? 'none' : 'inline-flex';
        inspectorCompareBtn.style.display = isCompare ? 'inline-flex' : 'none';
        barRunSimBtn.textContent = isCompare ? 'Run Comparison' : 'Run Simulation';

        if (isCompare) {
            beforeCore.resize();
            afterCore.resize();
            if (compareData) {
                renderComparisonMetrics(compareData);
            }
        } else {
            mainCore.resize();
            renderSingleMetrics(activeRun);
        }
        simClockSec = 0.0;
        fitActiveCanvases();
        updatePlaybackHud();
        requestActiveRender();
    }

    // Event bindings
    modePlaybackBtn.addEventListener('click', () => switchViewMode('PLAYBACK'));
    modeCompareBtn.addEventListener('click', () => switchViewMode('COMPARE'));

    barScenarioSelect.addEventListener('change', () => {
        scenarioSelect.value = barScenarioSelect.value;
        syncScenarioUi();
    });
    scenarioSelect.addEventListener('change', syncScenarioUi);

    barRunSimBtn.addEventListener('click', () => {
        if (viewMode === 'COMPARE') runPairedComparison();
        else runSingleSimulation();
    });
    inspectorRunBtn.addEventListener('click', runSingleSimulation);
    inspectorCompareBtn.addEventListener('click', runPairedComparison);

    playPauseBtn.addEventListener('click', () => setPlaying(!isPlaying));
    railPlayPauseBtn.addEventListener('click', () => setPlaying(!isPlaying));

    const doReset = () => {
        setPlaying(false);
        simClockSec = 0.0;
        updatePlaybackHud();
        requestActiveRender();
    };
    resetPlaybackBtn.addEventListener('click', doReset);
    railResetBtn.addEventListener('click', doReset);

    document.querySelectorAll('.sim-speed-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.sim-speed-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            playbackSpeed = Number(btn.dataset.speed || 1);
            if (isPlaying) {
                statusSimText.textContent = `Simulation · Playing (${playbackSpeed}x)`;
            }
        });
    });

    simScrubber.addEventListener('input', () => {
        setPlaying(false);
        simClockSec = Number(simScrubber.value || 0);
        updatePlaybackHud();
        requestActiveRender();
    });

    toggleHeatmapBtn.addEventListener('click', () => {
        showHeatmap = !showHeatmap;
        toggleHeatmapBtn.classList.toggle('active', showHeatmap);
        requestActiveRender();
    });
    toggleAgentsBtn.addEventListener('click', () => {
        showAgents = !showAgents;
        toggleAgentsBtn.classList.toggle('active', showAgents);
        requestActiveRender();
    });
    toggleNetworkBtn.addEventListener('click', () => {
        showNetwork = !showNetwork;
        toggleNetworkBtn.classList.toggle('active', showNetwork);
        requestActiveRender();
    });

    toggleInspectorBtn.addEventListener('click', () => {
        inspectorPanel.classList.toggle('collapsed');
        setTimeout(() => {
            mainCore.resize();
            beforeCore.resize();
            afterCore.resize();
        }, 50);
    });

    document.querySelectorAll('.inspector-tab').forEach(tab => {
        tab.addEventListener('click', () => {
            const target = tab.dataset.tab;
            document.querySelectorAll('.inspector-tab').forEach(t => t.classList.toggle('active', t === tab));
            document.querySelectorAll('.inspector-pane').forEach(p => p.classList.toggle('active', p.dataset.pane === target));
        });
    });

    document.getElementById('zoomInBtn').addEventListener('click', () => mainCore.zoomAt(1.2));
    document.getElementById('zoomOutBtn').addEventListener('click', () => mainCore.zoomAt(1 / 1.2));
    document.getElementById('zoom100Btn').addEventListener('click', () => mainCore.resetZoom100());
    document.getElementById('zoomFitBtn').addEventListener('click', fitActiveCanvases);
    document.getElementById('railFitBtn').addEventListener('click', fitActiveCanvases);

    document.getElementById('legendToggleBtn').addEventListener('click', (e) => {
        const list = document.getElementById('legendList');
        const hidden = list.style.display === 'none';
        list.style.display = hidden ? 'flex' : 'none';
        e.target.textContent = hidden ? 'Hide' : 'Show';
    });

    document.getElementById('exportPngBtn').addEventListener('click', () => {
        const url = viewMode === 'COMPARE' ? afterCore.exportPngDataUrl() : mainCore.exportPngDataUrl();
        const a = document.createElement('a');
        a.href = url;
        a.download = `vyuha-sim-event-${eventId}.png`;
        a.click();
    });

    if (openSnapshotDialogBtn) {
        openSnapshotDialogBtn.addEventListener('click', () => {
            modalSnapshotLabel.value = '';
            modalSnapshotNotes.value = '';
            snapshotDialog.showModal();
        });
    }
    if (closeSnapshotDialogBtn) closeSnapshotDialogBtn.addEventListener('click', () => snapshotDialog.close());
    if (cancelCaptureSnapshotBtn) cancelCaptureSnapshotBtn.addEventListener('click', () => snapshotDialog.close());
    if (confirmCaptureSnapshotBtn) {
        confirmCaptureSnapshotBtn.addEventListener('click', async () => {
            await captureSnapshot(modalSnapshotLabel.value, modalSnapshotNotes.value);
            snapshotDialog.close();
        });
    }
    if (inlineCaptureSnapshotBtn) {
        inlineCaptureSnapshotBtn.addEventListener('click', async () => {
            const res = await captureSnapshot(inlineSnapshotLabel.value, inlineSnapshotNotes.value);
            if (res) {
                inlineSnapshotLabel.value = '';
                inlineSnapshotNotes.value = '';
            }
        });
    }

    snapshotsList.addEventListener('click', async (e) => {
        const useBtn = e.target.closest('.snap-use-btn');
        const beforeBtn = e.target.closest('.snap-before-btn');
        const afterBtn = e.target.closest('.snap-after-btn');
        const delBtn = e.target.closest('.snap-del-btn');

        if (useBtn) {
            const sid = useBtn.dataset.id;
            const detail = await apiGet(`/api/events/${eventId}/simulation/snapshots/${sid}`);
            activeLayout = detail.layout;
            activeRun = null;
            activeFrames = null;
            runSnapshotSelect.value = String(sid);
            populateScenarioTargets(activeLayout);
            switchViewMode('PLAYBACK');
            fitActiveCanvases();
            setMessage(`Viewing Snapshot V${detail.summary.versionNumber}: ${detail.summary.label}`);
        } else if (beforeBtn) {
            beforeSnapshotSelect.value = beforeBtn.dataset.id;
            switchViewMode('COMPARE');
            document.querySelector('.inspector-tab[data-tab="setup"]').click();
        } else if (afterBtn) {
            afterSnapshotSelect.value = afterBtn.dataset.id;
            switchViewMode('COMPARE');
            document.querySelector('.inspector-tab[data-tab="setup"]').click();
        } else if (delBtn) {
            try {
                await apiDelete(`/api/events/${eventId}/simulation/snapshots/${delBtn.dataset.id}`);
                await refreshWorkspace();
                setMessage('Snapshot deleted.');
            } catch (err) {
                setMessage(err.message, true);
            }
        }
    });

    runsList.addEventListener('click', async (e) => {
        const loadBtn = e.target.closest('.run-load-btn');
        const delBtn = e.target.closest('.run-del-btn');
        if (loadBtn) {
            await loadExistingRun(loadBtn.dataset.id);
        } else if (delBtn) {
            try {
                await apiDelete(`/api/events/${eventId}/simulation/runs/${delBtn.dataset.id}`);
                if (activeRun && String(activeRun.id) === delBtn.dataset.id) {
                    activeRun = null;
                    activeFrames = null;
                }
                await refreshWorkspace();
                renderSingleMetrics(activeRun);
                updatePlaybackHud();
                requestActiveRender();
                setMessage('Simulation run deleted.');
            } catch (err) {
                setMessage(err.message, true);
            }
        }
    });

    hotspotsList.addEventListener('click', (e) => {
        const btn = e.target.closest('.locate-hotspot-btn');
        if (!btn) return;
        const x = Number(btn.dataset.x);
        const y = Number(btn.dataset.y);
        if (Number.isFinite(x) && Number.isFinite(y)) {
            highlightPoint = { x, y };
            if (viewMode === 'COMPARE') {
                beforeCore.panTo(x, y, 10);
                afterCore.panTo(x, y, 10);
            } else {
                mainCore.panTo(x, y, 10);
            }
            requestActiveRender();
        }
    });

    // Helper functions
    function formatClock(totalSec) {
        const s = Math.max(0, Math.round(totalSec || 0));
        const mm = String(Math.floor(s / 60)).padStart(2, '0');
        const ss = String(s % 60).padStart(2, '0');
        return `${mm}:${ss}`;
    }

    function formatVal(val, unit) {
        if (unit === 'persons') return `${Math.round(val)} ${unit}`;
        return `${Number(val).toFixed(2)} ${unit}`;
    }

    function hexToRgba(hex, alpha) {
        if (!hex || !hex.startsWith('#') || hex.length !== 7) {
            return `rgba(59, 130, 246, ${alpha})`;
        }
        const r = parseInt(hex.slice(1, 3), 16);
        const g = parseInt(hex.slice(3, 5), 16);
        const b = parseInt(hex.slice(5, 7), 16);
        return `rgba(${r}, ${g}, ${b}, ${alpha})`;
    }

    function escapeHtml(str) {
        return String(str ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // Initial boot
    syncScenarioUi();
    populateSelectorsAndLists();
    populateScenarioTargets(activeLayout);
    fitActiveCanvases();
    updatePlaybackHud();
    const urlParams = new URLSearchParams(window.location.search);
    const paramBeforeRunId = Number(urlParams.get('beforeRunId'));
    const paramAfterRunId = Number(urlParams.get('afterRunId'));
    const paramRunId = Number(urlParams.get('runId'));
    if (paramBeforeRunId > 0 && paramAfterRunId > 0) {
        loadExistingComparison(paramBeforeRunId, paramAfterRunId);
    } else if (paramRunId > 0) {
        loadExistingRun(paramRunId);
    } else if (workspace.runs && workspace.runs.length > 0) {
        loadExistingRun(workspace.runs[0].id);
    }
}
