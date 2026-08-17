package com.packing.backend.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {
}
