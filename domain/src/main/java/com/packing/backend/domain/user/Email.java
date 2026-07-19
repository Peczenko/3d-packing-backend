package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Email address, normalised to lower case so that uniqueness in the database matches
 * uniqueness in the domain.
 *
 * <p>The pattern is deliberately permissive. Firebase has already verified the address by
 * the time it reaches us; this guards against structurally impossible values, not against
 * every RFC 5322 edge case.
 */
public record Email(String value) {

    private static final int MAX_LENGTH = 320;
    private static final Pattern PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    public Email {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException("Email must not be blank");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH) {
            throw new DomainRuleViolationException(
                    "Email must be at most " + MAX_LENGTH + " characters");
        }
        if (!PATTERN.matcher(value).matches()) {
            throw new DomainRuleViolationException("Email is not a valid address: " + value);
        }
    }

    /** The part before the {@code @}, used to seed a username suggestion. */
    public String localPart() {
        return value.substring(0, value.indexOf('@'));
    }

    @Override
    public String toString() {
        return value;
    }
}
