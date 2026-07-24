package com.packing.backend.infra.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * There is deliberately no integration test for this path: issuing a user delegation SAS
 * needs Azurite started with HTTPS and {@code --oauth basic} against a token it does not
 * genuinely verify, which is a lot of fragile scaffolding for a path that still would not
 * be the real Entra flow.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserDelegationBlobSasIssuerTest {

    @Mock
    private BlobServiceClient serviceClient;
    @Mock
    private BlobClient blob;

    private static BlobServiceSasSignatureValues values() {
        return new BlobServiceSasSignatureValues(
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5),
                new BlobSasPermission().setReadPermission(true));
    }

    private static UserDelegationKey keyExpiringIn(long minutes) {
        return new UserDelegationKey()
                .setSignedExpiry(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(minutes));
    }

    @Test
    void fetchesTheDelegationKeyOnceAndReusesItAcrossSignatures() {
        when(serviceClient.getUserDelegationKey(any(), any())).thenReturn(keyExpiringIn(60));
        when(blob.generateUserDelegationSas(any(), any())).thenReturn("sig=x");
        UserDelegationBlobSasIssuer issuer = new UserDelegationBlobSasIssuer(serviceClient);

        issuer.sasToken(blob, values());
        issuer.sasToken(blob, values());
        issuer.sasToken(blob, values());

        verify(serviceClient, times(1)).getUserDelegationKey(any(), any());
        verify(blob, times(3)).generateUserDelegationSas(any(), any());
    }

    @Test
    void refreshesTheKeyBeforeItExpires() {
        when(serviceClient.getUserDelegationKey(any(), any())).thenReturn(keyExpiringIn(1));
        when(blob.generateUserDelegationSas(any(), any())).thenReturn("sig=x");
        UserDelegationBlobSasIssuer issuer = new UserDelegationBlobSasIssuer(serviceClient);

        issuer.sasToken(blob, values());
        issuer.sasToken(blob, values());

        verify(serviceClient, times(2)).getUserDelegationKey(any(), any());
    }

    @Test
    void requestsTheKeyWithABackdatedStartToAbsorbClockSkew() {
        when(serviceClient.getUserDelegationKey(any(), any())).thenReturn(keyExpiringIn(60));
        when(blob.generateUserDelegationSas(any(), any())).thenReturn("sig=x");
        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> expiry = ArgumentCaptor.forClass(OffsetDateTime.class);

        new UserDelegationBlobSasIssuer(serviceClient).sasToken(blob, values());

        verify(serviceClient).getUserDelegationKey(start.capture(), expiry.capture());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(start.getValue()).isBefore(now);
        assertThat(expiry.getValue()).isAfter(now);
    }
}
