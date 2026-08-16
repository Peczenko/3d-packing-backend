package com.packing.backend.api.shared.security;

import org.springframework.security.oauth2.jwt.Jwt;

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
