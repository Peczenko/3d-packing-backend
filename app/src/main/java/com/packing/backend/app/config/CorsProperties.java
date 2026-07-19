package com.packing.backend.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * @param allowedOrigins browser origins permitted to call {@code /api/**}. Empty by
 *                       default, which allows none — a permissive default is not
 *                       something to inherit by accident.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {
}
