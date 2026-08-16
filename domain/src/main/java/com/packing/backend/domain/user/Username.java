package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.Locale;
import java.util.regex.Pattern;

public record Username(String value) {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 64;

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");
    private static final Pattern ILLEGAL_CHARACTERS = Pattern.compile("[^a-z0-9._-]");

    public Username {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException("Username must not be blank");
        }
        value = value.trim()
                     .toLowerCase(Locale.ROOT);
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new DomainRuleViolationException(
                                                   "Username must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        if (!PATTERN.matcher(value)
                    .matches()) {
            throw new DomainRuleViolationException(
                                                   "Username must start with a letter or digit and contain only letters, "
                                                           + "digits, dots, underscores and hyphens: " + value);
        }
    }

    public static Username suggestionFrom(String rawText) {
        if (rawText == null) {
            throw new DomainRuleViolationException("Cannot derive a username from null");
        }
        String sanitised = ILLEGAL_CHARACTERS
                                             .matcher(rawText.trim()
                                                             .toLowerCase(Locale.ROOT))
                                             .replaceAll("");
        while (!sanitised.isEmpty() && !Character.isLetterOrDigit(sanitised.charAt(0))) {
            sanitised = sanitised.substring(1);
        }
        if (sanitised.isEmpty()) {
            throw new DomainRuleViolationException(
                                                   "Cannot derive a username from: " + rawText);
        }
        if (sanitised.length() > MAX_LENGTH) {
            sanitised = sanitised.substring(0, MAX_LENGTH);
        }
        // Pad rather than reject: a two-character email local part is perfectly legal.
        while (sanitised.length() < MIN_LENGTH) {
            sanitised = sanitised + "0";
        }
        return new Username(sanitised);
    }

    public Username withSuffix(int suffix) {
        String suffixText = Integer.toString(suffix);
        String stem = value;
        if (stem.length() + suffixText.length() > MAX_LENGTH) {
            stem = stem.substring(0, MAX_LENGTH - suffixText.length());
        }
        return new Username(stem + suffixText);
    }

    @Override
    public String toString() {
        return value;
    }
}
