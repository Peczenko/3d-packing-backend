package com.packing.backend.app.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects tokens not minted for this Firebase project.
 *
 * <p>Firebase sets {@code aud} to the bare project id. Without this check, a validly
 * signed ID token from <em>any</em> Firebase project would be accepted, because every
 * project's tokens are signed by the same Google key set — signature and issuer checks
 * alone are not sufficient.
 */
class FirebaseAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedAudience;

    FirebaseAudienceValidator(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (token.getAudience() != null && token.getAudience().contains(expectedAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "The required audience '" + expectedAudience + "' is missing",
                null));
    }
}
