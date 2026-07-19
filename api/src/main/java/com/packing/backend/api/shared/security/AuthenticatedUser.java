package com.packing.backend.api.shared.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The claims this application cares about, lifted from a verified Firebase ID token.
 *
 * <p>Exists so that controllers never touch {@link Jwt} directly: the claim names are a
 * Firebase detail, and pinning them here means a change of identity provider is a change
 * to one class.
 *
 * @param firebaseUid the {@code sub} claim — Firebase's user id
 * @param displayName may be null; Firebase accounts are not required to have one
 */
public record AuthenticatedUser(
        String firebaseUid,
        String email,
        String displayName,
        boolean emailVerified) {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_EMAIL_VERIFIED = "email_verified";
    private static final String CLAIM_NAME = "name";

    public static AuthenticatedUser from(Jwt jwt) {
        return new AuthenticatedUser(
                jwt.getSubject(),
                jwt.getClaimAsString(CLAIM_EMAIL),
                jwt.getClaimAsString(CLAIM_NAME),
                Boolean.TRUE.equals(jwt.getClaimAsBoolean(CLAIM_EMAIL_VERIFIED)));
    }
}
