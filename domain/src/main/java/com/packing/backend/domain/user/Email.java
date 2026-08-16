package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.Locale;
import java.util.regex.Pattern;

public record Email(String value) {

    private static final int MAX_LENGTH = 320;
    private static final Pattern PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    public Email {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException("Email must not be blank");
        }
        value = value.trim()
                     .toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH) {
            throw new DomainRuleViolationException(
                                                   "Email must be at most " + MAX_LENGTH + " characters");
        }
        if (!PATTERN.matcher(value)
                    .matches()) {
            throw new DomainRuleViolationException("Email is not a valid address: " + value);
        }
    }

    public String localPart() {
        return value.substring(0, value.indexOf('@'));
    }

    @Override
    public String toString() {
        return value;
    }
}
