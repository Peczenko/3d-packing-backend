package com.packing.backend.infra.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

class UserDelegationBlobSasIssuer implements BlobSasIssuer {

    private static final Duration KEY_LEASE            = Duration.ofHours(1);
    private static final Duration REFRESH_MARGIN       = Duration.ofMinutes(5);
    private static final Duration CLOCK_SKEW_ALLOWANCE = Duration.ofMinutes(5);

    private final BlobServiceClient                  serviceClient;
    private final AtomicReference<UserDelegationKey> cached = new AtomicReference<>();

    UserDelegationBlobSasIssuer(BlobServiceClient serviceClient) {
        this.serviceClient = serviceClient;
    }

    @Override
    public String sasToken(BlobClient blob, BlobServiceSasSignatureValues values) {
        return blob.generateUserDelegationSas(values, currentKey());
    }

    private UserDelegationKey currentKey() {
        UserDelegationKey key = cached.get();
        if (key != null && isUsable(key)) {
            return key;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserDelegationKey fetched = serviceClient.getUserDelegationKey(
                                                                       now.minus(CLOCK_SKEW_ALLOWANCE),
                                                                       now.plus(KEY_LEASE));
        cached.set(fetched);
        return fetched;
    }

    private static boolean isUsable(UserDelegationKey key) {
        return key.getSignedExpiry() != null
                && key.getSignedExpiry()
                      .isAfter(
                               OffsetDateTime.now(ZoneOffset.UTC)
                                             .plus(REFRESH_MARGIN));
    }
}
