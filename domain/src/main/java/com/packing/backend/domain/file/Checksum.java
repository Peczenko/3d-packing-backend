package com.packing.backend.domain.file;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.Locale;
import java.util.regex.Pattern;

public record Checksum(String value) {

    public static final int HEX_LENGTH = 64;

    private static final Pattern HEX = Pattern.compile("^[0-9a-f]{" + HEX_LENGTH + "}$");

    public Checksum {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException("Checksum must not be blank");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!HEX.matcher(value).matches()) {
            throw new DomainRuleViolationException(
                    "Checksum must be " + HEX_LENGTH + " hexadecimal characters (SHA-256)");
        }
    }

    public static Checksum ofHex(String hex) {
        return new Checksum(hex);
    }

    @Override
    public String toString() {
        return value;
    }
}
