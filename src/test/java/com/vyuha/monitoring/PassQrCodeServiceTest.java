package com.vyuha.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PassQrCodeServiceTest {

    private final PassQrCodeService service = new PassQrCodeService();

    @Test
    void generatesUniqueFormattedPassCodes() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String code = service.generateUniquePassCode(generated::contains);
            assertThat(code).matches("^VYU-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}$");
            assertThat(generated.add(code)).isTrue();
        }
    }

    @Test
    void encodesAndDecodesQrPngRoundTrip() {
        String code = "VYU-7K9M-2P4Q";
        byte[] png = service.generateQrPng(code, 240);

        assertThat(png.length).isGreaterThan(100);
        // PNG magic header: 89 50 4E 47
        assertThat(png[0]).isEqualTo((byte) 0x89);
        assertThat(png[1]).isEqualTo((byte) 0x50);
        assertThat(png[2]).isEqualTo((byte) 0x4E);
        assertThat(png[3]).isEqualTo((byte) 0x47);

        String decoded = service.decodeQrFromImage(png);
        assertThat(decoded).isEqualTo(code);
    }

    @Test
    void rejectsInvalidQrImageBytes() {
        assertThatThrownBy(() -> service.decodeQrFromImage(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.decodeQrFromImage("not-an-image".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
