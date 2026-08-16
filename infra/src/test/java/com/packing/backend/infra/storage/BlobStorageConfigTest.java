package com.packing.backend.infra.storage;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.infra.packing.PackingContractCodec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlobStorageConfigTest {

    @Test
    void exposesTheAzureArtifactStoreWhenStorageIsEnabled() {
        BlobServiceClient serviceClient = mock(BlobServiceClient.class);
        when(serviceClient.getBlobContainerClient("packing")).thenReturn(mock(BlobContainerClient.class));

        runner().withPropertyValues("app.storage.enabled=true", "app.storage.container-name=packing")
                .withBean(BlobServiceClient.class, () -> serviceClient)
                .run(context -> {
                    assertThat(context).hasSingleBean(PackingContractCodec.class);
                    assertThat(context).hasSingleBean(PackingJobArtifactStore.class);
                    assertThat(context.getBean(PackingJobArtifactStore.class))
                                                                              .isInstanceOf(AzurePackingJobArtifactStore.class);
                });
    }

    @Test
    void exposesAnUnavailableArtifactStoreWhenStorageIsDisabled() {
        runner().withPropertyValues("app.storage.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(PackingContractCodec.class);
                    assertThat(context).hasSingleBean(PackingJobArtifactStore.class);
                    PackingJobArtifactStore artifacts = context.getBean(PackingJobArtifactStore.class);
                    assertThat(artifacts).isInstanceOf(UnavailablePackingJobArtifactStore.class);
                    assertThatThrownBy(() -> artifacts.findResult(PackingJobId.generate()))
                                                                                           .isInstanceOf(ExternalServiceException.class);
                });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                                             .withConfiguration(AutoConfigurations.of(BlobStorageConfig.class, TestConfiguration.class))
                                             .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BlobStorageProperties.class)
    @ComponentScan(basePackageClasses = PackingContractCodec.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION,
                    classes = Component.class))
    static class TestConfiguration {
    }
}
