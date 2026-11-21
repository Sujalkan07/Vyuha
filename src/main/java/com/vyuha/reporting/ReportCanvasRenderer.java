package com.vyuha.reporting;

import com.vyuha.common.GeometryUtil;
import com.vyuha.emergency.EmergencyDtos;
import com.vyuha.grid.GridDtos;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.network.NetworkDtos;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.venue.VenueDtos;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Pure-Java2D offline renderer generating high-resolution PNG snapshots of the current
 * venue layout (with zones, infrastructure, pathways, emergency routes, and warning markers)
 * and Before-and-After simulation layout comparisons for direct download or PDF embedding.
 */
@Component
public class ReportCanvasRenderer {

    private static final Color PAGE_BG = new Color(0xF8, 0xFA, 0xFC);
    private static final Color HEADER_BG = new Color(0x0F, 0x17, 0x2A);
    private static final Color BORDER_COL = new Color(0xCB, 0xD5, 0xE1);
    private static final Color TEXT_PRIMARY = new Color(0x0F, 0x17, 0x2A);
    private static final Color TEXT_MUTED = new Color(0x47, 0x55, 0x69);

    public byte[] renderCurrentLayoutPng(ReportDtos.EventReportBundle bundle) {
        int width = 1200;
        int height = 760;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(PAGE_BG);
            g.fillRect(0, 0, width, height);

            // Header bar
            g.setColor(HEADER_BG);
            g.fillRect(0, 0, width, 48);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            String title = bundle.event().getName() + " — Current Venue Layout & Screening Overlay";
            g.drawString(title, 20, 30);

            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            String areaMeta = String.format(Locale.US,
                    "Gross: %.1f m² | Usable: %.1f m² | Conflicts: %d | Bottlenecks: %d",
                    bundle.venueWorkspace().areaBreakdown().grossAreaSqm(),
                    bundle.venueWorkspace().areaBreakdown().usableAreaSqm(),
                    bundle.infraWorkspace().summary().openFindingsCount(),
                    bundle.networkWorkspace().summary().openFindingsCount());
            int metaW = g.getFontMetrics().stringWidth(areaMeta);
            g.drawString(areaMeta, Math.max(460, width - metaW - 20), 30);

            Bounds2D bounds = computeCurrentBounds(bundle);
            int canvasX = 24;
            int canvasY = 64;
            int canvasW = width - 48;
            int canvasH = height - 118;

            g.setColor(Color.WHITE);
            g.fillRect(canvasX, canvasY, canvasW, canvasH);
            g.setColor(BORDER_COL);
            g.drawRect(canvasX, canvasY, canvasW, canvasH);

            double pad = 28.0;
            double spanX = Math.max(10.0, bounds.maxX - bounds.minX);
            double spanY = Math.max(10.0, bounds.maxY - bounds.minY);
            double scale = Math.min((canvasW - pad * 2.0) / spanX, (canvasH - pad * 2.0) / spanY);
            double offsetX = canvasX + (canvasW - spanX * scale) / 2.0 - bounds.minX * scale;
            double offsetY = canvasY + (canvasH - spanY * scale) / 2.0 - bounds.minY * scale;

            // Metric Grid lines (10m)
            drawMetricGrid(g, canvasX, canvasY, canvasW, canvasH, bounds, scale, offsetX, offsetY);

            // Outer boundary
            List<GeometryUtil.Point2D> boundary = bundle.venueWorkspace().venuePlan().boundaryPoints();
            if (boundary != null && boundary.size() >= 3) {
                Path2D bp = buildPath(boundary, scale, offsetX, offsetY);
                g.setColor(new Color(0xFF, 0xFF, 0xFF, 235));
                g.fill(bp);
                g.setColor(new Color(0x25, 0x63, 0xEB));
                g.setStroke(new BasicStroke(2.4f));
                g.draw(bp);
            }

            // Functional zones
            for (GridDtos.FunctionalZoneDto z : bundle.gridWorkspace().zones()) {
                if (z.shapes() == null) continue;
                Color fill = parseColor(z.fillColor(), new Color(0x3B, 0x82, 0xF6), 58);
                Color stroke = parseColor(z.strokeColor(), new Color(0x1D, 0x4E, 0xD8), 195);
                for (GeometryUtil.PolygonShape shape : z.shapes()) {
                    if (shape.exterior() == null || shape.exterior().size() < 3) continue;
                    Path2D zp = buildPath(shape.exterior(), scale, offsetX, offsetY);
                    g.setColor(fill);
                    g.fill(zp);
                    g.setColor(stroke);
                    g.setStroke(new BasicStroke(1.3f));
                    g.draw(zp);
                }
                if (z.labelPoint() != null) {
                    int lx = (int) Math.round(z.labelPoint().x() * scale + offsetX);
                    int ly = (int) Math.round(z.labelPoint().y() * scale + offsetY);
                    g.setColor(TEXT_PRIMARY);
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
                    String code = z.zoneCode() != null ? z.zoneCode() : z.name();
                    g.drawString(truncate(code, 18), lx - 18, ly + 3);
                }
            }

            // Exclusions
            for (VenueDtos.ExclusionAreaDto ex : bundle.venueWorkspace().exclusions()) {
                if (ex.points() == null || ex.points().size() < 3) continue;
                Path2D ep = buildPath(ex.points(), scale, offsetX, offsetY);
                g.setColor(parseColor(ex.fillColor(), new Color(0x94, 0xA3, 0xB8), 115));
                g.fill(ep);
                g.setColor(parseColor(ex.strokeColor(), new Color(0x47, 0x55, 0x69), 225));
                g.setStroke(new BasicStroke(1.4f));
                g.draw(ep);
            }

            // Emergency vehicle routes & assembly points
            for (EmergencyDtos.EmergencyAssetDto asset : bundle.emergencyWorkspace().assets()) {
                if (asset.points() == null || asset.points().isEmpty()) continue;
                Color stroke = parseColor(asset.strokeColor(), new Color(0xDC, 0x26, 0x26), 210);
                if ("POLYGON".equals(asset.geometryKind()) && asset.points().size() >= 3) {
                    Path2D ap = buildPath(asset.points(), scale, offsetX, offsetY);
                    g.setColor(parseColor(asset.fillColor(), new Color(0x0F, 0x76, 0x6E), 55));
                    g.fill(ap);
                    g.setColor(stroke);
                    g.setStroke(new BasicStroke(1.8f));
                    g.draw(ap);
                } else if ("LINESTRING".equals(asset.geometryKind()) && asset.points().size() >= 2) {
                    g.setColor(stroke);
                    g.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                            10f, new float[]{8f, 5f}, 0f));
                    drawPolyline(g, asset.points(), scale, offsetX, offsetY);
                }
            }

            // Pathway edges
            for (NetworkDtos.PathwayEdgeDto edge : bundle.networkWorkspace().edges()) {
                if (!edge.active() || edge.points() == null || edge.points().size() < 2) continue;
                Color ec = parseColor(edge.colorHex(), new Color(0x25, 0x63, 0xEB), 210);
                g.setColor(ec);
                float strokePx = (float) Math.max(2.0, Math.min(20.0, edge.widthM() * scale * 0.58));
                if (edge.dashed()) {
                    g.setStroke(new BasicStroke(strokePx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                            10f, new float[]{8f, 6f}, 0f));
                } else {
                    g.setStroke(new BasicStroke(strokePx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                }
                drawPolyline(g, edge.points(), scale, offsetX, offsetY);
            }

            // Infrastructure elements
            for (InfrastructureDtos.InfrastructureElementDto el : bundle.infraWorkspace().elements()) {
                if (el.footprintPoints() == null || el.footprintPoints().size() < 3) continue;
                Path2D fp = buildPath(el.footprintPoints(), scale, offsetX, offsetY);
                g.setColor(parseColor(el.fillColor(), new Color(0x64, 0x74, 0x8B), el.active() ? 185 : 70));
                g.fill(fp);
                g.setColor(parseColor(el.strokeColor(), TEXT_PRIMARY, 235));
                g.setStroke(new BasicStroke(1.5f));
                g.draw(fp);

                int cx = (int) Math.round(el.centerX() * scale + offsetX);
                int cy = (int) Math.round(el.centerY() * scale + offsetY);
                g.setColor(TEXT_PRIMARY);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
                g.drawString(truncate(el.shortCode(), 8), cx - 10, cy + 3);
            }

            // Pathway nodes
            for (NetworkDtos.PathwayNodeDto node : bundle.networkWorkspace().nodes()) {
                int nx = (int) Math.round(node.x() * scale + offsetX);
                int ny = (int) Math.round(node.y() * scale + offsetY);
                g.setColor(parseColor(node.fillColor(), new Color(0x25, 0x63, 0xEB), 255));
                g.fillOval(nx - 5, ny - 5, 10, 10);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(1.4f));
                g.drawOval(nx - 5, ny - 5, 10, 10);
            }

            // Warning pins for active CRITICAL / HIGH findings
            for (InfrastructureDtos.ConflictFindingDto cf : bundle.infraWorkspace().activeFindings()) {
                if (cf.locationX() != null && cf.locationY() != null) {
                    drawFindingPin(g, cf.locationX(), cf.locationY(), cf.severity(), scale, offsetX, offsetY);
                }
            }
            for (NetworkDtos.BottleneckFindingDto bf : bundle.networkWorkspace().activeFindings()) {
                if (bf.locationX() != null && bf.locationY() != null) {
                    drawFindingPin(g, bf.locationX(), bf.locationY(), bf.severity(), scale, offsetX, offsetY);
                }
            }

            // Footer legend & scale bar
            drawFooterLegend(g, width, height, scale);
        } finally {
            g.dispose();
        }
        return encodePng(image);
    }

    public byte[] renderComparisonPng(SimulationDtos.SimulationComparisonDto comparison) {
        int width = 1200;
        int height = 500;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(PAGE_BG);
            g.fillRect(0, 0, width, height);

            g.setColor(HEADER_BG);
            g.fillRect(0, 0, width, 42);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            g.drawString("Before-and-After Layout Comparison — " + comparison.afterRun().scenarioDisplayName()
                    + " (Seed " + comparison.afterRun().randomSeed() + ")", 18, 26);

            int panelW = 568;
            int panelH = 356;
            int panelY = 58;
            drawSnapshotPanel(g, 20, panelY, panelW, panelH,
                    "BEFORE: V" + comparison.beforeRun().snapshotVersion() + " — " + comparison.beforeRun().snapshotLabel(),
                    comparison.beforeLayout(), comparison.beforeRun().metrics(), new Color(0xB9, 0x1C, 0x1C));
            drawSnapshotPanel(g, 612, panelY, panelW, panelH,
                    "AFTER: V" + comparison.afterRun().snapshotVersion() + " — " + comparison.afterRun().snapshotLabel(),
                    comparison.afterLayout(), comparison.afterRun().metrics(), new Color(0x0F, 0x76, 0x6E));

            // Bottom KPI delta strip
            int stripY = 428;
            g.setColor(Color.WHITE);
            g.fillRect(20, stripY, width - 40, 56);
            g.setColor(BORDER_COL);
            g.drawRect(20, stripY, width - 40, 56);

            g.setColor(TEXT_PRIMARY);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
            String kpiLine = String.format(Locale.US,
                    "Peak Density: %.2f -> %.2f p/m² (%+.2f)   |   Peak Queue: %.0f -> %.0f   |   P95 Clearance: %.1f -> %.1f s (%+.1f s)",
                    comparison.peakDensity().beforeValue(), comparison.peakDensity().afterValue(), comparison.peakDensity().delta(),
                    comparison.peakQueueLength().beforeValue(), comparison.peakQueueLength().afterValue(),
                    comparison.p95TravelTime().beforeValue(), comparison.p95TravelTime().afterValue(), comparison.p95TravelTime().delta());
            g.drawString(kpiLine, 34, stripY + 24);

            g.setColor(TEXT_MUTED);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.drawString(SimulationDtos.MANDATORY_DISCLAIMER, 34, stripY + 44);
        } finally {
            g.dispose();
        }
        return encodePng(image);
    }

    public String toBase64DataUri(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) {
            return "";
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes);
    }

    private void drawSnapshotPanel(Graphics2D g,
                                   int x,
                                   int y,
                                   int w,
                                   int h,
                                   String heading,
                                   SimulationDtos.SerializedLayout layout,
                                   SimulationDtos.SimulationMetricsSummary metrics,
                                   Color accent) {
        g.setColor(Color.WHITE);
        g.fillRect(x, y, w, h);
        g.setColor(BORDER_COL);
        g.drawRect(x, y, w, h);

        g.setColor(accent);
        g.fillRect(x, y, 4, 30);
        g.setColor(TEXT_PRIMARY);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString(truncate(heading, 64), x + 12, y + 20);

        if (layout == null) {
            return;
        }
        GeometryUtil.Bounds b = layout.bounds();
        double minX = b != null ? b.minX() : 0.0;
        double minY = b != null ? b.minY() : 0.0;
        double spanX = b != null ? Math.max(10.0, b.maxX() - b.minX()) : 120.0;
        double spanY = b != null ? Math.max(10.0, b.maxY() - b.minY()) : 90.0;

        int mapX = x + 10;
        int mapY = y + 36;
        int mapW = w - 20;
        int mapH = h - 68;
        double pad = 16.0;
        double scale = Math.min((mapW - pad * 2.0) / spanX, (mapH - pad * 2.0) / spanY);
        double offsetX = mapX + (mapW - spanX * scale) / 2.0 - minX * scale;
        double offsetY = mapY + (mapH - spanY * scale) / 2.0 - minY * scale;

        if (layout.boundaryPoints() != null && layout.boundaryPoints().size() >= 3) {
            Path2D bp = buildPath(layout.boundaryPoints(), scale, offsetX, offsetY);
            g.setColor(new Color(0xF8, 0xFA, 0xFC));
            g.fill(bp);
            g.setColor(new Color(0x25, 0x63, 0xEB));
            g.setStroke(new BasicStroke(1.8f));
            g.draw(bp);
        }
        if (layout.zones() != null) {
            for (SimulationDtos.SnapZone z : layout.zones()) {
                if (z.shapes() == null) continue;
                Color fill = parseColor(z.fillColor(), new Color(0x3B, 0x82, 0xF6), 55);
                Color stroke = parseColor(z.strokeColor(), new Color(0x1D, 0x4E, 0xD8), 185);
                for (GeometryUtil.PolygonShape shape : z.shapes()) {
                    if (shape.exterior() == null || shape.exterior().size() < 3) continue;
                    Path2D zp = buildPath(shape.exterior(), scale, offsetX, offsetY);
                    g.setColor(fill);
                    g.fill(zp);
                    g.setColor(stroke);
                    g.setStroke(new BasicStroke(1.0f));
                    g.draw(zp);
                }
            }
        }
        if (layout.exclusions() != null) {
            for (SimulationDtos.SnapExclusion ex : layout.exclusions()) {
                if (ex.points() == null || ex.points().size() < 3) continue;
                Path2D ep = buildPath(ex.points(), scale, offsetX, offsetY);
                g.setColor(parseColor(ex.fillColor(), new Color(0x94, 0xA3, 0xB8), 115));
                g.fill(ep);
                g.setColor(parseColor(ex.strokeColor(), new Color(0x47, 0x55, 0x69), 210));
                g.setStroke(new BasicStroke(1.1f));
                g.draw(ep);
            }
        }
        if (layout.edges() != null) {
            for (SimulationDtos.SnapEdge edge : layout.edges()) {
                if (!edge.active() || edge.points() == null || edge.points().size() < 2) continue;
                g.setColor(parseColor(edge.colorHex(), new Color(0x25, 0x63, 0xEB), 210));
                float strokePx = (float) Math.max(1.5, Math.min(14.0, edge.widthM() * scale * 0.6));
                g.setStroke(new BasicStroke(strokePx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                drawPolyline(g, edge.points(), scale, offsetX, offsetY);
            }
        }
        if (layout.elements() != null) {
            for (SimulationDtos.SnapElement el : layout.elements()) {
                if (el.footprintPoints() == null || el.footprintPoints().size() < 3) continue;
                Path2D fp = buildPath(el.footprintPoints(), scale, offsetX, offsetY);
                g.setColor(parseColor(el.fillColor(), new Color(0x64, 0x74, 0x8B), 175));
                g.fill(fp);
                g.setColor(parseColor(el.strokeColor(), TEXT_PRIMARY, 225));
                g.setStroke(new BasicStroke(1.1f));
                g.draw(fp);
            }
        }
        if (metrics != null && metrics.peakDensityLocationX() != null && metrics.peakDensityLocationY() != null) {
            int hx = (int) Math.round(metrics.peakDensityLocationX() * scale + offsetX);
            int hy = (int) Math.round(metrics.peakDensityLocationY() * scale + offsetY);
            g.setColor(new Color(0xB9, 0x1C, 0x1C, 70));
            g.fillOval(hx - 14, hy - 14, 28, 28);
            g.setColor(new Color(0xB9, 0x1C, 0x1C));
            g.setStroke(new BasicStroke(2.0f));
            g.drawOval(hx - 14, hy - 14, 28, 28);
        }

        if (metrics != null) {
            g.setColor(TEXT_MUTED);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            String mLine = String.format(Locale.US,
                    "Peak: %.2f p/m² | Peak Queue: %d | Max Wait: %.1f s | Clearance: %.1f%%",
                    metrics.peakLocalDensityPsm(), metrics.peakQueueLength(),
                    metrics.maxQueueWaitTimeSec(), metrics.clearanceRatePct());
            g.drawString(mLine, x + 12, y + h - 10);
        }
    }

    private void drawMetricGrid(Graphics2D g,
                                int canvasX,
                                int canvasY,
                                int canvasW,
                                int canvasH,
                                Bounds2D bounds,
                                double scale,
                                double offsetX,
                                double offsetY) {
        g.setColor(new Color(0xF1, 0xF5, 0xF9));
        g.setStroke(new BasicStroke(1.0f));
        double step = 10.0;
        double startX = Math.floor(bounds.minX / step) * step;
        double startY = Math.floor(bounds.minY / step) * step;
        for (double wx = startX; wx <= bounds.maxX + step; wx += step) {
            int px = (int) Math.round(wx * scale + offsetX);
            if (px > canvasX && px < canvasX + canvasW) {
                g.drawLine(px, canvasY, px, canvasY + canvasH);
            }
        }
        for (double wy = startY; wy <= bounds.maxY + step; wy += step) {
            int py = (int) Math.round(wy * scale + offsetY);
            if (py > canvasY && py < canvasY + canvasH) {
                g.drawLine(canvasX, py, canvasX + canvasW, py);
            }
        }
    }

    private void drawFindingPin(Graphics2D g,
                                double x,
                                double y,
                                FindingSeverity severity,
                                double scale,
                                double offsetX,
                                double offsetY) {
        int px = (int) Math.round(x * scale + offsetX);
        int py = (int) Math.round(y * scale + offsetY);
        Color c = switch (severity) {
            case CRITICAL -> new Color(0xB9, 0x1C, 0x1C);
            case HIGH -> new Color(0xC2, 0x41, 0x0C);
            case LOW -> new Color(0xB4, 0x53, 0x09);
            case INFO -> new Color(0x02, 0x84, 0xC7);
        };
        g.setColor(c);
        g.fillOval(px - 8, py - 8, 16, 16);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(px - 8, py - 8, 16, 16);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g.drawString("!", px - 2, py + 4);
    }

    private void drawFooterLegend(Graphics2D g, int width, int height, double scale) {
        int y = height - 40;
        g.setColor(TEXT_MUTED);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.drawString("Legend: Blue Outline = Boundary  |  Shaded Regions = Functional Zones  |  Solid Slate = Exclusions  |  Dashed Red = Emergency Lanes  |  (!) = Active Finding",
                24, y + 18);

        // 10m scale bar on bottom-right
        int barPx = (int) Math.max(30, Math.round(10.0 * scale));
        int barX = width - 30 - barPx;
        int barY = y + 16;
        g.setColor(TEXT_PRIMARY);
        g.setStroke(new BasicStroke(2.0f));
        g.drawLine(barX, barY, barX + barPx, barY);
        g.drawLine(barX, barY - 4, barX, barY + 4);
        g.drawLine(barX + barPx, barY - 4, barX + barPx, barY + 4);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        g.drawString("10 m", barX + barPx / 2 - 14, barY - 6);
    }

    private Bounds2D computeCurrentBounds(ReportDtos.EventReportBundle bundle) {
        GeometryUtil.Bounds b = bundle.venueWorkspace().areaBreakdown().boundaryBounds();
        if (b != null) {
            return new Bounds2D(b.minX(), b.minY(), b.maxX(), b.maxY());
        }
        return new Bounds2D(0.0, 0.0, 120.0, 90.0);
    }

    private Path2D buildPath(List<GeometryUtil.Point2D> pts, double scale, double offsetX, double offsetY) {
        Path2D path = new Path2D.Double();
        for (int i = 0; i < pts.size(); i++) {
            double sx = pts.get(i).x() * scale + offsetX;
            double sy = pts.get(i).y() * scale + offsetY;
            if (i == 0) {
                path.moveTo(sx, sy);
            } else {
                path.lineTo(sx, sy);
            }
        }
        path.closePath();
        return path;
    }

    private void drawPolyline(Graphics2D g, List<GeometryUtil.Point2D> pts, double scale, double offsetX, double offsetY) {
        for (int i = 1; i < pts.size(); i++) {
            int x1 = (int) Math.round(pts.get(i - 1).x() * scale + offsetX);
            int y1 = (int) Math.round(pts.get(i - 1).y() * scale + offsetY);
            int x2 = (int) Math.round(pts.get(i).x() * scale + offsetX);
            int y2 = (int) Math.round(pts.get(i).y() * scale + offsetY);
            g.drawLine(x1, y1, x2, y2);
        }
    }

    private Color parseColor(String hex, Color fallback, int alpha) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) {
            return new Color(fallback.getRed(), fallback.getGreen(), fallback.getBlue(), alpha);
        }
        try {
            int rgb = Integer.parseInt(hex.substring(1), 16);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha);
        } catch (NumberFormatException ex) {
            return new Color(fallback.getRed(), fallback.getGreen(), fallback.getBlue(), alpha);
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen - 1) + "…";
    }

    private byte[] encodePng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to encode PNG report image.", ex);
        }
    }

    private record Bounds2D(double minX, double minY, double maxX, double maxY) {
    }
}
