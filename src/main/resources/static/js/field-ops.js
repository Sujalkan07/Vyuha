import { apiPost, apiUpload } from './api-client.js';

const root = document.getElementById('fieldOpsRoot');
if (root && root.dataset.eventId) {
    const eventId = root.dataset.eventId;
    const countInput = document.getElementById('fieldCountValueInput');
    const feedbackEl = document.getElementById('fieldActionFeedback');

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
            .replace(/>/g, '&gt;');
    }

    function applyMonitoringUpdate(data) {
        if (!data) return;
        if (data.passSummary) {
            const inBadge = document.getElementById('fieldPassInBadge');
            const outBadge = document.getElementById('fieldPassOutBadge');
            if (inBadge) inBadge.textContent = `${data.passSummary.checkedInCount} IN`;
            if (outBadge) outBadge.textContent = `${data.passSummary.checkedOutCount} OUT`;
        }
        const list = document.getElementById('fieldZoneCountsList');
        if (list && Array.isArray(data.manualZones)) {
            const items = data.manualZones.map(z => {
                const cnt = z.currentCount !== null && z.currentCount !== undefined ? z.currentCount : '—';
                const staleBadge = z.stale ? '<span class="badge badge-low">STALE</span>' : '';
                return `<div class="asset-item">
                    <div>
                        <strong class="mono">${escapeHtml(z.zoneCode)}</strong>
                        <span class="text-secondary" style="font-size:12px;"> · ${escapeHtml(z.zoneName)}</span>
                        <div class="mono text-muted" style="font-size:11px;">${escapeHtml(formatUtc(z.lastUpdatedAt))}</div>
                    </div>
                    <div class="badge-row">
                        <span class="mono" style="font-weight:700;">${cnt}</span>
                        <span class="badge ${escapeHtml(z.bandBadgeClass)}">${escapeHtml(z.bandLabel)}</span>
                        ${staleBadge}
                    </div>
                </div>`;
            }).join('');
            list.innerHTML = '<div class="form-label" style="margin-bottom:0;">Latest Zone Manual Counts</div>' + items;
        }
    }

    // Numeric stepper buttons (-10, -1, +1, +10)
    document.querySelectorAll('.js-step-count').forEach(btn => {
        btn.addEventListener('click', () => {
            if (!countInput) return;
            const delta = Number(btn.dataset.delta || 0);
            const cur = Number(countInput.value || 0);
            countInput.value = String(Math.max(0, cur + delta));
        });
    });

    // AJAX manual count submission
    document.getElementById('fieldManualCountForm')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const zoneId = Number(document.getElementById('fieldCountZoneSelect').value);
        const countValue = Number(countInput.value);
        const note = document.getElementById('fieldCountNoteInput').value;
        try {
            const data = await apiPost(`/api/field/events/${eventId}/readings`, { zoneId, countValue, note });
            applyMonitoringUpdate(data);
            document.getElementById('fieldCountNoteInput').value = '';
            showFeedback(`Manual zone count ${countValue} saved (MANUAL_STAFF).`);
        } catch (err) {
            showFeedback(err.message, true);
        }
    });

    // AJAX pass code scan
    document.getElementById('fieldPassScanForm')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const passCode = document.getElementById('fieldPassCodeInput').value;
        const action = document.getElementById('fieldScanActionSelect').value;
        const gateVal = document.getElementById('fieldScanGateSelect').value;
        const zoneVal = document.getElementById('fieldScanZoneSelect').value;
        try {
            const data = await apiPost(`/api/field/events/${eventId}/passes/scan`, {
                passCode,
                action,
                gateId: gateVal ? Number(gateVal) : null,
                zoneId: zoneVal ? Number(zoneVal) : null
            });
            applyMonitoringUpdate(data);
            document.getElementById('fieldPassCodeInput').value = '';
            showFeedback(`Pass ${passCode.trim().toUpperCase()} (${action}) recorded via PASS_SCAN.`);
        } catch (err) {
            showFeedback(err.message, true);
        }
    });

    // Offline QR photo upload decoded via Java ZXing
    document.getElementById('fieldQrPhotoInput')?.addEventListener('change', async (e) => {
        const file = e.target.files?.[0];
        if (!file) return;
        const formData = new FormData();
        formData.append('file', file);
        formData.append('action', document.getElementById('fieldScanActionSelect').value);
        const gateVal = document.getElementById('fieldScanGateSelect').value;
        const zoneVal = document.getElementById('fieldScanZoneSelect').value;
        if (gateVal) formData.append('gateId', gateVal);
        if (zoneVal) formData.append('zoneId', zoneVal);
        try {
            const data = await apiUpload(`/api/field/events/${eventId}/passes/scan-qr`, formData);
            applyMonitoringUpdate(data);
            showFeedback('QR photo decoded and pass scan recorded (PASS_SCAN).');
        } catch (err) {
            showFeedback(err.message, true);
        } finally {
            e.target.value = '';
        }
    });

    // Progressive enhancement: live camera QR scanning via native browser BarcodeDetector (zero external libraries)
    const camBtn = document.getElementById('fieldCameraScanBtn');
    const videoEl = document.getElementById('fieldQrVideo');
    let activeStream = null;
    if (camBtn && videoEl && typeof window.BarcodeDetector !== 'undefined' && navigator.mediaDevices?.getUserMedia) {
        camBtn.style.display = 'inline-flex';
        camBtn.addEventListener('click', async () => {
            if (activeStream) {
                activeStream.getTracks().forEach(t => t.stop());
                activeStream = null;
                videoEl.style.display = 'none';
                camBtn.textContent = 'Live Camera QR';
                return;
            }
            try {
                activeStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
                videoEl.srcObject = activeStream;
                videoEl.style.display = 'block';
                await videoEl.play();
                camBtn.textContent = 'Stop Camera';
                const detector = new window.BarcodeDetector({ formats: ['qr_code'] });
                const tick = async () => {
                    if (!activeStream) return;
                    try {
                        const codes = await detector.detect(videoEl);
                        if (codes && codes.length > 0 && codes[0].rawValue) {
                            const codeInput = document.getElementById('fieldPassCodeInput');
                            if (codeInput) codeInput.value = codes[0].rawValue.trim().toUpperCase();
                            activeStream.getTracks().forEach(t => t.stop());
                            activeStream = null;
                            videoEl.style.display = 'none';
                            camBtn.textContent = 'Live Camera QR';
                            document.getElementById('fieldPassScanForm')?.requestSubmit();
                            return;
                        }
                    } catch (_) {
                    }
                    requestAnimationFrame(tick);
                };
                requestAnimationFrame(tick);
            } catch (err) {
                showFeedback('Camera access unavailable; upload a QR photo or enter pass code manually.', true);
            }
        });
    }
}
