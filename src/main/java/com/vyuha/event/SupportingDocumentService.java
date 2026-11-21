package com.vyuha.event;

import com.vyuha.auth.AppUser;
import com.vyuha.auth.UserRepository;
import com.vyuha.config.StorageConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handles secure local upload, validation, retrieval, and cleanup of event supporting documents.
 */
@Service
public class SupportingDocumentService {

    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L;

    private static final Map<String, Set<String>> ALLOWED_MIME_TYPES = Map.of(
            "pdf", Set.of("application/pdf", "application/octet-stream"),
            "png", Set.of("image/png", "application/octet-stream"),
            "jpg", Set.of("image/jpeg", "image/pjpeg", "application/octet-stream"),
            "jpeg", Set.of("image/jpeg", "image/pjpeg", "application/octet-stream"),
            "docx", Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/zip",
                    "application/octet-stream"
            )
    );

    private static final Map<String, String> CANONICAL_MIME_TYPES = Map.of(
            "pdf", "application/pdf",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final SupportingDocumentRepository documentRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final Path uploadRoot;

    public SupportingDocumentService(SupportingDocumentRepository documentRepository,
                                     EventRepository eventRepository,
                                     UserRepository userRepository,
                                     StorageConfig storageConfig) {
        this.documentRepository = documentRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.uploadRoot = Paths.get(storageConfig.getUploadDir()).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public List<SupportingDocument> getDocumentsForEvent(Long eventId) {
        return documentRepository.findByEventIdWithUploader(eventId);
    }

    @Transactional
    public SupportingDocument storeDocument(Long eventId,
                                            Long uploaderUserId,
                                            MultipartFile file,
                                            String documentNote) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please select a non-empty file to upload.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File exceeds maximum allowed size of 10 MB.");
        }

        String safeOriginalName = sanitizeFilename(file.getOriginalFilename());
        String extension = extractExtension(safeOriginalName);
        if (!ALLOWED_MIME_TYPES.containsKey(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported file type. Allowed formats: PDF, PNG, JPG, JPEG, DOCX.");
        }

        String rawContentType = file.getContentType() != null
                ? file.getContentType().toLowerCase(Locale.ROOT).split(";")[0].trim()
                : "application/octet-stream";
        if (!ALLOWED_MIME_TYPES.get(extension).contains(rawContentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File MIME type (" + rawContentType + ") does not match allowed ." + extension + " format.");
        }

        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found."));
        AppUser uploader = userRepository.findById(uploaderUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Uploader account not found."));

        String storedFilename = UUID.randomUUID() + "." + extension;
        Path eventDir = resolveEventDir(eventId);
        Path targetPath = eventDir.resolve(storedFilename).normalize();
        if (!targetPath.startsWith(eventDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid storage path.");
        }

        try {
            Files.createDirectories(eventDir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save uploaded document on local storage.", ex);
        }

        SupportingDocument doc = new SupportingDocument();
        doc.setEvent(event);
        doc.setOriginalFilename(safeOriginalName);
        doc.setStoredFilename(storedFilename);
        doc.setContentType(CANONICAL_MIME_TYPES.get(extension));
        doc.setFileSizeBytes(file.getSize());
        doc.setDocumentNote(documentNote != null && !documentNote.isBlank() ? documentNote.trim() : null);
        doc.setUploadedBy(uploader);
        doc.setUploadedAt(Instant.now());
        return documentRepository.save(doc);
    }

    @Transactional(readOnly = true)
    public DocumentDownload loadDocument(Long eventId, Long docId) {
        SupportingDocument doc = documentRepository.findByIdAndEventId(docId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found."));

        Path eventDir = resolveEventDir(eventId);
        Path filePath = eventDir.resolve(doc.getStoredFilename()).normalize();
        if (!filePath.startsWith(eventDir) || !Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored document file could not be located.");
        }

        try {
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document file is unreadable.");
            }
            return new DocumentDownload(doc, resource);
        } catch (MalformedURLException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid document URI.", ex);
        }
    }

    @Transactional
    public void deleteDocument(Long eventId, Long docId) {
        SupportingDocument doc = documentRepository.findByIdAndEventId(docId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found."));

        Path eventDir = resolveEventDir(eventId);
        Path filePath = eventDir.resolve(doc.getStoredFilename()).normalize();
        documentRepository.delete(doc);

        if (filePath.startsWith(eventDir)) {
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException ignored) {
                // DB metadata removed; stale file deletion failure should not block operation.
            }
        }
    }

    public void deleteEventDirectory(Long eventId) {
        Path eventDir = resolveEventDir(eventId);
        if (!Files.exists(eventDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(eventDir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private Path resolveEventDir(Long eventId) {
        Path dir = uploadRoot.resolve("events").resolve(String.valueOf(eventId)).normalize();
        if (!dir.startsWith(uploadRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event storage path.");
        }
        return dir;
    }

    static String sanitizeFilename(String rawFilename) {
        if (rawFilename == null || rawFilename.isBlank()) {
            return "document";
        }
        String normalizedSlashes = rawFilename.replace('\\', '/');
        int lastSlash = normalizedSlashes.lastIndexOf('/');
        String baseName = lastSlash >= 0 ? normalizedSlashes.substring(lastSlash + 1) : normalizedSlashes;
        String cleaned = baseName.replaceAll("[^A-Za-z0-9._\\- ]", "_").trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            return "document";
        }
        if (cleaned.length() > 180) {
            return cleaned.substring(cleaned.length() - 180);
        }
        return cleaned;
    }

    static String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record DocumentDownload(SupportingDocument metadata, Resource resource) {
    }
}
