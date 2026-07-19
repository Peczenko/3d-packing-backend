package com.packing.backend.app.security;

import com.packing.backend.app.config.CorsProperties;
import com.packing.backend.core.user.port.in.LoadUserAuthorizationUseCase;
import com.packing.backend.infra.firebase.FirebaseProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Every route requires a verified Firebase ID token except the health probes. New
 * endpoints are therefore protected by default — a route has to be added to the permit
 * list explicitly to become public.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final FirebaseProperties firebaseProperties;
    private final CorsProperties corsProperties;
    private final LoadUserAuthorizationUseCase loadUserAuthorization;

    public SecurityConfig(FirebaseProperties firebaseProperties,
                          CorsProperties corsProperties,
                          LoadUserAuthorizationUseCase loadUserAuthorization) {
        this.firebaseProperties = firebaseProperties;
        this.corsProperties = corsProperties;
        this.loadUserAuthorization = loadUserAuthorization;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // No cookies and no server-side session — authentication is a bearer token
                // on every request, so there is no CSRF vector to defend.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                new DatabaseRoleJwtAuthenticationConverter(loadUserAuthorization))))
                .build();
    }

    /**
     * {@code withIssuerLocation} resolves the OIDC discovery document — and from it the
     * JWKS URI — lazily on the first token decode rather than at startup. That matters
     * here because the container app scales to zero: a cold replica must not fail to boot
     * just because Google was momentarily unreachable.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withIssuerLocation(firebaseProperties.issuerUri()).build();
        decoder.setJwtValidator(firebaseTokenValidator());
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> firebaseTokenValidator() {
        return new DelegatingOAuth2TokenValidator<>(
                // Timestamps (exp / nbf) plus the issuer.
                JwtValidators.createDefaultWithIssuer(firebaseProperties.issuerUri()),
                new FirebaseAudienceValidator(firebaseProperties.audience()),
                new FirebaseSubjectValidator());
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
