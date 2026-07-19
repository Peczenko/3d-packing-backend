package com.packing.backend.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Composition root.
 *
 * <p>{@code scanBasePackages} is load-bearing: this class lives in
 * {@code com.packing.backend.app}, so the default scan would cover only that package and
 * the application would boot cleanly with zero controllers and zero adapters registered.
 * The same reasoning applies to {@link ConfigurationPropertiesScan}, whose
 * {@code @ConfigurationProperties} records live in {@code :infra}.
 */
@SpringBootApplication(scanBasePackages = "com.packing.backend")
@ConfigurationPropertiesScan("com.packing.backend")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
