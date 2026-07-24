package com.packing.backend.infra.storage;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.file.StorageKey;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

public class AzureBlobBinaryStorage implements BinaryStorage {

    private static final String SERVICE = "azure-blob-storage";

    /**
     * Signatures start slightly in the past so a small clock difference between this host
     * and the storage service cannot make a freshly issued URL look not-yet-valid.
     */
    private static final int CLOCK_SKEW_ALLOWANCE_MINUTES = 5;

    private final BlobServiceClient serviceClient;
    private final BlobSasIssuer sasIssuer;
    private final BlobStorageProperties properties;
    private final AtomicBoolean containerEnsured = new AtomicBoolean();

    public AzureBlobBinaryStorage(BlobServiceClient serviceClient,
                                  BlobSasIssuer sasIssuer,
                                  BlobStorageProperties properties) {
        this.serviceClient = serviceClient;
        this.sasIssuer = sasIssuer;
        this.properties = properties;
    }

    @Override
    public void write(StorageKey key, InputStream content, long contentLength, String contentType) {
        BlobContainerClient container = ensureContainer();
        try {
            container.getBlobClient(key.value()).uploadWithResponse(
                    new BlobParallelUploadOptions(content)
                            .setHeaders(new BlobHttpHeaders().setContentType(contentType)),
                    null,
                    Context.NONE);
        } catch (BlobStorageException | UncheckedIOException e) {
            throw new ExternalServiceException(SERVICE,
                    "Could not write blob " + key + " to container "
                            + properties.containerName(), e);
        }
    }

    /**
     * The response headers are served by the storage account itself, outside this
     * application's control — this method must not be handed an unsanitised
     * {@code downloadFilename}, or a CRLF in it would inject headers there.
     */
    @Override
    public TemporaryUrl temporaryReadUrl(StorageKey key, String downloadFilename, String contentType) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiry = now.plus(properties.downloadUrlTtl());

        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(
                expiry, new BlobSasPermission().setReadPermission(true))
                .setStartTime(now.minusMinutes(CLOCK_SKEW_ALLOWANCE_MINUTES))
                .setContentType(contentType)
                .setContentDisposition(contentDisposition(downloadFilename));

        try {
            BlobClient blob = container().getBlobClient(key.value());
            return new TemporaryUrl(
                    URI.create(blob.getBlobUrl() + "?" + sasIssuer.sasToken(blob, values)),
                    expiry.toInstant());
        } catch (BlobStorageException e) {
            throw new ExternalServiceException(SERVICE,
                    "Could not issue a download URL for blob " + key, e);
        }
    }

    @Override
    public void delete(StorageKey key) {
        try {
            container().getBlobClient(key.value()).deleteIfExists();
        } catch (BlobStorageException e) {
            throw new ExternalServiceException(SERVICE, "Could not delete blob " + key, e);
        }
    }

    /**
     * RFC 6266 / RFC 5987: an ASCII fallback for old clients plus a percent-encoded UTF-8
     * form for everything current, so a name with non-ASCII characters survives.
     */
    private static String contentDisposition(String filename) {
        String asciiFallback = filename.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded;
    }

    private BlobContainerClient container() {
        return serviceClient.getBlobContainerClient(properties.containerName());
    }

    /**
     * Created lazily on first write, not at startup: the container app scales to zero, so a
     * cold replica must not fail to start because a dependency was momentarily unreachable.
     */
    private BlobContainerClient ensureContainer() {
        BlobContainerClient container = container();
        if (!properties.autoCreateContainer() || containerEnsured.get()) {
            return container;
        }
        try {
            container.createIfNotExists();
            containerEnsured.set(true);
            return container;
        } catch (BlobStorageException e) {
            throw new ExternalServiceException(SERVICE,
                    "Could not create container " + properties.containerName(), e);
        }
    }
}
