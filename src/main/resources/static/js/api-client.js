/**
 * Minimal CSRF-aware REST client for Vyuha workspace modules.
 * Expects Spring Security CSRF meta tags inside <head>.
 */
function getCsrfHeaders() {
    const tokenMeta = document.querySelector('meta[name="_csrf"]');
    const headerMeta = document.querySelector('meta[name="_csrf_header"]');
    if (!tokenMeta || !headerMeta) {
        return {};
    }
    const token = tokenMeta.getAttribute('content');
    const headerName = headerMeta.getAttribute('content');
    if (!token || !headerName) {
        return {};
    }
    return { [headerName]: token };
}

async function parseApiResponse(response) {
    const contentType = response.headers.get('content-type') || '';
    let payload = null;
    if (contentType.includes('application/json')) {
        payload = await response.json();
    } else {
        const text = await response.text();
        if (!response.ok) {
            throw new Error(text || `Request failed (${response.status})`);
        }
        return text;
    }

    if (!response.ok || (payload && payload.success === false)) {
        const details = payload && Array.isArray(payload.details) && payload.details.length > 0
            ? ' (' + payload.details.join('; ') + ')'
            : '';
        const msg = (payload && payload.message ? payload.message : `Request failed (${response.status})`) + details;
        throw new Error(msg);
    }
    return payload && Object.prototype.hasOwnProperty.call(payload, 'data') ? payload.data : payload;
}

export async function apiGet(url) {
    const res = await fetch(url, {
        method: 'GET',
        headers: { 'Accept': 'application/json' },
        credentials: 'same-origin'
    });
    return parseApiResponse(res);
}

export async function apiPost(url, body) {
    const res = await fetch(url, {
        method: 'POST',
        headers: {
            'Accept': 'application/json',
            'Content-Type': 'application/json',
            ...getCsrfHeaders()
        },
        credentials: 'same-origin',
        body: JSON.stringify(body)
    });
    return parseApiResponse(res);
}

export async function apiPut(url, body) {
    const res = await fetch(url, {
        method: 'PUT',
        headers: {
            'Accept': 'application/json',
            'Content-Type': 'application/json',
            ...getCsrfHeaders()
        },
        credentials: 'same-origin',
        body: JSON.stringify(body)
    });
    return parseApiResponse(res);
}

export async function apiDelete(url) {
    const res = await fetch(url, {
        method: 'DELETE',
        headers: {
            'Accept': 'application/json',
            ...getCsrfHeaders()
        },
        credentials: 'same-origin'
    });
    return parseApiResponse(res);
}

export async function apiUpload(url, formData) {
    const res = await fetch(url, {
        method: 'POST',
        headers: {
            'Accept': 'application/json',
            ...getCsrfHeaders()
        },
        credentials: 'same-origin',
        body: formData
    });
    return parseApiResponse(res);
}
