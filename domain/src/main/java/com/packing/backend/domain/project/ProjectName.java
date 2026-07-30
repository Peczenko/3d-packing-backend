package com.packing.backend.domain.project;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.text.Normalizer;

public record ProjectName(String value) {

    public static final int MAX_LENGTH = 128;

    public ProjectName {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException("Project name must not be blank");
        }
        value = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (value.length() > MAX_LENGTH) {
            throw new DomainRuleViolationException(
                    "Project name must be at most " + MAX_LENGTH + " characters");
        }
        rejectControlCharacters(value);
    }

    private static void rejectControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new DomainRuleViolationException(
                        "Project name must not contain control characters");
            }
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
