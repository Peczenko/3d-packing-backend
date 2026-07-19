package com.packing.backend.app.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These two validators are what stop a validly signed token from another Firebase project
 * being accepted. Every Firebase project's ID tokens are signed by the same Google key
 * set, so signature and issuer checks alone are not sufficient.
 */
class FirebaseTokenValidatorTest {

    private static final String PROJECT_ID = "packing-prod";

    private final FirebaseAudienceValidator audienceValidator =
            new FirebaseAudienceValidator(PROJECT_ID);
    private final FirebaseSubjectValidator subjectValidator = new FirebaseSubjectValidator();

    private Jwt token(List<String> audience, String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("email", "ada@example.com");
        if (audience != null) {
            builder.audience(audience);
        }
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.build();
    }

    @Test
    void acceptsATokenMintedForThisProject() {
        assertThat(audienceValidator.validate(token(List.of(PROJECT_ID), "uid")).hasErrors())
                .isFalse();
    }

    @Test
    void rejectsATokenFromAnotherFirebaseProject() {
        assertThat(audienceValidator.validate(token(List.of("someone-elses-project"), "uid")).hasErrors())
                .isTrue();
    }

    @Test
    void rejectsATokenWithNoAudience() {
        assertThat(audienceValidator.validate(token(null, "uid")).hasErrors()).isTrue();
    }

    @Test
    void acceptsANonEmptySubject() {
        assertThat(subjectValidator.validate(token(List.of(PROJECT_ID), "uid")).hasErrors())
                .isFalse();
    }

    @Test
    void rejectsAMissingSubject() {
        assertThat(subjectValidator.validate(token(List.of(PROJECT_ID), null)).hasErrors()).isTrue();
    }

    @Test
    void rejectsABlankSubject() {
        assertThat(subjectValidator.validate(token(List.of(PROJECT_ID), "   ")).hasErrors()).isTrue();
    }
}
