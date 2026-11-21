package com.vyuha.monitoring;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/**
 * Pure-Java ZXing QR code generator, image decoder, and unambiguous attendee pass code generator.
 */
@Service
public class PassQrCodeService {

    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int DEFAULT_QR_SIZE = 220;
    private static final int MIN_QR_SIZE = 120;
    private static final int MAX_QR_SIZE = 512;

    private final SecureRandom random = new SecureRandom();

    public String generateUniquePassCode(Predicate<String> alreadyExists) {
        for (int attempt = 0; attempt < 250; attempt++) {
            String candidate = "VYU-" + randomSegment(4) + "-" + randomSegment(4);
            if (alreadyExists == null || !alreadyExists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate a unique attendee pass code after 250 attempts.");
    }

    public String normalizePassCode(String rawPassCode) {
        if (rawPassCode == null || rawPassCode.isBlank()) {
            throw new IllegalArgumentException("Pass code must not be blank.");
        }
        return rawPassCode.trim().toUpperCase(Locale.ROOT);
    }

    public byte[] generateQrPng(String passCode, int requestedSizePx) {
        String normalized = normalizePassCode(passCode);
        int size = Math.max(MIN_QR_SIZE, Math.min(MAX_QR_SIZE,
                requestedSizePx > 0 ? requestedSizePx : DEFAULT_QR_SIZE));

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(normalized, BarcodeFormat.QR_CODE, size, size, hints);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
            return baos.toByteArray();
        } catch (WriterException | IOException ex) {
            throw new IllegalStateException("Failed to generate ZXing QR code PNG.", ex);
        }
    }

    public String decodeQrFromImage(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Uploaded QR image file is empty.");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read uploaded image file.", ex);
        }
        if (image == null) {
            throw new IllegalArgumentException("Unsupported or corrupted image format for QR scanning.");
        }

        BufferedImageLuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.List.of(BarcodeFormat.QR_CODE));

        try {
            Result result = new MultiFormatReader().decode(bitmap, hints);
            return normalizePassCode(result.getText());
        } catch (NotFoundException ex) {
            throw new IllegalArgumentException("No valid Vyuha QR code could be detected in the uploaded image.");
        }
    }

    private String randomSegment(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
