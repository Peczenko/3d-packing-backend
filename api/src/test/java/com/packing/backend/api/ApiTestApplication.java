package com.packing.backend.api;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Test-only composition root for the {@code :api} slice tests.
 *
 * <p>{@code @WebMvcTest} needs a {@code @SpringBootConfiguration} reachable by walking up
 * the package hierarchy from the test class. This module has no Spring Boot Gradle plugin
 * and no application class of its own — that lives in {@code :app} and is deliberately not
 * on this module's classpath.
 *
 * <p>It must be {@code @SpringBootApplication} rather than a bare
 * {@code @SpringBootConfiguration}: {@code @WebMvcTest} works by applying its type filter
 * to the configuration class's {@code @ComponentScan}, and without one no controllers get
 * registered and every request 404s.
 *
 * <p>{@code @EnableMethodSecurity} mirrors the real {@code SecurityConfig} in {@code :app}
 * so that {@code @PreAuthorize} is actually enforced in these tests.
 *
 * <p>Test sources are not exported between Gradle modules, so this is invisible to
 * {@code :app} and cannot collide with the real application class.
 */
@SpringBootApplication
@EnableMethodSecurity
public class ApiTestApplication {
}
