package com.packing.backend.infra.firebase;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
public class FirebaseConfig {

    private static final String ADMIN_ENABLED = "admin-enabled";

    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "firebase", name = ADMIN_ENABLED,
            havingValue = "true", matchIfMissing = true)
    public FirebaseApp firebaseApp(FirebaseProperties properties) {
        return FirebaseApp.getApps()
                          .stream()
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
                                                    + "firebase.admin-enabled=false to run without the Admin SDK.",
                                            e);
        }
    }
}
