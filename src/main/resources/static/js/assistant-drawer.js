import { apiGet, apiPost } from './api-client.js';

const drawer = document.getElementById('planningAssistantDrawer');
if (drawer) {
    const eventId = drawer.dataset.eventId;
    const statusBanner = document.getElementById('assistantStatusBanner');
    const closeBtn = document.getElementById('assistantCloseBtn');
    const tabBtns = drawer.querySelectorAll('[data-assistant-tab]');
    const panes = drawer.querySelectorAll('[data-assistant-pane]');

    // Explain tab elements
    const explainFocus = document.getElementById('assistantExplainFocus');
    const explainPrompt = document.getElementById('assistantExplainPrompt');
    const explainRunBtn = document.getElementById('assistantExplainRunBtn');
    const explainOutput = document.getElementById('assistantExplainOutput');
    const explainMetrics = document.getElementById('assistantExplainMetrics');
    const explainSummary = document.getElementById('assistantExplainSummary');
    const explainItems = document.getElementById('assistantExplainItems');

    // What-If tab elements
    const whatIfPrompt = document.getElementById('assistantWhatIfPrompt');
    const whatIfPreviewBtn = document.getElementById('assistantWhatIfPreviewBtn');
    const whatIfPreviewPanel = document.getElementById('assistantWhatIfPreviewPanel');
    const whatIfConfidence = document.getElementById('assistantWhatIfConfidence');
    const whatIfConfirmationText = document.getElementById('assistantWhatIfConfirmationText');
    const whatIfNotes = document.getElementById('assistantWhatIfNotes');
    const confirmScenario = document.getElementById('assistantConfirmScenario');
    const confirmExitGroup = document.getElementById('assistantConfirmExitGroup');
    const confirmExitSelect = document.getElementById('assistantConfirmExitSelect');
    const confirmEdgeGroup = document.getElementById('assistantConfirmEdgeGroup');
    const confirmEdgeSelect = document.getElementById('assistantConfirmEdgeSelect');
    const confirmAgents = document.getElementById('assistantConfirmAgents');
    const confirmDuration = document.getElementById('assistantConfirmDuration');
    const confirmSeed = document.getElementById('assistantConfirmSeed');
    const confirmDisruptionSec = document.getElementById('assistantConfirmDisruptionSec');
    const whatIfConfirmBtn = document.getElementById('assistantWhatIfConfirmBtn');
    const whatIfCancelBtn = document.getElementById('assistantWhatIfCancelBtn');

    const whatIfResultPanel = document.getElementById('assistantWhatIfResultPanel');
    const whatIfDisclaimer = document.getElementById('assistantWhatIfDisclaimer');
    const whatIfSummary = document.getElementById('assistantWhatIfSummary');
    const whatIfDeltas = document.getElementById('assistantWhatIfDeltas');
    const whatIfTakeaways = document.getElementById('assistantWhatIfTakeaways');
    const replayLink = document.getElementById('assistantReplayLink');
    const compareLink = document.getElementById('assistantCompareLink');
    const whatIfCitations = document.getElementById('assistantWhatIfCitations');

    // Guideline search elements
    const guidelineQuery = document.getElementById('assistantGuidelineQuery');
    const guidelineCategory = document.getElementById('assistantGuidelineCategory');
    const guidelineSearchBtn = document.getElementById('assistantGuidelineSearchBtn');
    const guidelineResults = document.getElementById('assistantGuidelineResults');

    // History elements
    const historyDetails = document.getElementById('assistantHistoryDetails');
    const historyList = document.getElementById('assistantHistoryList');

    let latestPreview = null;
    let initialLoaded = false;

    function setError(message) {
        if (!message) {
            statusBanner.style.display = 'none';
            statusBanner.textContent = '';
            return;
        }
        statusBanner.textContent = message;
        statusBanner.style.display = 'block';
    }

    function openDrawer() {
        drawer.hidden = false;
        drawer.setAttribute('aria-hidden', 'false');
        if (!initialLoaded) {
            initialLoaded = true;
            loadHistory();
            runExplain();
        }
    }

    function closeDrawer() {
        drawer.hidden = true;
        drawer.setAttribute('aria-hidden', 'true');
    }

    function toggleDrawer() {
        if (drawer.hidden) {
            openDrawer();
        } else {
            closeDrawer();
        }
    }

    function switchTab(target) {
        tabBtns.forEach(btn => {
            const active = btn.dataset.assistantTab === target;
            btn.classList.toggle('active', active);
            btn.setAttribute('aria-selected', active ? 'true' : 'false');
        });
        panes.forEach(pane => {
            pane.classList.toggle('active', pane.dataset.assistantPane === target);
        });
        setError('');
    }

    document.querySelectorAll('[data-assistant-toggle]').forEach(btn => {
        btn.addEventListener('click', toggleDrawer);
    });
    if (closeBtn) {
        closeBtn.addEventListener('click', closeDrawer);
    }
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !drawer.hidden) {
            closeDrawer();
        }
    });

    tabBtns.forEach(btn => {
        btn.addEventListener('click', () => switchTab(btn.dataset.assistantTab));
    });

    // Explain Findings
    async function runExplain() {
        setError('');
        explainRunBtn.disabled = true;
        explainRunBtn.textContent = 'Analyzing...';
        try {
            const resp = await apiPost(`/api/events/${eventId}/assistant/explain`, {
                focusArea: explainFocus.value,
                prompt: explainPrompt.value.trim()
            });
            renderExplainResult(resp);
            loadHistory();
        } catch (err) {
            setError(err.message);
        } finally {
            explainRunBtn.disabled = false;
            explainRunBtn.textContent = 'Explain Current Layout';
        }
    }

    function renderExplainResult(resp) {
        explainOutput.style.display = 'flex';
        const m = resp.verifiedMetrics || {};
        explainMetrics.innerHTML = [
            `<span>Usable: <strong>${Number(m.usableAreaSqm || 0).toFixed(1)} m²</strong></span>`,
            `<span>Cap: <strong>${m.preliminaryCapacity != null ? m.preliminaryCapacity : '—'}</strong></span>`,
            `<span>Conflicts: <strong>${m.activeConflictCount || 0}</strong></span>`,
            `<span>Bottlenecks: <strong>${m.activeBottleneckCount || 0}</strong></span>`
        ].join(' · ');

        explainSummary.innerHTML = `<strong>${escapeHtml(resp.attributionTag)}</strong><br>${escapeHtml(resp.executiveSummary)}`;

        const items = resp.recommendations || [];
        if (items.length === 0) {
            explainItems.innerHTML = '<div class="assistant-card text-secondary" style="font-size:12px;">No findings matched this focus filter. Human professional safety review remains required.</div>';
            return;
        }

        explainItems.innerHTML = items.map(item => {
            const sevClass = severityBadgeClass(item.severity);
            const stepsHtml = (item.manualRemediationSteps || [])
                .map(s => `<li>${escapeHtml(s)}</li>`)
                .join('');
            const citeHtml = (item.citations || [])
                .map(c => `<div class="assistant-quote"><strong>${escapeHtml(c.documentTitle)} (${escapeHtml(c.sectionNumber)})</strong>: &ldquo;${escapeHtml(c.verbatimQuote)}&rdquo;</div>`)
                .join('');
            return `
                <article class="assistant-card">
                    <div class="assistant-card-header">
                        <span class="badge ${sevClass}">${escapeHtml(item.severity)}</span>
                        <span class="badge badge-neutral">${escapeHtml(item.sourceModule)}</span>
                    </div>
                    <div style="font-weight:600; font-size:13px;">${escapeHtml(item.title)}</div>
                    <div class="mono text-secondary" style="font-size:11px;">${escapeHtml(item.measuredEvidence)}</div>
                    <p style="font-size:12px; color:var(--text-secondary);">${escapeHtml(item.plainLanguageExplanation)}</p>
                    <ul class="assistant-steps">${stepsHtml}</ul>
                    ${citeHtml}
                    <div style="display:flex; justify-content:space-between; align-items:center; gap:6px; margin-top:4px;">
                        <a href="${escapeHtml(item.navigateUrl)}" class="btn btn-secondary btn-sm">Open Workspace Module</a>
                    </div>
                </article>
            `;
        }).join('');
    }

    explainRunBtn.addEventListener('click', runExplain);

    // What-If Scenario
    drawer.querySelectorAll('[data-whatif-sample]').forEach(chip => {
        chip.addEventListener('click', () => {
            whatIfPrompt.value = chip.dataset.whatifSample || '';
            whatIfPrompt.focus();
        });
    });

    async function previewWhatIf() {
        const prompt = whatIfPrompt.value.trim();
        if (!prompt) {
            setError('Enter a plain-English What-If scenario prompt first.');
            return;
        }
        setError('');
        whatIfPreviewBtn.disabled = true;
        whatIfPreviewBtn.textContent = 'Parsing...';
        try {
            const preview = await apiPost(`/api/events/${eventId}/assistant/what-if/preview`, { prompt });
            latestPreview = preview;
            renderWhatIfPreview(preview);
            loadHistory();
        } catch (err) {
            setError(err.message);
        } finally {
            whatIfPreviewBtn.disabled = false;
            whatIfPreviewBtn.textContent = 'Parse & Preview Scenario';
        }
    }

    function renderWhatIfPreview(preview) {
        whatIfPreviewPanel.style.display = 'flex';
        whatIfResultPanel.style.display = 'none';

        whatIfConfidence.textContent = preview.confidence || 'MEDIUM';
        whatIfConfidence.className = 'badge ' + (preview.confidence === 'HIGH'
            ? 'badge-verified'
            : (preview.confidence === 'LOW' ? 'badge-low' : 'badge-info'));

        whatIfConfirmationText.textContent = preview.confirmationSummary || '';
        whatIfNotes.innerHTML = (preview.parsingNotes || [])
            .map(n => `<li>${escapeHtml(n)}</li>`)
            .join('');

        const cfg = preview.proposedConfig || {};
        confirmScenario.value = preview.parsedScenarioType || 'NORMAL_OPERATIONS';

        confirmExitSelect.innerHTML = (preview.availableExits || []).map(ex =>
            `<option value="${escapeHtml(ex.uid)}" data-entity-id="${ex.entityId || ''}">${escapeHtml(ex.label)} — ${escapeHtml(ex.detail)}</option>`
        ).join('') || '<option value="">No exits found</option>';
        if (cfg.targetExitNodeUid) {
            confirmExitSelect.value = cfg.targetExitNodeUid;
        }

        confirmEdgeSelect.innerHTML = (preview.availableCorridors || []).map(edge =>
            `<option value="${escapeHtml(edge.uid)}" data-entity-id="${edge.entityId || ''}">${escapeHtml(edge.label)} — ${escapeHtml(edge.detail)}</option>`
        ).join('') || '<option value="">No active corridors found</option>';
        if (cfg.targetEdgeUid) {
            confirmEdgeSelect.value = cfg.targetEdgeUid;
        }

        confirmAgents.value = cfg.agentCount || 350;
        confirmDuration.value = cfg.durationSeconds || 300;
        confirmSeed.value = cfg.randomSeed != null ? cfg.randomSeed : 42;
        confirmDisruptionSec.value = cfg.disruptionStartSec != null ? cfg.disruptionStartSec : 0;

        syncConfirmTargetVisibility();
        whatIfConfirmBtn.disabled = !preview.layoutReadyForSimulation;
        if (!preview.layoutReadyForSimulation) {
            setError(preview.readinessMessage);
        }
    }

    function syncConfirmTargetVisibility() {
        const sc = confirmScenario.value;
        confirmExitGroup.style.display = sc === 'CLOSED_EXIT' ? 'flex' : 'none';
        confirmEdgeGroup.style.display = sc === 'BLOCKED_CORRIDOR' ? 'flex' : 'none';
    }

    confirmScenario.addEventListener('change', syncConfirmTargetVisibility);
    whatIfPreviewBtn.addEventListener('click', previewWhatIf);
    whatIfCancelBtn.addEventListener('click', () => {
        whatIfPreviewPanel.style.display = 'none';
        latestPreview = null;
    });

    whatIfConfirmBtn.addEventListener('click', async () => {
        if (!latestPreview) return;
        setError('');
        whatIfConfirmBtn.disabled = true;
        whatIfConfirmBtn.textContent = 'Running Simulation...';

        const selectedExitOpt = confirmExitSelect.options[confirmExitSelect.selectedIndex];
        const selectedEdgeOpt = confirmEdgeSelect.options[confirmEdgeSelect.selectedIndex];

        try {
            const payload = {
                interactionLogId: latestPreview.interactionLogId,
                confirmed: true,
                scenarioType: confirmScenario.value,
                snapshotId: latestPreview.targetSnapshotId,
                baselineRunId: latestPreview.baselineRunId,
                randomSeed: Number(confirmSeed.value || 42),
                agentCount: Number(confirmAgents.value || 350),
                durationSeconds: Number(confirmDuration.value || 300),
                targetExitNodeUid: confirmScenario.value === 'CLOSED_EXIT' ? (confirmExitSelect.value || null) : null,
                targetExitInfraId: confirmScenario.value === 'CLOSED_EXIT' && selectedExitOpt?.dataset.entityId
                    ? Number(selectedExitOpt.dataset.entityId) : null,
                targetEdgeUid: confirmScenario.value === 'BLOCKED_CORRIDOR' ? (confirmEdgeSelect.value || null) : null,
                targetEdgeId: confirmScenario.value === 'BLOCKED_CORRIDOR' && selectedEdgeOpt?.dataset.entityId
                    ? Number(selectedEdgeOpt.dataset.entityId) : null,
                disruptionStartSec: Number(confirmDisruptionSec.value || 0)
            };

            const res = await apiPost(`/api/events/${eventId}/assistant/what-if/confirm`, payload);
            renderWhatIfExecution(res);
            loadHistory();
        } catch (err) {
            setError(err.message);
        } finally {
            whatIfConfirmBtn.disabled = false;
            whatIfConfirmBtn.textContent = 'Confirm & Run';
        }
    });

    function renderWhatIfExecution(res) {
        whatIfPreviewPanel.style.display = 'none';
        whatIfResultPanel.style.display = 'flex';

        whatIfDisclaimer.textContent = res.disclaimer || '';
        whatIfSummary.innerHTML = `
            <div style="font-weight:600; font-size:11px; color:var(--text-secondary); margin-bottom:4px;">${escapeHtml(res.attributionTag)}</div>
            <p>${escapeHtml(res.plainLanguageSummary)}</p>
        `;

        const c = res.comparison || {};
        const deltas = [c.peakDensity, c.peakQueueLength, c.maxQueueWaitTime, c.unservedBlockedAgents].filter(Boolean);
        whatIfDeltas.innerHTML = deltas.map(d => {
            const sign = d.delta > 0 ? '+' : '';
            const badgeCls = d.trend === 'IMPROVED' ? 'badge-verified' : (d.trend === 'WORSE' ? 'badge-critical' : 'badge-neutral');
            return `
                <div class="assistant-delta-item">
                    <div style="display:flex; justify-content:space-between; gap:4px; align-items:center;">
                        <span class="text-muted" style="font-size:11px;">${escapeHtml(d.label)}</span>
                        <span class="badge ${badgeCls}">${escapeHtml(d.trend)}</span>
                    </div>
                    <div class="mono" style="font-weight:700; font-size:13px;">${sign}${Number(d.delta).toFixed(2)} ${escapeHtml(d.unit)}</div>
                    <div class="mono text-secondary" style="font-size:11px;">${Number(d.beforeValue).toFixed(2)} &rarr; ${Number(d.afterValue).toFixed(2)}</div>
                </div>
            `;
        }).join('');

        const takeaways = (res.keyTakeaways || []).map(t => `<li>${escapeHtml(t)}</li>`).join('');
        whatIfTakeaways.innerHTML = `<strong style="font-size:12px;">Comparison Notes</strong><ul class="assistant-notes-list">${takeaways}</ul>`;

        replayLink.href = res.replayUrl || `/events/${eventId}/simulation`;
        compareLink.href = res.compareUrl || `/events/${eventId}/simulation`;

        whatIfCitations.innerHTML = (res.citations || [])
            .map(hit => `<div class="assistant-quote"><strong>${escapeHtml(hit.documentTitle)} (${escapeHtml(hit.sectionNumber)})</strong>: &ldquo;${escapeHtml(hit.verbatimQuote)}&rdquo;</div>`)
            .join('');
    }

    // Guideline Search
    async function runGuidelineSearch() {
        setError('');
        const q = encodeURIComponent(guidelineQuery.value.trim());
        const cat = encodeURIComponent(guidelineCategory.value || 'ALL');
        guidelineSearchBtn.disabled = true;
        try {
            const result = await apiGet(`/api/events/${eventId}/assistant/guidelines?q=${q}&category=${cat}&limit=8`);
            renderGuidelineHits(result);
            if (guidelineQuery.value.trim()) {
                loadHistory();
            }
        } catch (err) {
            setError(err.message);
        } finally {
            guidelineSearchBtn.disabled = false;
        }
    }

    function renderGuidelineHits(result) {
        const hits = result?.hits || [];
        if (hits.length === 0) {
            guidelineResults.innerHTML = '<div class="assistant-card text-muted" style="font-size:12px;">No matching safety guideline sections found. Try broader terms like width, exit, queue, or ambulance.</div>';
            return;
        }
        guidelineResults.innerHTML = hits.map(h => `
            <article class="assistant-card">
                <div class="assistant-card-header">
                    <span class="badge badge-info">${escapeHtml(h.sectionNumber)}</span>
                    <span class="mono text-muted" style="font-size:11px;">Score ${Number(h.score || 0).toFixed(2)}</span>
                </div>
                <div style="font-weight:600; font-size:13px;">${escapeHtml(h.sectionTitle)}</div>
                <div class="text-muted" style="font-size:11px;">${escapeHtml(h.documentTitle)} · ${escapeHtml(h.authority)} (${h.publicationYear})</div>
                <p style="font-size:12px; color:var(--text-secondary);">${escapeHtml(h.summary)}</p>
                <blockquote class="assistant-quote">&ldquo;${escapeHtml(h.verbatimQuote)}&rdquo;</blockquote>
            </article>
        `).join('');
    }

    guidelineSearchBtn.addEventListener('click', runGuidelineSearch);
    guidelineQuery.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            runGuidelineSearch();
        }
    });

    // History
    async function loadHistory() {
        try {
            const logs = await apiGet(`/api/events/${eventId}/assistant/history`);
            if (!logs || logs.length === 0) {
                historyList.innerHTML = '<p class="text-muted" style="font-size:12px;">No assistant interactions logged yet.</p>';
                return;
            }
            historyList.innerHTML = logs.slice(0, 8).map(item => `
                <div class="assistant-card" style="padding:8px;">
                    <div class="assistant-card-header">
                        <span class="badge ${item.confirmedByUser ? 'badge-verified' : 'badge-neutral'}">${escapeHtml(item.modeDisplayName)}</span>
                        <span class="mono text-muted" style="font-size:10px;">${escapeHtml(item.userName || '')}</span>
                    </div>
                    <div style="font-size:12px; font-weight:500;">${escapeHtml(item.prompt)}</div>
                    <div class="text-secondary" style="font-size:11px;">${escapeHtml(item.responseExcerpt)}</div>
                </div>
            `).join('');
        } catch (ignored) {
        }
    }

    function severityBadgeClass(sev) {
        switch ((sev || '').toUpperCase()) {
            case 'CRITICAL': return 'badge-critical';
            case 'HIGH': return 'badge-high';
            case 'LOW': return 'badge-low';
            default: return 'badge-info';
        }
    }

    function escapeHtml(str) {
        return String(str ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }
}
