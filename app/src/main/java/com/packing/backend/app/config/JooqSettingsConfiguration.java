package com.packing.backend.app.config;

import org.jooq.conf.RenderImplicitJoinType;
import org.springframework.boot.autoconfigure.jooq.DefaultConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Not JooqConfiguration: Boot's JooqAutoConfiguration already registers a bean under that
// default name, and with allow-bean-definition-overriding false the context fails to start.
@Configuration(proxyBeanMethods = false)
public class JooqSettingsConfiguration {

    @Bean
    public DefaultConfigurationCustomizer jooqCustomizer() {
        return configuration -> configuration.settings()
                // JDBC-level, so unlike a server statement_timeout it cannot cap a Flyway backfill.
                .withQueryTimeout(10)
                // No query in this codebase uses an implicit join path, and none should: the join
                // type is chosen from FK nullability at codegen time, so a future nullable FK would
                // silently turn a project_members join into a LEFT JOIN and widen a result set the
                // membership checks depend on being empty.
                .withRenderImplicitJoinType(RenderImplicitJoinType.THROW)
                .withRenderImplicitJoinToManyType(RenderImplicitJoinType.THROW);
    }
}
