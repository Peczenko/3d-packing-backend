package com.packing.backend.infra.firebase;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.packing.backend.core.user.port.out.FirebaseUserDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Wires the Firebase Admin SDK.
 *
 * <p>The two {@link FirebaseUserDirectory} beans are selected by the same property with
 * opposite values, so exactly one is always defined and the choice does not depend on
 * bean-definition ordering (which is what makes {@code @ConditionalOnMissingBean}
 * unreliable outside auto-configuration).
 */
@Configuration(proxyBeanMethods = false)
public class FirebaseConfig {

    private static final String ADMIN_ENABLED = "admin-enabled";

    /**
     * {@code destroyMethod = ""} because {@link FirebaseApp#delete()} unregisters the app
     * globally; letting Spring call it would break any other context in the same JVM,
     * which matters when several test contexts are cached side by side.
     */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "firebase", name = ADMIN_ENABLED,
            havingValue = "true", matchIfMissing = true)
    public FirebaseApp firebaseApp(FirebaseProperties properties) throws IOException {
        // FirebaseApp keeps a static registry, so initialising twice in one JVM throws.
        // Reuse whatever is already there — again, test contexts.
        return FirebaseApp.getApps().stream()
                .filter(app -> FirebaseApp.DEFAULT_APP_NAME.equals(app.getName()))
                .findFirst()
                .orElseGet(() -> FirebaseApp.initializeApp(buildOptions(properties)));
    }

    @Bean
    @ConditionalOnProperty(prefix = "firebase", name = ADMIN_ENABLED,
            havingValue = "true", matchIfMissing = true)
    public FirebaseAuth firebaseAuth(FirebaseApp firebaseApp) {
        return FirebaseAuth.getInstance(firebaseApp);
    }

    @Bean
    @ConditionalOnProperty(prefix = "firebase", name = ADMIN_ENABLED,
            havingValue = "true", matchIfMissing = true)
    public FirebaseUserDirectory firebaseUserDirectory(FirebaseAuth firebaseAuth) {
        return new FirebaseAdminUserDirectory(firebaseAuth);
    }

    @Bean
    @ConditionalOnProperty(prefix = "firebase", name = ADMIN_ENABLED, havingValue = "false")
    public FirebaseUserDirectory unavailableFirebaseUserDirectory() {
        return new UnavailableFirebaseUserDirectory();
    }

    private FirebaseOptions buildOptions(FirebaseProperties properties) {
        try {
            return FirebaseOptions.builder()
                    .setProjectId(properties.projectId())
                    .setCredentials(new FirebaseCredentialsProvider(properties).resolve())
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not resolve Firebase credentials. Set firebase.service-account, "
                            + "provide Application Default Credentials, or set "
                            + "firebase.admin-enabled=false to run without the Admin SDK.", e);
        }
    }
}
