package com.packing.backend.app.config;

import org.jooq.conf.RenderImplicitJoinType;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JooqSettingsConfigurationTest {

    @Test
    void appliesTimeoutAndForbidsImplicitJoinPaths() {
        DefaultConfiguration configuration = new DefaultConfiguration();

        new JooqSettingsConfiguration().jooqCustomizer()
                                       .customize(configuration);

        assertThat(configuration.settings()
                                .getQueryTimeout()).isEqualTo(10);
        assertThat(configuration.settings()
                                .getRenderImplicitJoinType())
                                                             .isEqualTo(RenderImplicitJoinType.THROW);
        assertThat(configuration.settings()
                                .getRenderImplicitJoinToManyType())
                                                                   .isEqualTo(RenderImplicitJoinType.THROW);
    }
}
