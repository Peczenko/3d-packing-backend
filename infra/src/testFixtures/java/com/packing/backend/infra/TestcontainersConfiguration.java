package com.packing.backend.infra;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Spins up a throwaway PostgreSQL container for the test context. The
 * {@link ServiceConnection} annotation wires the JDBC url / credentials into Spring Boot
 * automatically, so jOOQ and Flyway run against the container.
 *
 * <p>Lives in {@code src/testFixtures} rather than {@code src/test} because both
 * {@code :infra} and {@code :app} need it; {@code :app} consumes it with
 * {@code testImplementation testFixtures(project(':infra'))}. Test source sets are not
 * exported between modules, test fixtures are.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
