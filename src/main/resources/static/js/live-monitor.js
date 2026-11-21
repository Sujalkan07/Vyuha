import { VyuhaCanvasCore } from './canvas-core.js';
import { apiGet, apiPost, apiPut, apiDelete, apiUpload } from './api-client.js';

const root = document.getElementById('liveMonitorRoot');
if (root) {
    const eventId = root.dataset.eventId;
    const canEdit = root.dataset.canEdit === 'true';
    const canAck = root.dataset.canAck === 'true';

    let state = JSON.parse(root.dataset.initialState || '{}');
    let activeMode = state.mode || 'LIVE';
    let activeSource = state.activeSource || 'MANUAL_STAFF';

    const canvas = document.getElementById('monitorHeatmapCanvas');
    const tooltipEl = document.getElementById('monitorHoverTooltip');
    const coordsEl = document.getElementById('monCursorCoords');
    const feedbackEl = document.getElementById('liveActionFeedback');
    const ssePillEl = document.getElementById('sseStatusPill');

    const blueprintImg = new Image();
    let blueprintLoadedUrl = null;
    let practiceTimer = null;
    let practiceFrameIdx = 0;

    const core = new VyuhaCanvasCore(canvas, {
        initialZoom: 6.5,
        onRender: () => renderHeatmap()
    });

    function ensureBlueprintLoaded() {
        const geom = state.heatmapGeometry;
        if (geom && geom.hasBlueprint && geom.blueprintUrl && blueprintLoadedUrl !== geom.blueprintUrl) {
            blueprintLoadedUrl = geom.blueprintUrl;
            blueprintImg.onload = () => core.requestRender();
            blueprintImg.src = geom.blueprintUrl;
        }
    }

    function fitHeatmapBounds() {
        const bounds = state.heatmapGeometry ? state.heatmapGeometry.bounds : null;
        core.fitToBounds(bounds, 44);
    }

    function renderHeatmap() {
        core.beginFrame();
        const geom = state.heatmapGeometry || {};
        if (geom.hasBlueprint && blueprintImg.complete && geom.pixelsPerMeter) {
            core.drawBlueprintImage(blueprintImg, geom.pixelsPerMeter, geom.blueprintWidthPx, geom.blueprintHeightPx, 0.78);
        }
        core.drawMetricGrid('m');

        if (Array.isArray(geom.boundaryPoints) && geom.boundaryPoints.length >= 3) {
            core.drawPolygon(geom.boundaryPoints, {
                fill: geom.hasBlueprint ? null : 'rgba(255, 255, 255, 0.72)',
                stroke: '#0F172A',
                lineWidth: 2
            });
        }

        if (Array.isArray(geom.exclusions)) {
            for (const ex of geom.exclusions) {
                core.drawPolygon(ex.points, {
                    fill: ex.fillColor || 'rgba(148, 163, 184, 0.30)',
                    stroke: ex.strokeColor || '#64748B',
                    lineWidth: 1.2,
                    hatched: !ex.corridor,
                    hatchColor: '#475569'
                });
            }
        }

        if (Array.isArray(state.zones)) {
            for (const z of state.zones) {
                const countText = z.currentCount !== null && z.currentCount !== undefined
                    ? `${z.zoneCode}: ${z.currentCount}` + (z.occupancyPct !== null ? ` (${z.occupancyPct.toFixed(0)}%)` : '')
                    : `${z.zoneCode}: —`;
                core.drawPolygonShapes(z.shapes, {
                    fill: z.bandFillColor,
                    stroke: z.bandStrokeColor,
                    lineWidth: z.stale ? 2.0 : 1.6,
                    dashed: z.stale ? [5, 3] : null,
                    hatched: Boolean(z.bandHatched),
                    hatchColor: '#64748B',
                    label: countText,
                    labelPoint: z.labelPoint
                });
            }
        }

        if (Array.isArray(geom.gates)) {
            const ctx = core.ctx;
            ctx.save();
            for (const g of geom.gates) {
                const pt = core.worldToScreen(g.centerX, g.centerY);
                ctx.fillStyle = g.active ? '#0F172A' : '#94A3B8';
                ctx.strokeStyle = '#FFFFFF';
                ctx.lineWidth = 1.5;
                ctx.beginPath();
                ctx.arc(pt.x, pt.y, 5, 0, Math.PI * 2);
                ctx.fill();
                ctx.stroke();

                ctx.font = '600 10px system-ui, sans-serif';
                ctx.fillStyle = '#0F172A';
                ctx.textAlign = 'center';
                ctx.fillText(g.label, pt.x, pt.y - 9);
            }
            ctx.restore();
        }

        core.drawScaleBarAndCompass(true);
    }

    function pointInRing(wx, wy, ring) {
        if (!Array.isArray(ring) || ring.length < 3) return false;
        let inside = false;
        for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            const xi = ring[i].x, yi = ring[i].y;
            const xj = ring[j].x, yj = ring[j].y;
            const intersect = ((yi > wy) !== (yj > wy))
                && (wx < ((xj - xi) * (wy - yi)) / ((yj - yi) || 1e-9) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    function findZoneAtWorld(wx, wy) {
        if (!Array.isArray(state.zones)) return null;
        for (const z of state.zones) {
            if (!Array.isArray(z.shapes)) continue;
            for (const shape of z.shapes) {
                if (!pointInRing(wx, wy, shape.exterior)) continue;
                let inHole = false;
                if (Array.isArray(shape.holes)) {
                    for (const h of shape.holes) {
                        if (pointInRing(wx, wy, h)) {
                            inHole = true;
                            break;
                        }
                    }
                }
                if (!inHole) return z;
            }
        }
        return null;
    }

    let isPanning = false;
    let lastPanX = 0;
    let lastPanY = 0;

    canvas.addEventListener('mousedown', (e) => {
        if (e.button === 1 || e.button === 2 || e.shiftKey) {
            isPanning = true;
            lastPanX = e.clientX;
            lastPanY = e.clientY;
            e.preventDefault();
        }
    });

    window.addEventListener('mousemove', (e) => {
        if (isPanning) {
            core.panBy(e.clientX - lastPanX, e.clientY - lastPanY);
            lastPanX = e.clientX;
            lastPanY = e.clientY;
        }
    });

    window.addEventListener('mouseup', () => {
        isPanning = false;
    });

    canvas.addEventListener('mousemove', (e) => {
        const local = core.getCanvasLocalCoords(e.clientX, e.clientY);
        const world = core.screenToWorld(local.x, local.y);
        if (coordsEl) {
            coordsEl.textContent = `X: ${world.x.toFixed(1)} m, Y: ${world.y.toFixed(1)} m`;
        }
        const hit = findZoneAtWorld(world.x, world.y);
        if (hit && tooltipEl) {
            const pctStr = hit.occupancyPct !== null && hit.occupancyPct !== undefined
                ? `${hit.occupancyPct.toFixed(1)}%` : '—';
            const cntStr = hit.currentCount !== null && hit.currentCount !== undefined
                ? `${hit.currentCount}` : '—';
            const updatedStr = formatUtc(hit.lastUpdatedAt);
            const staleTag = hit.stale ? ' [STALE]' : '';
            tooltipEl.innerHTML =
                `<strong>${escapeHtml(hit.zoneCode)} · ${escapeHtml(hit.zoneName)}</strong><br>` +
                `Count: <span class="mono">${cntStr}</span> (${pctStr})<br>` +
                `Source: ${escapeHtml(hit.sourceShortLabel)}${staleTag}<br>` +
                `Updated: <span class="mono">${escapeHtml(updatedStr)}</span>`;
            tooltipEl.style.left = `${Math.min(canvas.clientWidth - 240, Math.max(8, local.x + 12))}px`;
            tooltipEl.style.top = `${Math.max(8, local.y - 12)}px`;
            tooltipEl.style.display = 'block';
        } else if (tooltipEl) {
            tooltipEl.style.display = 'none';
        }
    });

    canvas.addEventListener('mouseleave', () => {
        if (tooltipEl) tooltipEl.style.display = 'none';
    });

    canvas.addEventListener('wheel', (e) => {
        e.preventDefault();
        const local = core.getCanvasLocalCoords(e.clientX, e.clientY);
        const factor = e.deltaY < 0 ? 1.15 : 0.87;
        core.zoomAt(factor, local.x, local.y);
    }, { passive: false });

    document.getElementById('monZoomOutBtn')?.addEventListener('click', () => core.zoomAt(0.8));
    document.getElementById('monZoomInBtn')?.addEventListener('click', () => core.zoomAt(1.25));
    document.getElementById('monZoom100Btn')?.addEventListener('click', () => core.resetZoom100());
    document.getElementById('monZoomFitBtn')?.addEventListener('click', () => fitHeatmapBounds());

    document.getElementById('monLegendToggleBtn')?.addEventListener('click', (e) => {
        const list = document.getElementById('monLegendList');
        if (!list) return;
        const hidden = list.style.display === 'none';
        list.style.display = hidden ? 'flex' : 'none';
        e.target.textContent = hidden ? 'Hide' : 'Show';
    });

    function showFeedback(message, isError = false) {
        if (!feedbackEl) return;
        feedbackEl.className = isError ? 'banner banner-danger' : 'banner banner-info';
        feedbackEl.textContent = message;
        feedbackEl.style.display = 'block';
    }

    function formatUtc(iso) {
        if (!iso) return '—';
        const d = new Date(iso);
        if (Number.isNaN(d.getTime())) return '—';
        return d.toISOString().slice(0, 16).replace('T', ' ') + ' UTC';
    }

    function escapeHtml(text) {
        if (text === null || text === undefined) return '';
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function applyState(nextState) {
        state = nextState;
        activeMode = state.mode || 'LIVE';
        activeSource = state.activeSource || 'MANUAL_STAFF';

        ensureBlueprintLoaded();
        updateHeaderAndBanners();
        updateKpis();
        updateZonesTable();
        updatePassesTable();
        updateAlertsFeed();
        updateRulesList();
        updateGateList();

        const evalEl = document.getElementById('monLastEvalText');
        if (evalEl) evalEl.textContent = 'Evaluated: ' + formatUtc(state.evaluatedAt);

        core.requestRender();
    }

    function updateHeaderAndBanners() {
        const isPractice = activeMode === 'PRACTICE';
        const badge = document.getElementById('headerModeBadge');
        if (badge) {
            badge.className = 'badge ' + (isPractice ? 'badge-low' : 'badge-info');
            badge.textContent = isPractice ? 'Practice Simulation Mode' : 'Live Operations Mode';
        }
        document.getElementById('modeLiveBtn')?.classList.toggle('active', !isPractice);
        document.getElementById('modePracticeBtn')?.classList.toggle('active', isPractice);

        const liveBanner = document.getElementById('liveSourceSegregationBanner');
        const pracBanner = document.getElementById('practiceIsolationBanner');
        const srcWrap = document.getElementById('liveSourceSwitchWrap');
        const pracToolbar = document.getElementById('practiceToolbar');
        const passCard = document.getElementById('passManagementCard');
        const manualPanel = document.getElementById('manualCountPanel');

        if (liveBanner) liveBanner.style.display = isPractice ? 'none' : 'block';
        if (pracBanner) pracBanner.style.display = isPractice ? 'block' : 'none';
        if (srcWrap) srcWrap.style.display = isPractice ? 'none' : 'inline-flex';
        if (pracToolbar) pracToolbar.style.display = isPractice ? 'flex' : 'none';
        if (passCard) passCard.style.display = isPractice ? 'none' : 'block';
        if (manualPanel) manualPanel.style.display = isPractice ? 'none' : 'block';

        document.getElementById('sourceManualBtn')?.classList.toggle('active', activeSource === 'MANUAL_STAFF');
        document.getElementById('sourcePassBtn')?.classList.toggle('active', activeSource === 'PASS_SCAN');
        const mPill = document.getElementById('sourceManualPillCount');
        const pPill = document.getElementById('sourcePassPillCount');
        if (mPill && state.manualSummary) mPill.textContent = String(state.manualSummary.totalCount);
        if (pPill && state.passScanSummary) pPill.textContent = String(state.passScanSummary.totalCount);

        const heatBadge = document.getElementById('heatmapChannelBadge');
        if (heatBadge && state.activeChannel) {
            heatBadge.className = 'badge ' + state.activeChannel.badgeClass;
            heatBadge.textContent = state.activeChannel.shortLabel;
        }
    }

    function updateKpis() {
        const ch = state.activeChannel;
        const ps = state.passSummary;
        const qp = state.queuePressure;
        const sd = state.staffDuty;

        const kActive = document.getElementById('kpiActiveTotal');
        const kActiveMeta = document.getElementById('kpiActiveMeta');
        if (kActive && ch) kActive.textContent = String(ch.totalCount);
        if (kActiveMeta && ch) {
            kActiveMeta.innerHTML =
                `<span class="badge ${escapeHtml(ch.badgeClass)}">${escapeHtml(ch.shortLabel)}</span> ` +
                `<span class="mono">Updated: ${escapeHtml(formatUtc(ch.lastUpdatedAt))}</span>` +
                (ch.stale ? ` <span class="badge badge-low">STALE</span>` : '');
        }

        const kPass = document.getElementById('kpiPassCheckedIn');
        const kPassMeta = document.getElementById('kpiPassMeta');
        if (kPass && ps) kPass.textContent = String(ps.checkedInCount);
        if (kPassMeta && ps) {
            kPassMeta.innerHTML =
                `<span class="badge badge-verified">Pass Scan</span> ` +
                `<span class="mono">${ps.totalIssued} issued · ${ps.checkedOutCount} out</span> ` +
                `<span class="mono">Updated: ${escapeHtml(formatUtc(ps.lastScannedAt))}</span>`;
        }

        const kHold = document.getElementById('kpiHoldingCount');
        const kHoldMeta = document.getElementById('kpiHoldingMeta');
        if (kHold && qp) {
            kHold.textContent = qp.holdingCapacity > 0
                ? `${qp.currentHoldingCount} / ${qp.holdingCapacity}`
                : String(qp.currentHoldingCount);
        }
        if (kHoldMeta && qp && ch) {
            const pctStr = qp.holdingUtilizationPct !== null && qp.holdingUtilizationPct !== undefined
                ? `${qp.holdingUtilizationPct.toFixed(1)}% load`
                : 'No cap';
            kHoldMeta.innerHTML =
                `<span class="badge ${escapeHtml(ch.badgeClass)}">${escapeHtml(ch.shortLabel)}</span> ` +
                `<span class="mono">${escapeHtml(pctStr)}</span> ` +
                `<span class="mono">Updated: ${escapeHtml(formatUtc(qp.lastUpdatedAt))}</span>` +
                (qp.stale ? ` <span class="badge badge-low">STALE</span>` : '');
        }

        const kStaff = document.getElementById('kpiStaffAndAlerts');
        const kStaffMeta = document.getElementById('kpiStaffMeta');
        if (kStaff && sd) kStaff.textContent = `${sd.onDutyNowCount} / ${sd.totalStaffCount}`;
        if (kStaffMeta && sd) {
            const alertCnt = Array.isArray(state.activeAlerts) ? state.activeAlerts.length : 0;
            kStaffMeta.textContent = `${alertCnt} active alerts · ${sd.openIncidentCount} open incidents`;
        }
    }

    function updateZonesTable() {
        const zones = Array.isArray(state.zones) ? state.zones : [];
        const badge = document.getElementById('zoneCountBadge');
        if (badge) badge.textContent = `${zones.length} zones`;

        const emptyEl = document.getElementById('zonesEmptyState');
        const wrapEl = document.getElementById('zonesTableWrap');
        const tbody = document.getElementById('zoneOccupancyTableBody');
        if (emptyEl) emptyEl.style.display = zones.length === 0 ? 'flex' : 'none';
        if (wrapEl) wrapEl.style.display = zones.length === 0 ? 'none' : 'block';
        if (!tbody) return;

        tbody.innerHTML = zones.map(z => {
            const cap = z.reviewedCapacity !== null && z.reviewedCapacity !== undefined ? String(z.reviewedCapacity) : '—';
            const cnt = z.currentCount !== null && z.currentCount !== undefined ? String(z.currentCount) : '—';
            const pct = z.occupancyPct !== null && z.occupancyPct !== undefined ? `${z.occupancyPct.toFixed(1)}%` : '—';
            const dens = z.currentDensityPsm !== null && z.currentDensityPsm !== undefined
                ? `<div class="text-muted" style="font-size:11px;">${z.currentDensityPsm.toFixed(2)} p/m²</div>` : '';
            const recBy = z.recordedBy ? `<div class="text-muted" style="font-size:11px;">${escapeHtml(z.recordedBy)}</div>` : '';
            const staleBadge = z.stale ? ` <span class="badge badge-low">STALE</span>` : '';
            return `<tr>
                <td><strong class="mono">${escapeHtml(z.zoneCode)}</strong><div class="text-secondary" style="font-size:12px;">${escapeHtml(z.zoneName)}</div></td>
                <td class="num">${cap}</td>
                <td class="num">${cnt}</td>
                <td class="num"><span>${pct}</span>${dens}</td>
                <td>
                    <span class="badge ${escapeHtml(z.sourceBadgeClass)}">${escapeHtml(z.sourceShortLabel)}</span>
                    <div class="mono text-muted" style="font-size:11px;margin-top:2px;">${escapeHtml(formatUtc(z.lastUpdatedAt))}</div>
                    ${recBy}
                </td>
                <td><div class="badge-row"><span class="badge ${escapeHtml(z.bandBadgeClass)}">${escapeHtml(z.bandLabel)}</span>${staleBadge}</div></td>
            </tr>`;
        }).join('');
    }

    function updatePassesTable() {
        const ps = state.passSummary || { checkedInCount: 0, checkedOutCount: 0, totalIssued: 0 };
        const inPill = document.getElementById('passCheckedInPill');
        const outPill = document.getElementById('passCheckedOutPill');
        const totPill = document.getElementById('passIssuedPill');
        if (inPill) inPill.textContent = `${ps.checkedInCount} IN`;
        if (outPill) outPill.textContent = `${ps.checkedOutCount} OUT`;
        if (totPill) totPill.textContent = `${ps.totalIssued} TOTAL`;

        const passes = Array.isArray(state.recentPasses) ? state.recentPasses : [];
        const emptyEl = document.getElementById('passesEmptyState');
        const wrapEl = document.getElementById('passesTableWrap');
        const tbody = document.getElementById('passesTableBody');
        if (emptyEl) emptyEl.style.display = passes.length === 0 ? 'flex' : 'none';
        if (wrapEl) wrapEl.style.display = passes.length === 0 ? 'none' : 'block';
        if (!tbody) return;

        tbody.innerHTML = passes.map(p => `<tr>
            <td class="mono" style="font-weight:600;">${escapeHtml(p.passCode)}</td>
            <td>${escapeHtml(p.holderLabel || 'Attendee')}</td>
            <td><span class="badge ${escapeHtml(p.statusBadgeClass)}">${escapeHtml(p.statusLabel)}</span></td>
            <td>${escapeHtml(p.lastGateLabel || '—')}${p.lastZoneLabel ? `<div class="text-muted" style="font-size:11px;">${escapeHtml(p.lastZoneLabel)}</div>` : ''}</td>
            <td class="mono" style="font-size:12px;">${escapeHtml(formatUtc(p.lastScannedAt))}</td>
            <td><button type="button" class="btn btn-ghost btn-sm js-view-qr"
                data-pass-code="${escapeHtml(p.passCode)}"
                data-holder="${escapeHtml(p.holderLabel || 'Attendee')}"
                data-qr-url="${escapeHtml(p.qrImageUrl)}">View QR</button></td>
        </tr>`).join('');
    }

    function updateAlertsFeed() {
        const container = document.getElementById('activeAlertsContainer');
        if (!container) return;
        const active = Array.isArray(state.activeAlerts) ? state.activeAlerts : [];
        if (active.length === 0) {
            container.innerHTML = '<div class="banner banner-info">No active monitoring alerts for this mode right now.</div>';
        } else {
            container.innerHTML = active.map(a => {
                const cls = a.severity === 'CRITICAL' ? 'is-critical' : (a.severity === 'LOW' ? 'is-low' : '');
                const ackBtn = canAck && !a.acknowledgedBy
                    ? `<button type="button" class="btn btn-ghost btn-sm js-ack-alert" data-alert-id="${a.id}">Acknowledge</button>`
                    : '';
                const resBtn = canEdit
                    ? `<button type="button" class="btn btn-secondary btn-sm js-resolve-alert" data-alert-id="${a.id}">Resolve</button>`
                    : '';
                const ackText = a.acknowledgedBy ? `Acknowledged by ${escapeHtml(a.acknowledgedBy)}` : 'Unacknowledged';
                return `<div class="alert-feed-item ${cls}">
                    <div class="badge-row" style="justify-content:space-between;">
                        <div class="badge-row">
                            <span class="badge ${escapeHtml(a.severityBadgeClass)}">${escapeHtml(a.severity)}</span>
                            <span class="badge ${escapeHtml(a.sourceBadgeClass)}">${escapeHtml(a.sourceShortLabel)}</span>
                            <span class="badge badge-neutral">${escapeHtml(a.alertTypeLabel)}</span>
                        </div>
                        <span class="mono text-muted" style="font-size:11px;">${escapeHtml(formatUtc(a.triggeredAt))}</span>
                    </div>
                    <p style="font-size:13px;">${escapeHtml(a.message)}</p>
                    <div class="badge-row" style="justify-content:space-between;font-size:11px;">
                        <span class="text-muted">${ackText}</span>
                        <div class="filter-actions">${ackBtn}${resBtn}</div>
                    </div>
                </div>`;
            }).join('');
        }
    }

    function updateRulesList() {
        const container = document.getElementById('alertRulesList');
        if (!container) return;
        const rules = Array.isArray(state.alertRules) ? state.alertRules : [];
        container.innerHTML = rules.map(r => {
            const removeBtn = canEdit && !r.defaultRule && r.id
                ? `<button type="button" class="btn btn-ghost btn-sm js-delete-rule" data-rule-id="${r.id}" style="color:var(--sev-critical);">Remove</button>`
                : '';
            return `<div class="asset-item">
                <div>
                    <strong class="mono">${escapeHtml(r.zoneCode)}</strong>
                    <div class="text-secondary" style="font-size:12px;">${escapeHtml(r.zoneName)}</div>
                    <div class="mono text-muted" style="font-size:11px;">
                        Warn: ${Math.round(r.warningThresholdPct)}% · Crit: ${Math.round(r.criticalThresholdPct)}% · Stale: ${r.staleAfterMinutes} min
                    </div>
                </div>
                ${removeBtn}
            </div>`;
        }).join('');
    }

    function updateGateList() {
        const container = document.getElementById('gateStatusList');
        if (!container || !Array.isArray(state.gates) || state.gates.length === 0) return;
        const rows = state.gates.map(g => `<div class="asset-item">
            <div>
                <strong>${escapeHtml(g.label)}</strong>
                <div class="text-muted" style="font-size:11px;">${escapeHtml(g.typeName)}</div>
            </div>
            <div class="mono" style="text-align:right;font-size:11px;">
                <span>${g.checkedInCount} in · ${g.checkedOutCount} out</span>
                <div class="text-muted">${escapeHtml(formatUtc(g.lastScannedAt))}</div>
            </div>
        </div>`).join('');
        container.innerHTML = '<div class="form-label" style="margin-bottom:0;">Active Gates &amp; Checkpoints</div>' + rows;
    }

    async function refreshState(modeOverride = activeMode, sourceOverride = activeSource) {
        try {
            const next = await apiGet(`/api/events/${eventId}/live?mode=${encodeURIComponent(modeOverride)}&source=${encodeURIComponent(sourceOverride)}`);
            applyState(next);
        } catch (err) {
            showFeedback(err.message, true);
        }
    }

    // Intercept mode & source switchers for snappy SPA updates (preserving URL query params)
    document.getElementById('modeLiveBtn')?.addEventListener('click', (e) => {
        e.preventDefault();
        stopPracticePlay();
        const src = activeSource === 'PASS_SCAN' ? 'PASS_SCAN' : 'MANUAL_STAFF';
        history.replaceState(null, '', `/events/${eventId}/live?mode=LIVE&source=${src}`);
        refreshState('LIVE', src);
    });

    document.getElementById('modePracticeBtn')?.addEventListener('click', (e) => {
        e.preventDefault();
        history.replaceState(null, '', `/events/${eventId}/live?mode=PRACTICE`);
        refreshState('PRACTICE', 'PRACTICE_SIMULATION');
    });

    document.getElementById('sourceManualBtn')?.addEventListener('click', (e) => {
        e.preventDefault();
        history.replaceState(null, '', `/events/${eventId}/live?mode=LIVE&source=MANUAL_STAFF`);
        refreshState('LIVE', 'MANUAL_STAFF');
    });

    document.getElementById('sourcePassBtn')?.addEventListener('click', (e) => {
        e.preventDefault();
        history.replaceState(null, '', `/events/${eventId}/live?mode=LIVE&source=PASS_SCAN`);
        refreshState('LIVE', 'PASS_SCAN');
    });

    // AJAX Form Handlers (keeping native form POST fallback intact)
    document.getElementById('manualCountForm')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const zoneId = Number(document.getElementById('manualZoneSelect').value);
        const countValue = Number(document.getElementById('manualCountInput').value);
        const note = document.getElementById('manualNoteInput').value;
        try {
            const next = await apiPost(`/api/events/${eventId}/live/readings`, { zoneId, countValue, note });
            document.getElementById('manualCountInput').value = '';
            document.getElementById('manualNoteInput').value = '';
            applyState(next);
            showFeedback('Manual zone count recorded (MANUAL_STAFF).');
        } catch (err) {
            showFeedback(err.message, true);
        }
    });

    document.getElementById('passBatchForm')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const count = Number(document.getElementById('batchCountInput').value);
        const labelPrefix = document.getElementById('batchPrefixInput').value;
        try {
            const next = await apiPost(`/api/events/${eventId}/live/passes/batch`, { count, labelPrefix });
            applyState(next);
            showFeedback(`Generated ${count} unique attendee passes with QR codes.`);
        } catch (err) {
            showFeedback(err.message, true);
        }
    });

    document.getElementById('passScanForm')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const passCode = document.getElementById('scanPassCodeInput').value;
        const action = document.getElementById('scanActionSelect').value;
        const gateVal = document.getElementById('scanGateSelect').value;
        const zoneVal = document.getElementById('scanZoneSelect').value;
        try {
            const next = await apiPost(`/api/events/${eventId}/live/passes/scan`, {
                passCode,
                action,
                gateId: gateVal ? Number(gateVal) : null,
                zoneId: zoneVal ? Number(zoneVal) : null
            });
            document.getElementById('scanPassCodeInput').value = '';
            applyState(next);
            showFeedback(`Pass scan (${action}) recorded via PASS_SCAN.`);
        } catch (err) {
            showFeedback(err.message, true);
            await refreshState('LIVE', activeSource);
        }
    });

    document.getElementById('scanQrImageInput')?.addEventListener('change', async (e) => {
        const file = e.target.files?.[0];
        if (!file) return;
        const formData = new FormData();
        formData.append('file', file);
        formData.append('action', document.getElementById('scanActionSelect').value);
        const gateVal = document.getElementById('scanGateSelect').value;
        const zoneVal = document.getElementById('scanZoneSelect').value;
        if (gateVal) formData.append('gateId', gateVal);
        if (zoneVal) formData.append('zoneId', zoneVal);
        try {
            const next = await apiUpload(`/api/events/${eventId}/live/passes/scan-qr`, formData);
            applyState(next);
            showFeedback('QR image decoded and pass scan recorded.');
        } catch (err) {
            showFeedback(err.message, true);
            await refreshState('LIVE', activeSource);
        } finally {
            e.target.value = '';
        }
    });

    document.getElementById('alertRuleForm')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const zoneVal = document.getElementById('ruleZoneSelect').value;
        const warningThresholdPct = Number(document.getElementById('ruleWarnInput').value);
        const criticalThresholdPct = Number(document.getElementById('ruleCritInput').value);
        const staleAfterMinutes = Number(document.getElementById('ruleStaleInput').value);
        try {
            const next = await apiPut(`/api/events/${eventId}/live/rules`, {
                zoneId: zoneVal ? Number(zoneVal) : null,
                warningThresholdPct,
                criticalThresholdPct,
                staleAfterMinutes
            });
            applyState(next);
            showFeedback('Alert threshold and staleness rule saved.');
        } catch (err) {
            showFeedback(err.message, true);
        }
    });

    document.getElementById('checkStalenessBtn')?.addEventListener('click', async () => {
        try {
            const next = await apiPost(`/api/events/${eventId}/live/check-staleness?mode=${encodeURIComponent(activeMode)}&source=${encodeURIComponent(activeSource)}`, {});
            applyState(next);
            showFeedback('Threshold and staleness checks evaluated.');
        } catch (err) {
            showFeedback(err.message, true);
        }
    });

    // Delegated actions for QR modal, alert ack/resolve, and rule delete
    root.addEventListener('click', async (e) => {
        const qrBtn = e.target.closest('.js-view-qr');
        if (qrBtn) {
            const dialog = document.getElementById('passQrDialog');
            const img = document.getElementById('qrDialogImage');
            const codeEl = document.getElementById('qrDialogCode');
            const holderEl = document.getElementById('qrDialogHolder');
            const dlLink = document.getElementById('qrDialogDownloadLink');
            if (dialog && img) {
                img.src = qrBtn.dataset.qrUrl;
                codeEl.textContent = qrBtn.dataset.passCode;
                holderEl.textContent = qrBtn.dataset.holder || 'Attendee';
                dlLink.href = qrBtn.dataset.qrUrl;
                dlLink.download = `${qrBtn.dataset.passCode}.png`;
                dialog.showModal();
            }
            return;
        }

        const ackBtn = e.target.closest('.js-ack-alert');
        if (ackBtn) {
            e.preventDefault();
            try {
                const next = await apiPost(`/api/events/${eventId}/live/alerts/${ackBtn.dataset.alertId}/acknowledge`, {});
                applyState(next);
            } catch (err) {
                showFeedback(err.message, true);
            }
            return;
        }

        const resBtn = e.target.closest('.js-resolve-alert');
        if (resBtn) {
            e.preventDefault();
            try {
                const next = await apiPost(`/api/events/${eventId}/live/alerts/${resBtn.dataset.alertId}/resolve`, {});
                applyState(next);
            } catch (err) {
                showFeedback(err.message, true);
            }
            return;
        }

        const delRuleBtn = e.target.closest('.js-delete-rule');
        if (delRuleBtn) {
            e.preventDefault();
            try {
                const next = await apiDelete(`/api/events/${eventId}/live/rules/${delRuleBtn.dataset.ruleId}`);
                applyState(next);
            } catch (err) {
                showFeedback(err.message, true);
            }
        }
    });

    document.getElementById('closeQrDialogBtn')?.addEventListener('click', () => {
        document.getElementById('passQrDialog')?.close();
    });

    // Practice Simulation Step / Play / Clear
    async function stepPractice(resetFirst = false) {
        const runVal = document.getElementById('practiceRunSelect')?.value;
        try {
            const res = await apiPost(`/api/events/${eventId}/live/practice/step`, {
                runId: runVal ? Number(runVal) : null,
                frameIndex: practiceFrameIdx,
                resetFirst
            });
            const infoEl = document.getElementById('practiceFrameInfo');
            if (infoEl) {
                infoEl.textContent = `Frame ${res.frameIndex + 1}/${res.totalFrames} (t=${Math.round(res.frameTimeSec)}s)`;
            }
            practiceFrameIdx = (res.frameIndex + 1) % Math.max(1, res.totalFrames);
            if (res.controlCentre) {
                applyState(res.controlCentre);
            }
        } catch (err) {
            stopPracticePlay();
            showFeedback(err.message, true);
        }
    }

    function stopPracticePlay() {
        if (practiceTimer) {
            clearInterval(practiceTimer);
            practiceTimer = null;
            const playBtn = document.getElementById('practicePlayBtn');
            if (playBtn) playBtn.textContent = 'Play Drill';
        }
    }

    document.getElementById('practiceStepBtn')?.addEventListener('click', () => stepPractice(false));
    document.getElementById('practicePlayBtn')?.addEventListener('click', (e) => {
        if (practiceTimer) {
            stopPracticePlay();
            return;
        }
        e.target.textContent = 'Pause Drill';
        stepPractice(true);
        practiceTimer = setInterval(() => stepPractice(false), 1800);
    });

    document.getElementById('practiceClearBtn')?.addEventListener('click', async () => {
        stopPracticePlay();
        practiceFrameIdx = 0;
        try {
            const next = await apiDelete(`/api/events/${eventId}/live/practice`);
            const infoEl = document.getElementById('practiceFrameInfo');
            if (infoEl) infoEl.textContent = 'Frame 0';
            applyState(next);
            showFeedback('Practice simulation readings and alerts cleared.');
        } catch (err) {
            showFeedback(err.message, true);
        }
    });

    // Server-Sent Events (SSE) with automatic JSON polling fallback
    let pollInterval = null;
    function startPollingFallback() {
        if (pollInterval) return;
        if (ssePillEl) {
            ssePillEl.className = 'sse-pill';
            ssePillEl.textContent = 'Polling Fallback (10s)';
        }
        pollInterval = setInterval(() => refreshState(activeMode, activeSource), 10000);
    }

    function connectSse() {
        if (typeof EventSource === 'undefined') {
            startPollingFallback();
            return;
        }
        const source = new EventSource(`/api/events/${eventId}/live/stream`);
        source.addEventListener('connected', () => {
            if (ssePillEl) {
                ssePillEl.className = 'sse-pill is-live';
                ssePillEl.textContent = 'SSE Stream Live';
            }
            if (pollInterval) {
                clearInterval(pollInterval);
                pollInterval = null;
            }
        });
        source.addEventListener('update', () => {
            refreshState(activeMode, activeSource);
        });
        source.addEventListener('alert', (evt) => {
            try {
                const payload = JSON.parse(evt.data);
                if (payload && payload.message) {
                    showFeedback(`Alert [${payload.dataSource}]: ${payload.message}`,
                        payload.severity === 'CRITICAL' || payload.severity === 'HIGH');
                }
            } catch (_) {
            }
            refreshState(activeMode, activeSource);
        });
        source.onerror = () => {
            startPollingFallback();
        };
    }

    ensureBlueprintLoaded();
    fitHeatmapBounds();
    core.requestRender();
    connectSse();
}
