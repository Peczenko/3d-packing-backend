package com.packing.backend.infra.firebase;

import com.google.auth.oauth2.GoogleCredentials;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class FirebaseCredentialsProvider {

    private final FirebaseProperties properties;

    FirebaseCredentialsProvider(FirebaseProperties properties) {
        this.properties = properties;
    }

    GoogleCredentials resolve() throws IOException {
        String configured = properties.serviceAccount();
        if (configured == null || configured.isBlank()) {
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
            return Base64.getDecoder()
                         .decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                                            "firebase.service-account is neither valid base64 nor raw JSON",
                                            e);
        }
    }
}
