package com.vyuha.venue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Loads bundled offline GeoJSON site maps and parses user-imported GeoJSON or SVG vector maps
 * into normalized local metric GeoJSON FeatureCollections.
 */
@Service
public class SampleMapService {

    public static final String DEFAULT_SAMPLE_MAP_KEY = "campus-quadrangle";
    public static final String CUSTOM_UPLOAD_KEY = "custom-upload";
    public static final long MAX_VECTOR_FILE_BYTES = 5L * 1024L * 1024L;

    private static final Pattern DISALLOWED_SVG_PATTERN = Pattern.compile(
            "(<script\\b|<foreignObject\\b|javascript:|\\bon[a-z]+\\s*=)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NUMBER_PAIR_PATTERN = Pattern.compile("[-+]?(?:\\d*\\.\\d+|\\d+)");

    private static final List<VenueDtos.SampleMapSummary> BUILT_IN_MAPS = List.of(
            new VenueDtos.SampleMapSummary(
                    "campus-quadrangle",
                    "Campus Central Quadrangle",
                    "120 m × 90 m orthographic campus quadrangle with academic blocks, stage deck & east ambulance road",
                    120.0,
                    90.0
            ),
            new VenueDtos.SampleMapSummary(
                    "civic-plaza",
                    "Civic Public Plaza",
                    "100 m × 80 m municipal plaza with town hall pavilion, central fountain & west promenade",
                    100.0,
                    80.0
            ),
            new VenueDtos.SampleMapSummary(
                    "stadium-ground",
                    "Open Stadium & Fairground",
                    "150 m × 110 m open ground with north grandstand tier, field stage & emergency ring track",
                    150.0,
                    110.0
            ),
            new VenueDtos.SampleMapSummary(
                    "blank-grid",
                    "Blank Metric Grid (100 m × 80 m)",
                    "Clean local metric coordinate grid without base buildings",
                    100.0,
                    80.0
            )
    );

    private static final Map<String, String> RESOURCE_PATHS = Map.of(
            "campus-quadrangle", "sample-maps/campus-quadrangle.geojson",
            "civic-plaza", "sample-maps/civic-plaza.geojson",
            "stadium-ground", "sample-maps/stadium-ground.geojson"
    );

    private final ObjectMapper objectMapper;

    public SampleMapService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<VenueDtos.SampleMapSummary> listSampleMaps() {
        return BUILT_IN_MAPS;
    }

    public boolean isValidBuiltInKey(String key) {
        return BUILT_IN_MAPS.stream().anyMatch(m -> m.key().equals(key));
    }

    public Object loadBuiltInGeoJson(String key) {
        String resolvedKey = (key == null || key.isBlank()) ? DEFAULT_SAMPLE_MAP_KEY : key;
        if ("blank-grid".equals(resolvedKey)) {
            return createBlankGridGeoJson();
        }
        String path = RESOURCE_PATHS.get(resolvedKey);
        if (path == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown sample map key: " + resolvedKey);
        }
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, Object.class);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to load built-in sample map: " + resolvedKey, ex);
        }
    }

    public Object parseRawGeoJson(String json) {
        if (json == null || json.isBlank()) {
            return createBlankGridGeoJson();
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (IOException ex) {
            return createBlankGridGeoJson();
        }
    }

    public ImportedVectorMap importCustomVectorFile(MultipartFile file, Double targetWidthMeters) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please select a GeoJSON or SVG file to import.");
        }
        if (file.getSize() > MAX_VECTOR_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Vector map file exceeds maximum allowed size of 5 MB.");
        }

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "site-map";
        String lower = originalName.toLowerCase(Locale.ROOT);

        try {
            byte[] bytes = file.getBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (lower.endsWith(".geojson") || lower.endsWith(".json")) {
                String normalizedJson = normalizeGeoJson(content);
                return new ImportedVectorMap(originalName, normalizedJson);
            }
            if (lower.endsWith(".svg")) {
                double widthM = (targetWidthMeters != null && Double.isFinite(targetWidthMeters) && targetWidthMeters > 0)
                        ? targetWidthMeters
                        : 100.0;
                String normalizedJson = convertSvgToGeoJson(content, widthM);
                return new ImportedVectorMap(originalName, normalizedJson);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported vector map format. Upload .geojson, .json, or .svg.");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not parse vector map file: " + ex.getMessage(), ex);
        }
    }

    private String normalizeGeoJson(String rawJson) throws IOException {
        JsonNode root = objectMapper.readTree(rawJson);
        if (!root.isObject() || !"FeatureCollection".equals(root.path("type").asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "GeoJSON file must be a valid FeatureCollection.");
        }
        JsonNode features = root.path("features");
        if (!features.isArray() || features.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "GeoJSON FeatureCollection must contain at least one feature.");
        }
        return objectMapper.writeValueAsString(root);
    }

    private String convertSvgToGeoJson(String svgContent, double targetWidthMeters) throws Exception {
        if (DISALLOWED_SVG_PATTERN.matcher(svgContent).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SVG vector map contains disallowed active script or event handler content.");
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);

        Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(svgContent.getBytes(StandardCharsets.UTF_8)));
        Element root = doc.getDocumentElement();
        if (root == null || !"svg".equalsIgnoreCase(root.getTagName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is not a valid SVG document.");
        }

        double[] viewBox = parseSvgViewBox(root);
        double minX = viewBox[0];
        double minY = viewBox[1];
        double vbWidth = viewBox[2] > 0 ? viewBox[2] : 100.0;
        double vbHeight = viewBox[3] > 0 ? viewBox[3] : 80.0;
        double scale = targetWidthMeters / vbWidth;

        ObjectNode fc = objectMapper.createObjectNode();
        fc.put("type", "FeatureCollection");
        fc.put("name", CUSTOM_UPLOAD_KEY);
        ObjectNode props = fc.putObject("properties");
        props.put("key", CUSTOM_UPLOAD_KEY);
        props.put("title", "Imported SVG Vector Plan");
        props.put("widthMeters", targetWidthMeters);
        props.put("heightMeters", vbHeight * scale);
        ArrayNode features = fc.putArray("features");

        NodeList rects = doc.getElementsByTagName("rect");
        for (int i = 0; i < rects.getLength(); i++) {
            Element el = (Element) rects.item(i);
            double x = parseDoubleAttr(el, "x", 0.0);
            double y = parseDoubleAttr(el, "y", 0.0);
            double w = parseDoubleAttr(el, "width", 0.0);
            double h = parseDoubleAttr(el, "height", 0.0);
            if (w > 0 && h > 0) {
                List<double[]> ring = List.of(
                        new double[]{(x - minX) * scale, (y - minY) * scale},
                        new double[]{(x + w - minX) * scale, (y - minY) * scale},
                        new double[]{(x + w - minX) * scale, (y + h - minY) * scale},
                        new double[]{(x - minX) * scale, (y + h - minY) * scale}
                );
                addPolygonFeature(features, el.getAttribute("id"), "rect", ring);
            }
        }

        NodeList polygons = doc.getElementsByTagName("polygon");
        for (int i = 0; i < polygons.getLength(); i++) {
            Element el = (Element) polygons.item(i);
            List<double[]> pts = parsePointsAttr(el.getAttribute("points"), minX, minY, scale);
            if (pts.size() >= 3) {
                addPolygonFeature(features, el.getAttribute("id"), "polygon", pts);
            }
        }

        NodeList polylines = doc.getElementsByTagName("polyline");
        for (int i = 0; i < polylines.getLength(); i++) {
            Element el = (Element) polylines.item(i);
            List<double[]> pts = parsePointsAttr(el.getAttribute("points"), minX, minY, scale);
            if (pts.size() >= 2) {
                addLineFeature(features, el.getAttribute("id"), pts);
            }
        }

        if (features.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No supported <rect>, <polygon>, or <polyline> shapes found in SVG.");
        }
        return objectMapper.writeValueAsString(fc);
    }

    private void addPolygonFeature(ArrayNode features, String id, String kind, List<double[]> ring) {
        ObjectNode feat = features.addObject();
        feat.put("type", "Feature");
        ObjectNode fProps = feat.putObject("properties");
        fProps.put("role", "imported_shape");
        fProps.put("kind", kind);
        fProps.put("label", (id != null && !id.isBlank()) ? id : "Imported Shape");

        ObjectNode geom = feat.putObject("geometry");
        geom.put("type", "Polygon");
        ArrayNode outer = geom.putArray("coordinates").addArray();
        for (double[] pt : ring) {
            ArrayNode coord = outer.addArray();
            coord.add(pt[0]);
            coord.add(pt[1]);
        }
        double[] first = ring.get(0);
        ArrayNode closing = outer.addArray();
        closing.add(first[0]);
        closing.add(first[1]);
    }

    private void addLineFeature(ArrayNode features, String id, List<double[]> pts) {
        ObjectNode feat = features.addObject();
        feat.put("type", "Feature");
        ObjectNode fProps = feat.putObject("properties");
        fProps.put("role", "imported_line");
        fProps.put("label", (id != null && !id.isBlank()) ? id : "Imported Line");

        ObjectNode geom = feat.putObject("geometry");
        geom.put("type", "LineString");
        ArrayNode coords = geom.putArray("coordinates");
        for (double[] pt : pts) {
            ArrayNode coord = coords.addArray();
            coord.add(pt[0]);
            coord.add(pt[1]);
        }
    }

    private List<double[]> parsePointsAttr(String pointsAttr, double minX, double minY, double scale) {
        List<Double> nums = new ArrayList<>();
        if (pointsAttr != null) {
            Matcher m = NUMBER_PAIR_PATTERN.matcher(pointsAttr);
            while (m.find()) {
                nums.add(Double.parseDouble(m.group()));
            }
        }
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i + 1 < nums.size(); i += 2) {
            pts.add(new double[]{(nums.get(i) - minX) * scale, (nums.get(i + 1) - minY) * scale});
        }
        return pts;
    }

    private double[] parseSvgViewBox(Element root) {
        String vb = root.getAttribute("viewBox");
        if (vb != null && !vb.isBlank()) {
            Matcher m = NUMBER_PAIR_PATTERN.matcher(vb);
            List<Double> vals = new ArrayList<>();
            while (m.find() && vals.size() < 4) {
                vals.add(Double.parseDouble(m.group()));
            }
            if (vals.size() == 4) {
                return new double[]{vals.get(0), vals.get(1), vals.get(2), vals.get(3)};
            }
        }
        double w = parseDoubleAttr(root, "width", 100.0);
        double h = parseDoubleAttr(root, "height", 80.0);
        return new double[]{0.0, 0.0, w, h};
    }

    private double parseDoubleAttr(Element el, String name, double fallback) {
        String raw = el.getAttribute(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Matcher m = NUMBER_PAIR_PATTERN.matcher(raw);
        if (m.find()) {
            return Double.parseDouble(m.group());
        }
        return fallback;
    }

    private Object createBlankGridGeoJson() {
        ObjectNode fc = objectMapper.createObjectNode();
        fc.put("type", "FeatureCollection");
        fc.put("name", "blank-grid");
        ObjectNode props = fc.putObject("properties");
        props.put("key", "blank-grid");
        props.put("title", "Blank Metric Grid (100 m × 80 m)");
        props.put("widthMeters", 100.0);
        props.put("heightMeters", 80.0);
        fc.putArray("features");
        return fc;
    }

    public record ImportedVectorMap(String filename, String geoJson) {
    }
}
