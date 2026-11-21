package com.vyuha.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.common.GeometryUtil;
import com.vyuha.config.StorageConfig;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handles local persistence of Phase 9 snapshot PNG thumbnails and compact simulation frame streams
 * under {uploadDir}/events/{eventId}/simulation/ with strict path-traversal guards.
 */
@Service
public class SimulationStorageService {

    public static final long MAX_THUMBNAIL_BYTES = 5L * 1024L * 1024L;
    private static final String PNG_DATA_URL_PREFIX = "data:image/png;base64,";

    private final Path uploadRoot;
    private final ObjectMapper objectMapper;

    public SimulationStorageService(StorageConfig storageConfig, ObjectMapper objectMapper) {
        this.uploadRoot = Paths.get(storageConfig.getUploadDir()).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public String saveThumbnailFromDataUrl(Long eventId, String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) {
            return null;
        }
        String trimmed = dataUrl.trim();
        if (!trimmed.startsWith(PNG_DATA_URL_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Snapshot thumbnail must be a base64-encoded PNG data URL.");
        }
        String base64Part = trimmed.substring(PNG_DATA_URL_PREFIX.length());
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Part);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Snapshot thumbnail base64 payload is invalid.", ex);
        }
        if (bytes.length == 0 || bytes.length > MAX_THUMBNAIL_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Snapshot thumbnail size must be between 1 byte and 5 MB.");
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Snapshot thumbnail could not be decoded as a valid PNG image.");
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid snapshot PNG payload.", ex);
        }

        String filename = "snap-" + UUID.randomUUID() + ".png";
        Path targetPath = resolveFilePath(eventId, filename);
        try {
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, bytes);
            return filename;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to store snapshot thumbnail.", ex);
        }
    }

    /**
     * Renders a crisp offline vector thumbnail PNG on the server when no client canvas capture is provided.
     */
    public String renderFallbackThumbnailPng(Long eventId, SimulationDtos.SerializedLayout layout) {
        int width = 640;
        int height = 400;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xF8, 0xFA, 0xFC));
            g.fillRect(0, 0, width, height);

            GeometryUtil.Bounds bounds = layout != null ? layout.bounds() : null;
            double minX = bounds != null ? bounds.minX() : 0.0;
            double minY = bounds != null ? bounds.minY() : 0.0;
            double spanX = bounds != null ? Math.max(10.0, bounds.maxX() - bounds.minX()) : 100.0;
            double spanY = bounds != null ? Math.max(10.0, bounds.maxY() - bounds.minY()) : 80.0;
            double pad = 28.0;
            double scale = Math.min((width - pad * 2.0) / spanX, (height - pad * 2.0) / spanY);
            double offsetX = (width - spanX * scale) / 2.0 - minX * scale;
            double offsetY = (height - spanY * scale) / 2.0 - minY * scale;

            if (layout != null) {
                if (layout.boundaryPoints() != null && layout.boundaryPoints().size() >= 3) {
                    Path2D path = buildPath(layout.boundaryPoints(), scale, offsetX, offsetY);
                    g.setColor(Color.WHITE);
                    g.fill(path);
                    g.setColor(new Color(0x25, 0x63, 0xEB));
                    g.setStroke(new BasicStroke(2.0f));
                    g.draw(path);
                }

                if (layout.zones() != null) {
                    for (SimulationDtos.SnapZone z : layout.zones()) {
                        if (z.shapes() == null) continue;
                        Color fill = parseColor(z.fillColor(), new Color(0x3B, 0x82, 0xF6), 55);
                        Color stroke = parseColor(z.strokeColor(), new Color(0x1D, 0x4E, 0xD8), 200);
                        for (GeometryUtil.PolygonShape shape : z.shapes()) {
                            if (shape.exterior() == null || shape.exterior().size() < 3) continue;
                            Path2D zp = buildPath(shape.exterior(), scale, offsetX, offsetY);
                            g.setColor(fill);
                            g.fill(zp);
                            g.setColor(stroke);
                            g.setStroke(new BasicStroke(1.2f));
                            g.draw(zp);
                        }
                    }
                }

                if (layout.exclusions() != null) {
                    for (SimulationDtos.SnapExclusion ex : layout.exclusions()) {
                        if (ex.points() == null || ex.points().size() < 3) continue;
                        Path2D ep = buildPath(ex.points(), scale, offsetX, offsetY);
                        g.setColor(parseColor(ex.fillColor(), new Color(0x94, 0xA3, 0xB8), 110));
                        g.fill(ep);
                        g.setColor(parseColor(ex.strokeColor(), new Color(0x47, 0x55, 0x69), 220));
                        g.setStroke(new BasicStroke(1.2f));
                        g.draw(ep);
                    }
                }

                if (layout.elements() != null) {
                    for (SimulationDtos.SnapElement el : layout.elements()) {
                        if (el.footprintPoints() == null || el.footprintPoints().size() < 3) continue;
                        Path2D fp = buildPath(el.footprintPoints(), scale, offsetX, offsetY);
                        g.setColor(parseColor(el.fillColor(), new Color(0x64, 0x74, 0x8B), 165));
                        g.fill(fp);
                        g.setColor(parseColor(el.strokeColor(), new Color(0x0F, 0x17, 0x2A), 230));
                        g.setStroke(new BasicStroke(1.2f));
                        g.draw(fp);
                    }
                }

                if (layout.edges() != null) {
                    for (SimulationDtos.SnapEdge e : layout.edges()) {
                        if (!e.active() || e.points() == null || e.points().size() < 2) continue;
                        Color edgeCol = parseColor(e.colorHex(), new Color(0x25, 0x63, 0xEB), 210);
                        g.setColor(edgeCol);
                        float strokePx = (float) Math.max(1.5, Math.min(18.0, e.widthM() * scale * 0.65));
                        g.setStroke(new BasicStroke(strokePx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        for (int i = 1; i < e.points().size(); i++) {
                            GeometryUtil.Point2D p1 = e.points().get(i - 1);
                            GeometryUtil.Point2D p2 = e.points().get(i);
                            g.drawLine(
                                    (int) Math.round(p1.x() * scale + offsetX),
                                    (int) Math.round(p1.y() * scale + offsetY),
                                    (int) Math.round(p2.x() * scale + offsetX),
                                    (int) Math.round(p2.y() * scale + offsetY));
                        }
                    }
                }

                if (layout.nodes() != null) {
                    for (SimulationDtos.SnapNode n : layout.nodes()) {
                        int nx = (int) Math.round(n.x() * scale + offsetX);
                        int ny = (int) Math.round(n.y() * scale + offsetY);
                        g.setColor(parseColor(n.fillColor(), new Color(0x3B, 0x82, 0xF6), 255));
                        g.fillOval(nx - 4, ny - 4, 8, 8);
                        g.setColor(Color.WHITE);
                        g.setStroke(new BasicStroke(1.2f));
                        g.drawOval(nx - 4, ny - 4, 8, 8);
                    }
                }
            }

            g.setColor(new Color(0x0F, 0x17, 0x2A));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            String title = layout != null && layout.eventName() != null ? layout.eventName() : "Layout Snapshot";
            g.drawString(title, 12, 20);
        } finally {
            g.dispose();
        }

        String filename = "snap-" + UUID.randomUUID() + ".png";
        Path targetPath = resolveFilePath(eventId, filename);
        try {
            Files.createDirectories(targetPath.getParent());
            ImageIO.write(image, "png", targetPath.toFile());
            return filename;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to generate fallback snapshot thumbnail.", ex);
        }
    }

    public Resource loadThumbnailResource(Long eventId, String filename) {
        if (filename == null || filename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot thumbnail not available.");
        }
        Path filePath = resolveFilePath(eventId, filename);
        if (!Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot thumbnail file not found.");
        }
        try {
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot thumbnail is unreadable.");
            }
            return resource;
        } catch (MalformedURLException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid thumbnail URI.", ex);
        }
    }

    public String saveFrameStream(Long eventId, SimulationDtos.SimulationFrameStream stream) {
        String filename = "run-" + UUID.randomUUID() + ".json";
        Path targetPath = resolveFilePath(eventId, filename);
        try {
            Files.createDirectories(targetPath.getParent());
            objectMapper.writeValue(targetPath.toFile(), stream);
            return filename;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to persist simulation frame stream.", ex);
        }
    }

    public SimulationDtos.SimulationFrameStream loadFrameStream(Long eventId, String filename) {
        if (filename == null || filename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation frame stream not available.");
        }
        Path filePath = resolveFilePath(eventId, filename);
        if (!Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation frame stream file not found.");
        }
        try {
            return objectMapper.readValue(filePath.toFile(), SimulationDtos.SimulationFrameStream.class);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read simulation frame stream.", ex);
        }
    }

    public void deleteFileIfExists(Long eventId, String filename) {
        if (filename == null || filename.isBlank()) {
            return;
        }
        try {
            Path filePath = resolveFilePath(eventId, filename);
            Files.deleteIfExists(filePath);
        } catch (IOException | ResponseStatusException ignored) {
        }
    }

    private Path resolveFilePath(Long eventId, String filename) {
        String safeName = Paths.get(filename).getFileName().toString();
        Path simDir = uploadRoot.resolve("events").resolve(String.valueOf(eventId)).resolve("simulation").normalize();
        if (!simDir.startsWith(uploadRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid simulation directory path.");
        }
        Path target = simDir.resolve(safeName).normalize();
        if (!target.startsWith(simDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid simulation artifact path.");
        }
        return target;
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
}
