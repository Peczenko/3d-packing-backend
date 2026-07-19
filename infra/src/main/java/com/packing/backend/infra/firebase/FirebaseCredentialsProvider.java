package com.packing.backend.infra.firebase;

import com.google.auth.oauth2.GoogleCredentials;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Resolves the credentials the Admin SDK authenticates with.
 *
 * <p>Key Vault stores the service-account JSON base64-encoded, which removes every
 * shell-quoting and line-ending question around the embedded PEM private key. Raw JSON is
 * accepted too, so an operator who stored it unencoded still gets a working app rather
 * than a base64 decoding error.
 */
final class FirebaseCredentialsProvider {

    private final FirebaseProperties properties;

    FirebaseCredentialsProvider(FirebaseProperties properties) {
        this.properties = properties;
    }

    GoogleCredentials resolve() throws IOException {
        String configured = properties.serviceAccount();
        if (configured == null || configured.isBlank()) {
            // Local development: gcloud application-default login, or
            // GOOGLE_APPLICATION_CREDENTIALS pointing at a key file.
            return GoogleCredentials.getApplicationDefault();
        }
        byte[] json = decode(configured.trim());
        return GoogleCredentials.fromStream(new ByteArrayInputStream(json));
    }

    private byte[] decode(String value) {
        if (value.startsWith("{")) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "firebase.service-account is neither valid base64 nor raw JSON", e);
        }
    }
}
