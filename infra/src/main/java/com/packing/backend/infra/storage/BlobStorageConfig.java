package com.packing.backend.infra.storage;

import com.azure.storage.blob.BlobServiceClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class BlobStorageConfig {

    private static final String PREFIX   = "app.storage";
    private static final String ENABLED  = "enabled";
    private static final String SAS_MODE = "sas-mode";

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = ENABLED, havingValue = "true", matchIfMissing = true)
    BlobContainer blobContainer(BlobServiceClient serviceClient,
                                BlobSasIssuer sasIssuer,
                                BlobStorageProperties properties,
                                Clock clock) {
        return new BlobContainer(serviceClient.getBlobContainerClient(properties.containerName()),
                                 sasIssuer,
                                 properties.autoCreateContainer(),
                                 clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = ENABLED, havingValue = "false")
    BlobContainer unavailableBlobContainer() {
        return BlobContainer.unavailable();
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = SAS_MODE, havingValue = "ACCOUNT_KEY", matchIfMissing = true)
    BlobSasIssuer accountKeyBlobSasIssuer() {
        return new AccountKeyBlobSasIssuer();
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = SAS_MODE, havingValue = "USER_DELEGATION")
    BlobSasIssuer userDelegationBlobSasIssuer(BlobServiceClient serviceClient) {
        return new UserDelegationBlobSasIssuer(serviceClient);
    }
}
