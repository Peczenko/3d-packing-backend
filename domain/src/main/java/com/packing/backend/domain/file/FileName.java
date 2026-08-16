package com.packing.backend.domain.file;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.text.Normalizer;
import java.util.Locale;

public record FileName(String value) {

    public static final int MAX_LENGTH = 255;

    private static final char EXTENSION_SEPARATOR = '.';
    private static final String CURRENT_DIRECTORY = ".";
    private static final String PARENT_DIRECTORY = "..";

    public FileName {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException("File name must not be blank");
        }
        value = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        value = stripPathComponents(value).trim();

        if (value.isEmpty() || CURRENT_DIRECTORY.equals(value) || PARENT_DIRECTORY.equals(value)) {
            throw new DomainRuleViolationException("File name must not be a path segment");
        }
        if (value.length() > MAX_LENGTH) {
            throw new DomainRuleViolationException(
                                                   "File name must be at most " + MAX_LENGTH + " characters");
        }
        rejectControlCharacters(value);
        requireExtension(value);
    }

    public String extension() {
        return value.substring(value.lastIndexOf(EXTENSION_SEPARATOR) + 1)
                    .toLowerCase(Locale.ROOT);
    }

    public ModelFormat format() {
        return ModelFormat.fromExtension(extension());
    }

    private static String stripPathComponents(String value) {
        int lastSeparator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return lastSeparator < 0 ? value : value.substring(lastSeparator + 1);
    }

    private static void rejectControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new DomainRuleViolationException(
                                                       "File name must not contain control characters");
            }
        }
    }

    private static void requireExtension(String value) {
        int separator = value.lastIndexOf(EXTENSION_SEPARATOR);
        if (separator <= 0 || separator == value.length() - 1) {
            throw new DomainRuleViolationException(
                                                   "File name must have an extension, for example model.stl");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
