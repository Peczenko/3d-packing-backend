package com.packing.backend.app.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class FirebaseAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedAudience;

    FirebaseAudienceValidator(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (token.getAudience() != null && token.getAudience()
                                                .contains(expectedAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                                                  "invalid_token",
                                                                  "The required audience '" + expectedAudience + "' is missing",
                                                                  null));
    }
}
