package com.packing.backend.infra.storage;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.infra.packing.PackingContractCodec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlobStorageConfigTest {

    @Test
    void bothStoresShareOneContainerWhenStorageIsEnabled() {
        BlobServiceClient serviceClient = mock(BlobServiceClient.class);
        when(serviceClient.getBlobContainerClient("packing")).thenReturn(mock(BlobContainerClient.class));

        runner().withPropertyValues("app.storage.enabled=true", "app.storage.container-name=packing")
                .withBean(BlobServiceClient.class, () -> serviceClient)
                .run(context -> {
                    assertThat(context).hasSingleBean(BlobContainer.class);
                    assertThat(context).hasSingleBean(BinaryStorage.class);
                    assertThat(context).hasSingleBean(PackingJobArtifactStore.class);
                    verify(serviceClient).getBlobContainerClient("packing");
                });
    }

    @Test
    void bothStoresReportTheMissingDependencyWhenStorageIsDisabled() {
        runner().withPropertyValues("app.storage.enabled=false")
                .run(context -> {
                    BinaryStorage files = context.getBean(BinaryStorage.class);
                    PackingJobArtifactStore artifacts = context.getBean(PackingJobArtifactStore.class);

                    assertThatThrownBy(() -> files.delete(StorageKey.forFile(FileId.generate())))
                                                                                                  .isInstanceOf(ExternalServiceException.class)
                                                                                                  .hasMessageContaining("app.storage.enabled");
                    assertThatThrownBy(() -> artifacts.findResult(PackingJobId.generate()))
                                                                                           .isInstanceOf(ExternalServiceException.class)
                                                                                           .hasMessageContaining("app.storage.enabled");
                });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                                             .withConfiguration(AutoConfigurations.of(BlobStorageConfig.class))
                                             .withUserConfiguration(Adapters.class)
                                             .withBean(ObjectMapper.class, ObjectMapper::new)
                                             .withBean(Clock.class, Clock::systemUTC);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BlobStorageProperties.class)
    @Import({ AzureBlobBinaryStorage.class, AzurePackingJobArtifactStore.class, PackingContractCodec.class })
    static class Adapters {
    }
}
