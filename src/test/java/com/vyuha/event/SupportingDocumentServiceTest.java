package com.vyuha.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vyuha.auth.AppUser;
import com.vyuha.auth.UserRepository;
import com.vyuha.config.StorageConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class SupportingDocumentServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void sanitizeFilenameRemovesPathTraversalAndUnsafeChars() {
        assertThat(SupportingDocumentService.sanitizeFilename("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
        assertThat(SupportingDocumentService.sanitizeFilename("C:\\fakepath\\site<plan>.png")).isEqualTo("site_plan_.png");
        assertThat(SupportingDocumentService.sanitizeFilename("..")).isEqualTo("document");
        assertThat(SupportingDocumentService.sanitizeFilename("   ")).isEqualTo("document");
    }

    @Test
    void rejectsDisallowedFileTypesAndEmptyFiles() {
        SupportingDocumentService service = createServiceWithMockRepos();

        MockMultipartFile htmlFile = new MockMultipartFile(
                "file", "evil.html", "text/html", "<script>alert(1)</script>".getBytes());
        assertThatThrownBy(() -> service.storeDocument(1L, 2L, htmlFile, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unsupported file type");

        MockMultipartFile svgFile = new MockMultipartFile(
                "file", "vector.svg", "image/svg+xml", "<svg/>".getBytes());
        assertThatThrownBy(() -> service.storeDocument(1L, 2L, svgFile, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unsupported file type");

        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> service.storeDocument(1L, 2L, emptyFile, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void storesAndLoadsValidPdfSafelyInsideEventDirectory() throws Exception {
        SupportingDocumentRepository docRepo = mock(SupportingDocumentRepository.class);
        EventRepository eventRepo = mock(EventRepository.class);
        UserRepository userRepo = mock(UserRepository.class);

        Event event = new Event();
        AppUser user = new AppUser();
        when(eventRepo.findById(42L)).thenReturn(Optional.of(event));
        when(userRepo.findById(2L)).thenReturn(Optional.of(user));
        when(docRepo.save(any(SupportingDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        StorageConfig config = new StorageConfig();
        config.setUploadDir(tempDir.toString());
        SupportingDocumentService service = new SupportingDocumentService(docRepo, eventRepo, userRepo, config);

        byte[] pdfBytes = "%PDF-1.4\nSample Fire NOC".getBytes();
        MockMultipartFile pdf = new MockMultipartFile("file", "../fire-noc.pdf", "application/pdf", pdfBytes);

        SupportingDocument stored = service.storeDocument(42L, 2L, pdf, "Draft Fire NOC");
        assertThat(stored.getOriginalFilename()).isEqualTo("fire-noc.pdf");
        assertThat(stored.getContentType()).isEqualTo("application/pdf");
        assertThat(stored.getFileSizeBytes()).isEqualTo(pdfBytes.length);

        Path savedFile = tempDir.resolve("events").resolve("42").resolve(stored.getStoredFilename());
        assertThat(Files.exists(savedFile)).isTrue();
        assertThat(Files.readAllBytes(savedFile)).isEqualTo(pdfBytes);

        when(docRepo.findByIdAndEventId(99L, 42L)).thenReturn(Optional.of(stored));
        SupportingDocumentService.DocumentDownload download = service.loadDocument(42L, 99L);
        assertThat(download.resource().exists()).isTrue();
        assertThat(download.resource().contentLength()).isEqualTo(pdfBytes.length);
    }

    private SupportingDocumentService createServiceWithMockRepos() {
        StorageConfig config = new StorageConfig();
        config.setUploadDir(tempDir.toString());
        return new SupportingDocumentService(
                mock(SupportingDocumentRepository.class),
                mock(EventRepository.class),
                mock(UserRepository.class),
                config
        );
    }
}
