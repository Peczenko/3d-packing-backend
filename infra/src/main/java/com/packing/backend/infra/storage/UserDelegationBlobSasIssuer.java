package com.packing.backend.infra.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The only way to issue a SAS under a managed identity, which holds no storage account key.
 *
 * <p>The identity needs two role assignments at storage-account scope, and missing either
 * produces the same opaque {@code AuthorizationPermissionMismatch} at the first download:
 * <ul>
 *   <li>{@code Storage Blob Delegator} — to call {@code getUserDelegationKey} at all. Easy
 *       to forget, since it is not needed for any other operation.
 *   <li>{@code Storage Blob Data Contributor} — for the reads, writes and deletes.
 * </ul>
 *
 * <p>The key is cached rather than fetched per download, on a lease far shorter than the
 * seven days the service permits: a shorter lease bounds what a leaked key could sign.
 */
class UserDelegationBlobSasIssuer implements BlobSasIssuer {

    private static final Duration KEY_LEASE = Duration.ofHours(1);
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    /**
     * The key's validity starts in the past so that a small clock difference between this
     * host and the storage service does not make a freshly issued signature look
     * not-yet-valid.
     */
    private static final Duration CLOCK_SKEW_ALLOWANCE = Duration.ofMinutes(5);

    private final BlobServiceClient serviceClient;
    private final AtomicReference<UserDelegationKey> cached = new AtomicReference<>();

    UserDelegationBlobSasIssuer(BlobServiceClient serviceClient) {
        this.serviceClient = serviceClient;
    }

    @Override
    public String sasToken(BlobClient blob, BlobServiceSasSignatureValues values) {
        return blob.generateUserDelegationSas(values, currentKey());
    }

    /**
     * Races are harmless: two threads may each fetch a key, and whichever lands second
     * simply replaces an equally valid one. Locking would serialise every download to
     * avoid an occasional redundant call, which is the worse trade.
     */
    private UserDelegationKey currentKey() {
        UserDelegationKey key = cached.get();
        if (key != null && isUsable(key)) {
            return key;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserDelegationKey fetched = serviceClient.getUserDelegationKey(
                now.minus(CLOCK_SKEW_ALLOWANCE), now.plus(KEY_LEASE));
        cached.set(fetched);
        return fetched;
    }

    /** Refreshed ahead of expiry so a signature is never issued against a key about to lapse. */
    private static boolean isUsable(UserDelegationKey key) {
        return key.getSignedExpiry() != null
                && key.getSignedExpiry().isAfter(
                        OffsetDateTime.now(ZoneOffset.UTC).plus(REFRESH_MARGIN));
    }
}
