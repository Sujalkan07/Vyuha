import { apiGet } from './api-client.js';

const root = document.getElementById('guidelinesPageRoot');
if (root) {
    const form = document.getElementById('guidelineSearchForm');
    const queryInput = document.getElementById('guidelineQueryInput');
    const categorySelect = document.getElementById('guidelineCategorySelect');
    const matchSummary = document.getElementById('guidelineMatchSummary');
    const resultsList = document.getElementById('guidelineResultsList');

    async function executeSearch() {
        const q = encodeURIComponent(queryInput.value.trim());
        const cat = encodeURIComponent(categorySelect.value || 'ALL');
        const result = await apiGet(`/api/guidelines/search?q=${q}&category=${cat}&limit=25`);
        renderResults(result);
    }

    function renderResults(result) {
        const hits = result?.hits || [];
        matchSummary.textContent = `${result?.totalMatches ?? hits.length} match(es)`;
        if (hits.length === 0) {
            resultsList.innerHTML = '<div class="empty-state"><p>No guideline sections matched that search query. Try broader keywords such as width, exit, queue, or ambulance.</p></div>';
            return;
        }
        resultsList.innerHTML = hits.map(h => `
            <article class="guideline-card">
                <div class="guideline-card-top">
                    <div class="badge-row">
                        <span class="badge badge-info">${escapeHtml(h.sectionNumber)}</span>
                        <span class="badge badge-neutral">${escapeHtml(h.categoryDisplayName)}</span>
                    </div>
                    ${h.score > 0 ? `<span class="mono text-muted" style="font-size: 12px;">BM25: ${Number(h.score).toFixed(2)}</span>` : ''}
                </div>
                <h3>${escapeHtml(h.sectionTitle)}</h3>
                <div class="text-secondary" style="font-size: 12px;">
                    <strong>${escapeHtml(h.documentTitle)}</strong> · ${escapeHtml(h.authority)} <span class="mono">(${h.publicationYear})</span>
                </div>
                <p style="font-size: 13px;">${escapeHtml(h.summary)}</p>
                <blockquote class="guideline-quote">&ldquo;${escapeHtml(h.verbatimQuote)}&rdquo;</blockquote>
            </article>
        `).join('');
    }

    root.querySelectorAll('.guideline-topic-chip').forEach(btn => {
        btn.addEventListener('click', () => {
            queryInput.value = btn.dataset.query || '';
            executeSearch().catch(() => form.submit());
        });
    });

    form.addEventListener('submit', (e) => {
        e.preventDefault();
        executeSearch().catch(() => form.submit());
    });

    categorySelect.addEventListener('change', () => {
        executeSearch().catch(() => form.submit());
    });

    function escapeHtml(str) {
        return String(str ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }
}
