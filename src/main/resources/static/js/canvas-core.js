/**
 * Reusable HTML5 Canvas core for Vyuha workspace views (Phases 3-9).
 * Handles DPR scaling, world-meter <-> screen-pixel transforms, pan/zoom, grid snap,
 * compass & scale bar rendering, GeoJSON vector overlays, locked blueprint images,
 * hatched polygon fills, and PNG snapshot export.
 */
export class VyuhaCanvasCore {
    constructor(canvas, options = {}) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
        this.zoom = options.initialZoom || 6.5; // screen CSS pixels per world meter
        this.minZoom = options.minZoom || 1.2;
        this.maxZoom = options.maxZoom || 120.0;
        this.panX = options.panX || 40;
        this.panY = options.panY || 40;
        this.snapMeters = options.snapMeters ?? 1.0;
        this.showGrid = options.showGrid !== false;
        this.onCursorMove = options.onCursorMove || null;
        this.onViewChange = options.onViewChange || null;
        this.onRender = options.onRender || null;

        this._dpr = window.devicePixelRatio || 1;
        this._width = 800;
        this._height = 600;
        this._hatchCache = new Map();

        this._bindResize();
        this.resize();
    }

    _bindResize() {
        if (typeof ResizeObserver !== 'undefined' && this.canvas.parentElement) {
            this._resizeObserver = new ResizeObserver(() => this.resize());
            this._resizeObserver.observe(this.canvas.parentElement);
        } else {
            window.addEventListener('resize', () => this.resize());
        }
    }

    resize() {
        const rect = this.canvas.getBoundingClientRect();
        this._width = Math.max(1, Math.floor(rect.width || 800));
        this._height = Math.max(1, Math.floor(rect.height || 600));
        this._dpr = window.devicePixelRatio || 1;
        this.canvas.width = Math.floor(this._width * this._dpr);
        this.canvas.height = Math.floor(this._height * this._dpr);
        this.requestRender();
    }

    requestRender() {
        if (this._rafPending) {
            return;
        }
        this._rafPending = true;
        requestAnimationFrame(() => {
            this._rafPending = false;
            if (this.onRender) {
                this.onRender(this);
            }
        });
    }

    worldToScreen(wx, wy) {
        return {
            x: wx * this.zoom + this.panX,
            y: wy * this.zoom + this.panY
        };
    }

    screenToWorld(sx, sy) {
        return {
            x: (sx - this.panX) / this.zoom,
            y: (sy - this.panY) / this.zoom
        };
    }

    snapWorld(wx, wy) {
        if (!this.snapMeters || this.snapMeters <= 0) {
            return { x: wx, y: wy };
        }
        return {
            x: Math.round(wx / this.snapMeters) * this.snapMeters,
            y: Math.round(wy / this.snapMeters) * this.snapMeters
        };
    }

    getCanvasLocalCoords(clientX, clientY) {
        const rect = this.canvas.getBoundingClientRect();
        return {
            x: clientX - rect.left,
            y: clientY - rect.top
        };
    }

    zoomAt(factor, screenX, screenY) {
        const cx = screenX !== undefined ? screenX : this._width / 2;
        const cy = screenY !== undefined ? screenY : this._height / 2;
        const before = this.screenToWorld(cx, cy);
        const nextZoom = Math.max(this.minZoom, Math.min(this.maxZoom, this.zoom * factor));
        this.zoom = nextZoom;
        this.panX = cx - before.x * this.zoom;
        this.panY = cy - before.y * this.zoom;
        if (this.onViewChange) {
            this.onViewChange(this);
        }
        this.requestRender();
    }

    resetZoom100() {
        const center = this.screenToWorld(this._width / 2, this._height / 2);
        this.zoom = 6.5;
        this.panX = this._width / 2 - center.x * this.zoom;
        this.panY = this._height / 2 - center.y * this.zoom;
        if (this.onViewChange) {
            this.onViewChange(this);
        }
        this.requestRender();
    }

    panBy(dx, dy) {
        this.panX += dx;
        this.panY += dy;
        if (this.onViewChange) {
            this.onViewChange(this);
        }
        this.requestRender();
    }

    panTo(wx, wy, minTargetZoom = null) {
        if (minTargetZoom && this.zoom < minTargetZoom) {
            this.zoom = Math.min(this.maxZoom, minTargetZoom);
        }
        this.panX = this._width / 2 - wx * this.zoom;
        this.panY = this._height / 2 - wy * this.zoom;
        if (this.onViewChange) {
            this.onViewChange(this);
        }
        this.requestRender();
    }

    fitToBounds(bounds, paddingPx = 48) {
        if (!bounds) {
            this.zoom = 6.5;
            this.panX = 40;
            this.panY = 40;
            this.requestRender();
            return;
        }
        const bw = Math.max(1, bounds.maxX - bounds.minX);
        const bh = Math.max(1, bounds.maxY - bounds.minY);
        const availW = Math.max(80, this._width - paddingPx * 2);
        const availH = Math.max(80, this._height - paddingPx * 2);
        this.zoom = Math.max(this.minZoom, Math.min(this.maxZoom, Math.min(availW / bw, availH / bh)));
        const midX = (bounds.minX + bounds.maxX) / 2;
        const midY = (bounds.minY + bounds.maxY) / 2;
        this.panX = this._width / 2 - midX * this.zoom;
        this.panY = this._height / 2 - midY * this.zoom;
        if (this.onViewChange) {
            this.onViewChange(this);
        }
        this.requestRender();
    }

    beginFrame() {
        const ctx = this.ctx;
        ctx.setTransform(this._dpr, 0, 0, this._dpr, 0, 0);
        ctx.clearRect(0, 0, this._width, this._height);
    }

    drawMetricGrid(unitLabel = 'm') {
        if (!this.showGrid) {
            return;
        }
        const ctx = this.ctx;
        const step = this._pickGridStep();
        const topLeft = this.screenToWorld(0, 0);
        const bottomRight = this.screenToWorld(this._width, this._height);

        const startX = Math.floor(topLeft.x / step) * step;
        const startY = Math.floor(topLeft.y / step) * step;

        ctx.save();
        ctx.lineWidth = 1;
        ctx.font = '10px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
        ctx.fillStyle = '#64748B';

        for (let wx = startX; wx <= bottomRight.x; wx += step) {
            const sx = Math.round(wx * this.zoom + this.panX) + 0.5;
            const isMajor = Math.abs(Math.round(wx / (step * 5)) * (step * 5) - wx) < 1e-6;
            ctx.strokeStyle = Math.abs(wx) < 1e-6 ? '#94A3B8' : (isMajor ? '#CBD5E1' : '#E2E8F0');
            ctx.beginPath();
            ctx.moveTo(sx, 0);
            ctx.lineTo(sx, this._height);
            ctx.stroke();
            if (isMajor || step * this.zoom >= 44) {
                ctx.fillText(this._formatAxisNumber(wx) + unitLabel, sx + 3, 12);
            }
        }

        for (let wy = startY; wy <= bottomRight.y; wy += step) {
            const sy = Math.round(wy * this.zoom + this.panY) + 0.5;
            const isMajor = Math.abs(Math.round(wy / (step * 5)) * (step * 5) - wy) < 1e-6;
            ctx.strokeStyle = Math.abs(wy) < 1e-6 ? '#94A3B8' : (isMajor ? '#CBD5E1' : '#E2E8F0');
            ctx.beginPath();
            ctx.moveTo(0, sy);
            ctx.lineTo(this._width, sy);
            ctx.stroke();
            if ((isMajor || step * this.zoom >= 44) && sy > 18) {
                ctx.fillText(this._formatAxisNumber(wy) + unitLabel, 4, sy - 3);
            }
        }
        ctx.restore();
    }

    drawScaleBarAndCompass(showMeters = true) {
        const ctx = this.ctx;
        ctx.save();

        // Compact North Compass badge in top-left
        const cx = 24;
        const cy = 38;
        ctx.fillStyle = 'rgba(255, 255, 255, 0.9)';
        ctx.strokeStyle = '#CBD5E1';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.arc(cx, cy, 14, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();

        ctx.fillStyle = '#0F172A';
        ctx.beginPath();
        ctx.moveTo(cx, cy - 9);
        ctx.lineTo(cx - 4, cy + 3);
        ctx.lineTo(cx, cy);
        ctx.lineTo(cx + 4, cy + 3);
        ctx.closePath();
        ctx.fill();

        ctx.font = 'bold 9px system-ui, sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('N', cx, cy + 12);

        if (showMeters) {
            const barMeters = this._pickScaleBarMeters();
            const barPx = barMeters * this.zoom;
            const bx = 14;
            const by = this._height - 54;

            ctx.fillStyle = 'rgba(255, 255, 255, 0.9)';
            ctx.strokeStyle = '#CBD5E1';
            ctx.fillRect(bx - 6, by - 16, barPx + 12, 24);
            ctx.strokeRect(bx - 6, by - 16, barPx + 12, 24);

            ctx.strokeStyle = '#0F172A';
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.moveTo(bx, by - 4);
            ctx.lineTo(bx, by);
            ctx.lineTo(bx + barPx, by);
            ctx.lineTo(bx + barPx, by - 4);
            ctx.stroke();

            ctx.fillStyle = '#0F172A';
            ctx.font = '11px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
            ctx.textAlign = 'left';
            ctx.fillText(`${barMeters} m`, bx, by - 5);
        }
        ctx.restore();
    }

    drawBlueprintImage(img, displayPixelsPerMeter, widthPx, heightPx, opacity = 0.85) {
        if (!img || !img.complete || !displayPixelsPerMeter || displayPixelsPerMeter <= 0) {
            return;
        }
        const ctx = this.ctx;
        const topLeft = this.worldToScreen(0, 0);
        const worldW = (widthPx || img.naturalWidth) / displayPixelsPerMeter;
        const worldH = (heightPx || img.naturalHeight) / displayPixelsPerMeter;
        const screenW = worldW * this.zoom;
        const screenH = worldH * this.zoom;

        ctx.save();
        ctx.globalAlpha = opacity;
        ctx.drawImage(img, topLeft.x, topLeft.y, screenW, screenH);
        ctx.globalAlpha = 1;
        ctx.strokeStyle = '#94A3B8';
        ctx.lineWidth = 1;
        ctx.strokeRect(topLeft.x, topLeft.y, screenW, screenH);
        ctx.restore();
    }

    drawGeoJsonOverlay(featureCollection) {
        if (!featureCollection || !Array.isArray(featureCollection.features)) {
            return;
        }
        const ctx = this.ctx;
        ctx.save();
        for (const feat of featureCollection.features) {
            if (!feat || !feat.geometry) {
                continue;
            }
            const props = feat.properties || {};
            const geom = feat.geometry;
            const role = props.role || '';
            const kind = props.kind || '';

            if (geom.type === 'Polygon' && Array.isArray(geom.coordinates) && geom.coordinates[0]) {
                const ring = geom.coordinates[0].map(c => ({ x: c[0], y: c[1] }));
                if (role === 'outer_boundary') {
                    this.drawPolygon(ring, {
                        fill: 'rgba(255, 255, 255, 0.35)',
                        stroke: '#94A3B8',
                        lineWidth: 1.5,
                        dashed: [6, 4],
                        label: null
                    });
                } else {
                    const style = this._styleForContextKind(kind);
                    this.drawPolygon(ring, {
                        fill: style.fill,
                        stroke: style.stroke,
                        lineWidth: 1,
                        label: props.label || null,
                        labelColor: '#475569'
                    });
                }
            } else if (geom.type === 'LineString' && Array.isArray(geom.coordinates)) {
                ctx.beginPath();
                geom.coordinates.forEach((c, idx) => {
                    const pt = this.worldToScreen(c[0], c[1]);
                    if (idx === 0) ctx.moveTo(pt.x, pt.y);
                    else ctx.lineTo(pt.x, pt.y);
                });
                ctx.strokeStyle = '#64748B';
                ctx.lineWidth = 1.5;
                ctx.stroke();
            } else if (geom.type === 'Point' && Array.isArray(geom.coordinates)) {
                const pt = this.worldToScreen(geom.coordinates[0], geom.coordinates[1]);
                ctx.fillStyle = '#0F172A';
                ctx.beginPath();
                ctx.arc(pt.x, pt.y, 4, 0, Math.PI * 2);
                ctx.fill();
                if (props.label) {
                    ctx.font = '11px system-ui, sans-serif';
                    ctx.fillStyle = '#334155';
                    ctx.fillText(props.label, pt.x + 7, pt.y + 4);
                }
            }
        }
        ctx.restore();
    }

    drawPolygon(points, style = {}) {
        if (!points || points.length < 3) {
            return;
        }
        const ctx = this.ctx;
        ctx.save();
        ctx.beginPath();
        points.forEach((p, idx) => {
            const s = this.worldToScreen(p.x, p.y);
            if (idx === 0) ctx.moveTo(s.x, s.y);
            else ctx.lineTo(s.x, s.y);
        });
        ctx.closePath();

        if (style.fill) {
            ctx.fillStyle = style.fill;
            ctx.fill();
        }
        if (style.hatched) {
            ctx.fillStyle = this._getHatchPattern(style.hatchColor || style.stroke || '#475569');
            ctx.fill();
        }
        if (style.dashed) {
            ctx.setLineDash(style.dashed);
        }
        ctx.strokeStyle = style.stroke || '#2563EB';
        ctx.lineWidth = style.lineWidth || 2;
        ctx.stroke();
        ctx.setLineDash([]);

        if (style.selected) {
            points.forEach((p, idx) => {
                const s = this.worldToScreen(p.x, p.y);
                ctx.fillStyle = idx === 0 ? '#2563EB' : '#FFFFFF';
                ctx.strokeStyle = '#1D4ED8';
                ctx.lineWidth = 1.5;
                ctx.beginPath();
                ctx.arc(s.x, s.y, 5, 0, Math.PI * 2);
                ctx.fill();
                ctx.stroke();
            });
        }

        if (style.label) {
            const center = this._polygonCentroid(points);
            const sc = this.worldToScreen(center.x, center.y);
            ctx.font = style.labelFont || '600 11px system-ui, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillStyle = style.labelColor || '#0F172A';
            ctx.fillText(style.label, sc.x, sc.y);
        }
        ctx.restore();
    }

    drawPolygonShapes(shapes, style = {}) {
        if (!shapes || shapes.length === 0) {
            return;
        }
        const ctx = this.ctx;
        ctx.save();
        ctx.beginPath();
        for (const shape of shapes) {
            if (!shape || !shape.exterior || shape.exterior.length < 3) {
                continue;
            }
            shape.exterior.forEach((p, idx) => {
                const s = this.worldToScreen(p.x, p.y);
                if (idx === 0) ctx.moveTo(s.x, s.y);
                else ctx.lineTo(s.x, s.y);
            });
            ctx.closePath();

            if (Array.isArray(shape.holes)) {
                for (const hole of shape.holes) {
                    if (!hole || hole.length < 3) continue;
                    hole.forEach((p, idx) => {
                        const s = this.worldToScreen(p.x, p.y);
                        if (idx === 0) ctx.moveTo(s.x, s.y);
                        else ctx.lineTo(s.x, s.y);
                    });
                    ctx.closePath();
                }
            }
        }

        if (style.fill) {
            ctx.fillStyle = style.fill;
            ctx.fill('evenodd');
        }
        if (style.hatched) {
            ctx.fillStyle = this._getHatchPattern(style.hatchColor || style.stroke || '#475569');
            ctx.fill('evenodd');
        }
        if (style.dashed) {
            ctx.setLineDash(style.dashed);
        }
        ctx.strokeStyle = style.stroke || '#2563EB';
        ctx.lineWidth = style.lineWidth || 1.6;
        ctx.stroke();
        ctx.setLineDash([]);

        if (style.label && style.labelPoint) {
            const sc = this.worldToScreen(style.labelPoint.x, style.labelPoint.y);
            ctx.font = style.labelFont || '600 11px system-ui, sans-serif';
            const tw = ctx.measureText(style.label).width;
            ctx.fillStyle = 'rgba(255, 255, 255, 0.85)';
            ctx.fillRect(sc.x - tw / 2 - 4, sc.y - 8, tw + 8, 16);
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillStyle = style.labelColor || '#0F172A';
            ctx.fillText(style.label, sc.x, sc.y);
        }
        ctx.restore();
    }

    drawActiveTrace(points, hoverWorld, strokeColor = '#2563EB', unit = 'm') {
        if (!points || points.length === 0) {
            return;
        }
        const ctx = this.ctx;
        ctx.save();
        ctx.beginPath();
        points.forEach((p, idx) => {
            const s = this.worldToScreen(p.x, p.y);
            if (idx === 0) ctx.moveTo(s.x, s.y);
            else ctx.lineTo(s.x, s.y);
        });
        if (hoverWorld) {
            const hs = this.worldToScreen(hoverWorld.x, hoverWorld.y);
            ctx.lineTo(hs.x, hs.y);
        }
        ctx.strokeStyle = strokeColor;
        ctx.lineWidth = 2;
        ctx.setLineDash([5, 4]);
        ctx.stroke();
        ctx.setLineDash([]);

        points.forEach((p, idx) => {
            const s = this.worldToScreen(p.x, p.y);
            ctx.fillStyle = idx === 0 ? '#0F766E' : '#FFFFFF';
            ctx.strokeStyle = strokeColor;
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.arc(s.x, s.y, idx === 0 ? 6 : 4.5, 0, Math.PI * 2);
            ctx.fill();
            ctx.stroke();
        });

        if (hoverWorld && points.length > 0) {
            const prev = points[points.length - 1];
            const dist = Math.hypot(hoverWorld.x - prev.x, hoverWorld.y - prev.y);
            const hs = this.worldToScreen(hoverWorld.x, hoverWorld.y);
            ctx.fillStyle = '#0F172A';
            ctx.font = '11px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
            ctx.fillText(`${dist.toFixed(1)} ${unit}`, hs.x + 10, hs.y - 8);
        }
        ctx.restore();
    }

    drawCalibrationSegment(p1, p2, color, badgeText) {
        if (!p1 || !p2) {
            return;
        }
        const ctx = this.ctx;
        const s1 = this.worldToScreen(p1.x, p1.y);
        const s2 = this.worldToScreen(p2.x, p2.y);

        ctx.save();
        ctx.strokeStyle = color;
        ctx.lineWidth = 2.5;
        ctx.beginPath();
        ctx.moveTo(s1.x, s1.y);
        ctx.lineTo(s2.x, s2.y);
        ctx.stroke();

        [s1, s2].forEach(s => {
            ctx.fillStyle = '#FFFFFF';
            ctx.strokeStyle = color;
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.arc(s.x, s.y, 5, 0, Math.PI * 2);
            ctx.fill();
            ctx.stroke();
        });

        if (badgeText) {
            const mx = (s1.x + s2.x) / 2;
            const my = (s1.y + s2.y) / 2;
            ctx.font = '600 11px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
            const textW = ctx.measureText(badgeText).width;
            ctx.fillStyle = '#0F172A';
            ctx.fillRect(mx - textW / 2 - 6, my - 20, textW + 12, 18);
            ctx.fillStyle = '#FFFFFF';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(badgeText, mx, my - 11);
        }
        ctx.restore();
    }

    exportPngDataUrl() {
        return this.canvas.toDataURL('image/png');
    }

    _pickGridStep() {
        const candidates = [1, 2, 5, 10, 20, 50, 100];
        for (const c of candidates) {
            if (c * this.zoom >= 18) {
                return c;
            }
        }
        return 100;
    }

    _pickScaleBarMeters() {
        const targetPx = 90;
        const raw = targetPx / this.zoom;
        const steps = [1, 2, 5, 10, 20, 25, 50, 100, 200];
        for (const s of steps) {
            if (s >= raw) {
                return s;
            }
        }
        return 200;
    }

    _formatAxisNumber(v) {
        return Math.abs(v - Math.round(v)) < 1e-6 ? String(Math.round(v)) : v.toFixed(1);
    }

    _styleForContextKind(kind) {
        switch (kind) {
            case 'BUILDING':
                return { fill: 'rgba(148, 163, 184, 0.25)', stroke: '#64748B' };
            case 'STAGE':
                return { fill: 'rgba(168, 85, 247, 0.16)', stroke: '#7E22CE' };
            case 'ROAD':
                return { fill: 'rgba(14, 165, 233, 0.14)', stroke: '#0284C7' };
            case 'OPEN_GROUND':
                return { fill: 'rgba(16, 185, 129, 0.10)', stroke: '#059669' };
            case 'RESTRICTED':
                return { fill: 'rgba(239, 68, 68, 0.14)', stroke: '#DC2626' };
            default:
                return { fill: 'rgba(100, 116, 139, 0.12)', stroke: '#94A3B8' };
        }
    }

    _getHatchPattern(color) {
        if (this._hatchCache.has(color)) {
            return this._hatchCache.get(color);
        }
        const tile = document.createElement('canvas');
        tile.width = 10;
        tile.height = 10;
        const tctx = tile.getContext('2d');
        tctx.strokeStyle = color;
        tctx.globalAlpha = 0.28;
        tctx.lineWidth = 1.5;
        tctx.beginPath();
        tctx.moveTo(0, 10);
        tctx.lineTo(10, 0);
        tctx.moveTo(-2, 2);
        tctx.lineTo(2, -2);
        tctx.moveTo(8, 12);
        tctx.lineTo(12, 8);
        tctx.stroke();
        const pattern = this.ctx.createPattern(tile, 'repeat');
        this._hatchCache.set(color, pattern);
        return pattern;
    }

    _polygonCentroid(points) {
        let sx = 0;
        let sy = 0;
        for (const p of points) {
            sx += p.x;
            sy += p.y;
        }
        return { x: sx / points.length, y: sy / points.length };
    }
}
