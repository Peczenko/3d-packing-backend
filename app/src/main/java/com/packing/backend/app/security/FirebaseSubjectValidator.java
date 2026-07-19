package com.packing.backend.app.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Enforces Firebase's requirement that {@code sub} be a non-empty uid.
 *
 * <p>The whole user model is keyed on this claim, so an empty subject must fail
 * authentication rather than reach the provisioning path.
 */
class FirebaseSubjectValidator implements OAuth2TokenValidator<Jwt> {

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String subject = token.getSubject();
        if (subject != null && !subject.isBlank()) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "The 'sub' claim must be a non-empty Firebase uid", null));
    }
}
