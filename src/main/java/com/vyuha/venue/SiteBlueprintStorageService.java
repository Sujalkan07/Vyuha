package com.vyuha.venue;

import com.vyuha.config.StorageConfig;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Handles secure local storage, validation, and dimension extraction for site blueprint/photo uploads.
 * Stores files under {uploadDir}/events/{eventId}/venue/ so Event deletion cleans them automatically.
 */
@Service
public class SiteBlueprintStorageService {

    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L;

    private static final Map<String, Set<String>> ALLOWED_MIME_TYPES = Map.of(
            "png", Set.of("image/png", "application/octet-stream"),
            "jpg", Set.of("image/jpeg", "image/pjpeg", "application/octet-stream"),
            "jpeg", Set.of("image/jpeg", "image/pjpeg", "application/octet-stream"),
            "webp", Set.of("image/webp", "application/octet-stream"),
            "svg", Set.of("image/svg+xml", "text/xml", "application/xml", "application/octet-stream")
    );

    private static final Map<String, String> CANONICAL_MIME_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "webp", "image/webp",
            "svg", "image/svg+xml"
    );

    private static final Pattern DISALLOWED_SVG_PATTERN = Pattern.compile(
            "(<script\\b|<foreignObject\\b|javascript:|\\bon[a-z]+\\s*=)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?(?:\\d*\\.\\d+|\\d+)");

    private final Path uploadRoot;

    public SiteBlueprintStorageService(StorageConfig storageConfig) {
        this.uploadRoot = Paths.get(storageConfig.getUploadDir()).toAbsolutePath().normalize();
    }

    public StoredBlueprint storeBlueprint(Long eventId, MultipartFile file, String previousStoredFilename) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please select a blueprint image file to upload.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Blueprint image exceeds maximum allowed size of 10 MB.");
        }

        String safeOriginalName = sanitizeFilename(file.getOriginalFilename());
        String extension = extractExtension(safeOriginalName);
        if (!ALLOWED_MIME_TYPES.containsKey(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported blueprint format. Allowed formats: PNG, JPG, JPEG, WEBP, SVG.");
        }

        String rawContentType = file.getContentType() != null
                ? file.getContentType().toLowerCase(Locale.ROOT).split(";")[0].trim()
                : "application/octet-stream";
        if (!ALLOWED_MIME_TYPES.get(extension).contains(rawContentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Blueprint MIME type (" + rawContentType + ") does not match ." + extension + " format.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded blueprint file.", ex);
        }

        ImageDimensions dims = validateAndMeasure(extension, bytes);

        String storedFilename = UUID.randomUUID() + "." + extension;
        Path venueDir = resolveEventVenueDir(eventId);
        Path targetPath = venueDir.resolve(storedFilename).normalize();
        if (!targetPath.startsWith(venueDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid storage path.");
        }

        try {
            Files.createDirectories(venueDir);
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save blueprint image on local storage.", ex);
        }

        if (previousStoredFilename != null && !previousStoredFilename.isBlank()) {
            Path oldFile = venueDir.resolve(previousStoredFilename).normalize();
            if (oldFile.startsWith(venueDir)) {
                try {
                    Files.deleteIfExists(oldFile);
                } catch (IOException ignored) {
                }
            }
        }

        return new StoredBlueprint(
                safeOriginalName,
                storedFilename,
                CANONICAL_MIME_TYPES.get(extension),
                (long) bytes.length,
                dims.width(),
                dims.height()
        );
    }

    public StoredBlueprint storeGeneratedPngBlueprint(Long eventId, String originalFilename, byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generated blueprint PNG is empty.");
        }
        String safeOriginalName = sanitizeFilename(originalFilename);
        ImageDimensions dims = validateAndMeasure("png", pngBytes);
        String storedFilename = UUID.randomUUID() + ".png";
        Path venueDir = resolveEventVenueDir(eventId);
        Path targetPath = venueDir.resolve(storedFilename).normalize();
        if (!targetPath.startsWith(venueDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid storage path.");
        }
        try {
            Files.createDirectories(venueDir);
            Files.write(targetPath, pngBytes);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save generated blueprint image.", ex);
        }
        return new StoredBlueprint(
                safeOriginalName,
                storedFilename,
                "image/png",
                (long) pngBytes.length,
                dims.width(),
                dims.height()
        );
    }

    public Resource loadBlueprintResource(Long eventId, String storedFilename) {
        if (storedFilename == null || storedFilename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No blueprint uploaded for this venue.");
        }
        Path venueDir = resolveEventVenueDir(eventId);
        Path filePath = venueDir.resolve(storedFilename).normalize();
        if (!filePath.startsWith(venueDir) || !Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored blueprint file could not be located.");
        }
        try {
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Blueprint image is unreadable.");
            }
            return resource;
        } catch (MalformedURLException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid blueprint URI.", ex);
        }
    }

    private ImageDimensions validateAndMeasure(String extension, byte[] bytes) {
        if ("svg".equals(extension)) {
            String svgText = new String(bytes, StandardCharsets.UTF_8);
            if (DISALLOWED_SVG_PATTERN.matcher(svgText).find()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Uploaded SVG contains disallowed active script or event handler content.");
            }
            return parseSvgDimensions(bytes);
        }
        if ("webp".equals(extension)) {
            ImageDimensions webpDims = parseWebpDimensions(bytes);
            if (webpDims == null || webpDims.width() <= 0 || webpDims.height() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded WEBP image header is invalid.");
            }
            return webpDims;
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Uploaded file could not be decoded as a valid ." + extension + " image.");
            }
            return new ImageDimensions(img.getWidth(), img.getHeight());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Failed to decode uploaded ." + extension + " image.", ex);
        }
    }

    private ImageDimensions parseSvgDimensions(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            Element root = doc.getDocumentElement();
            if (root == null || !"svg".equalsIgnoreCase(root.getTagName())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is not a valid SVG root element.");
            }
            String vb = root.getAttribute("viewBox");
            if (vb != null && !vb.isBlank()) {
                Matcher m = NUMBER_PATTERN.matcher(vb);
                List<Double> nums = new ArrayList<>();
                while (m.find() && nums.size() < 4) {
                    nums.add(Double.parseDouble(m.group()));
                }
                if (nums.size() == 4 && nums.get(2) > 0 && nums.get(3) > 0) {
                    return new ImageDimensions((int) Math.round(nums.get(2)), (int) Math.round(nums.get(3)));
                }
            }
            int w = parseSvgPixelAttr(root.getAttribute("width"), 1200);
            int h = parseSvgPixelAttr(root.getAttribute("height"), 800);
            return new ImageDimensions(Math.max(1, w), Math.max(1, h));
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid SVG document: " + ex.getMessage(), ex);
        }
    }

    private int parseSvgPixelAttr(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Matcher m = NUMBER_PATTERN.matcher(raw);
        if (m.find()) {
            return (int) Math.round(Double.parseDouble(m.group()));
        }
        return fallback;
    }

    static ImageDimensions parseWebpDimensions(byte[] data) {
        if (data == null || data.length < 30) {
            return null;
        }
        if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F'
                || data[8] != 'W' || data[9] != 'E' || data[10] != 'B' || data[11] != 'P') {
            return null;
        }
        String chunk = new String(data, 12, 4, StandardCharsets.US_ASCII);
        if ("VP8 ".equals(chunk) && data.length >= 30) {
            int w = ((data[26] & 0xFF) | ((data[27] & 0xFF) << 8)) & 0x3FFF;
            int h = ((data[28] & 0xFF) | ((data[29] & 0xFF) << 8)) & 0x3FFF;
            return new ImageDimensions(w, h);
        }
        if ("VP8L".equals(chunk) && data.length >= 25) {
            int b1 = data[21] & 0xFF;
            int b2 = data[22] & 0xFF;
            int b3 = data[23] & 0xFF;
            int b4 = data[24] & 0xFF;
            int bits = b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);
            int w = (bits & 0x3FFF) + 1;
            int h = ((bits >> 14) & 0x3FFF) + 1;
            return new ImageDimensions(w, h);
        }
        if ("VP8X".equals(chunk) && data.length >= 30) {
            int w = 1 + ((data[24] & 0xFF) | ((data[25] & 0xFF) << 8) | ((data[26] & 0xFF) << 16));
            int h = 1 + ((data[27] & 0xFF) | ((data[28] & 0xFF) << 8) | ((data[29] & 0xFF) << 16));
            return new ImageDimensions(w, h);
        }
        return null;
    }

    private Path resolveEventVenueDir(Long eventId) {
        Path dir = uploadRoot.resolve("events").resolve(String.valueOf(eventId)).resolve("venue").normalize();
        if (!dir.startsWith(uploadRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid venue storage path.");
        }
        return dir;
    }

    private static String sanitizeFilename(String rawFilename) {
        if (rawFilename == null || rawFilename.isBlank()) {
            return "blueprint";
        }
        String normalizedSlashes = rawFilename.replace('\\', '/');
        int lastSlash = normalizedSlashes.lastIndexOf('/');
        String baseName = lastSlash >= 0 ? normalizedSlashes.substring(lastSlash + 1) : normalizedSlashes;
        String cleaned = baseName.replaceAll("[^A-Za-z0-9._\\- ]", "_").trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            return "blueprint";
        }
        if (cleaned.length() > 180) {
            return cleaned.substring(cleaned.length() - 180);
        }
        return cleaned;
    }

    private static String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record ImageDimensions(int width, int height) {
    }

    public record StoredBlueprint(
            String originalFilename,
            String storedFilename,
            String contentType,
            long fileSizeBytes,
            int widthPx,
            int heightPx
    ) {
    }
}
