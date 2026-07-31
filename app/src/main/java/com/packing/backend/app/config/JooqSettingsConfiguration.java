package com.packing.backend.app.config;

import org.jooq.conf.RenderImplicitJoinType;
import org.springframework.boot.autoconfigure.jooq.DefaultConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JooqSettingsConfiguration {

    @Bean
    public DefaultConfigurationCustomizer jooqCustomizer() {
        return configuration -> configuration.settings()
                .withQueryTimeout(10)
                .withRenderImplicitJoinType(RenderImplicitJoinType.THROW)
                .withRenderImplicitJoinToManyType(RenderImplicitJoinType.THROW);
    }
}
