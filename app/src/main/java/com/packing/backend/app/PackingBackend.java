package com.packing.backend.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.packing.backend")
@ConfigurationPropertiesScan("com.packing.backend")
public class PackingBackend {

    public static void main(String[] args) {
        SpringApplication.run(PackingBackend.class, args);
    }
}
