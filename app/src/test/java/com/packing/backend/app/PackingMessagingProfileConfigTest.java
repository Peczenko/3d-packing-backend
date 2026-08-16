package com.packing.backend.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PackingMessagingProfileConfigTest {

    private static final String CONNECTION_STRING =
            "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                    + "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";

    @Test
    void localProfileLeavesTheConnectionStringUnsetForAnAmbientEnvVar() {
        assertConnectionStringUnset("local");
    }

    @Test
    void azureProfileIgnoresAnAmbientConnectionStringAndSelectsManagedIdentityNamespace() {
        runner("azure").withPropertyValues(
                        "SERVICE_BUS_CONNECTION_STRING=" + CONNECTION_STRING,
                        "SERVICE_BUS_NAMESPACE=packing-production")
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty("packing.messaging.connection-string"))
                            .isNull();
                    assertThat(context.getEnvironment().getProperty("packing.messaging.fully-qualified-namespace"))
                            .isEqualTo("packing-production.servicebus.windows.net");
                });
    }

    // docker-compose.yml sets the container's PACKING_MESSAGING_CONNECTION_STRING env var, which Spring's
    // relaxed binding maps onto packing.messaging.connection-string with no properties file involved. A plain
    // withPropertyValues() entry lands in a MapPropertySource, not a SystemEnvironmentPropertySource, and would
    // prove nothing about that relaxed matching -- so this installs a real SystemEnvironmentPropertySource.
    @Test
    void composeSuppliesTheConnectionStringThroughARealEnvironmentVariable() {
        runner("local")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("PACKING_MESSAGING_CONNECTION_STRING", CONNECTION_STRING))))
                .run(context -> assertThat(context.getEnvironment()
                        .getProperty("packing.messaging.connection-string")).isEqualTo(CONNECTION_STRING));
    }

    private static void assertConnectionStringUnset(String profile) {
        runner(profile).withPropertyValues("SERVICE_BUS_CONNECTION_STRING=" + CONNECTION_STRING)
                .run(context -> assertThat(context.getEnvironment()
                        .getProperty("packing.messaging.connection-string")).isNull());
    }

    private static ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.config.location=" + mainResourcesDirectory(),
                        "spring.profiles.active=" + profile);
    }

    // app/src/test/resources/application.properties shadows classpath:/application.properties on the
    // test runtime classpath, so point spring.config.location at the main resources output directory
    // instead (found via application-azure.properties, which exists only in main) to load the real mapping.
    private static String mainResourcesDirectory() {
        URL azureProperties = PackingMessagingProfileConfigTest.class.getClassLoader()
                .getResource("application-azure.properties");
        assertThat(azureProperties)
                .as("application-azure.properties must resolve from the main resources output")
                .isNotNull();
        URI azureUri = URI.create(azureProperties.toString());
        Path mainResources = Path.of(azureUri).getParent();
        return mainResources.toUri().toString();
    }
}
