package com.packing.backend.core.file;

import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record ContentDigest(long sizeBytes, Checksum checksum) {

    private static final int BUFFER_BYTES = 8192;

    public static ContentDigest of(ContentSource source) {
        return of(source, StoredFile.MAX_SIZE_BYTES, "File");
    }

    public static ContentDigest of(ContentSource source, long maxBytes, String subject) {
        MessageDigest digest = sha256();
        long size = 0;
        try (InputStream stream = source.open()) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
                if (size > maxBytes) {
                    throw new DomainRuleViolationException(subject + " must be at most " + maxBytes + " bytes");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the file content", e);
        }
        return new ContentDigest(size,
                                 Checksum.ofHex(HexFormat.of()
                                                         .formatHex(digest.digest())));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
