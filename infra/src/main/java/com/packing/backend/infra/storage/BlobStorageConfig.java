package com.packing.backend.infra.storage;

import com.azure.storage.blob.BlobServiceClient;
import com.packing.backend.core.file.port.out.BinaryStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BlobStorageConfig {

    private static final String PREFIX = "app.storage";
    private static final String ENABLED = "enabled";
    private static final String SAS_MODE = "sas-mode";

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = ENABLED,
            havingValue = "true", matchIfMissing = true)
    public BinaryStorage azureBlobBinaryStorage(BlobServiceClient serviceClient,
                                                BlobSasIssuer sasIssuer,
                                                BlobStorageProperties properties) {
        return new AzureBlobBinaryStorage(serviceClient, sasIssuer, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = ENABLED, havingValue = "false")
    public BinaryStorage unavailableBinaryStorage() {
        return new UnavailableBinaryStorage();
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = SAS_MODE,
            havingValue = "ACCOUNT_KEY", matchIfMissing = true)
    public BlobSasIssuer accountKeyBlobSasIssuer() {
        return new AccountKeyBlobSasIssuer();
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = SAS_MODE, havingValue = "USER_DELEGATION")
    public BlobSasIssuer userDelegationBlobSasIssuer(BlobServiceClient serviceClient) {
        return new UserDelegationBlobSasIssuer(serviceClient);
    }
}
