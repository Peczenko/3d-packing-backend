package com.packing.backend.infra.storage;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.options.BlobUploadFromFileOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.blob.specialized.BlobInputStream;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.core.shared.TemporaryUrl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

class BlobContainer {

    static final String SERVICE = "azure-blob-storage";

    private static final int DECODE_BUFFER_BYTES = 1 << 16;

    private static final String UNAVAILABLE = "Object storage is not configured. Set app.storage.enabled=true and provide "
            + "spring.cloud.azure.storage.blob.connection-string (or .endpoint).";

    private final BlobContainerClient client;
    private final BlobSasIssuer       sasIssuer;
    private final boolean             autoCreate;
    private final Clock               clock;
    private final AtomicBoolean       created = new AtomicBoolean();

    BlobContainer(BlobContainerClient client, BlobSasIssuer sasIssuer, boolean autoCreate, Clock clock) {
        this.client = client;
        this.sasIssuer = sasIssuer;
        this.autoCreate = autoCreate;
        this.clock = clock;
    }

    static BlobContainer unavailable() {
        return new BlobContainer(null, null, false, Clock.systemUTC());
    }

    void upload(String key, InputStream content, BlobHttpHeaders headers) {
        BlobClient blob = writable().getBlobClient(key);
        translating("write",
                    key,
                    () -> blob.uploadWithResponse(new BlobParallelUploadOptions(content).setHeaders(headers),
                                                  null,
                                                  Context.NONE));
    }

    void upload(String key, Path file, BlobHttpHeaders headers) {
        BlobClient blob = writable().getBlobClient(key);
        translating("write",
                    key,
                    () -> blob.uploadFromFileWithResponse(new BlobUploadFromFileOptions(file.toString()).setHeaders(headers),
                                                          null,
                                                          Context.NONE));
    }

    boolean uploadIfAbsent(String key, byte[] content, BlobHttpHeaders headers) {
        BlobClient blob = writable().getBlobClient(key);
        BlobParallelUploadOptions options = new BlobParallelUploadOptions(new ByteArrayInputStream(content))
                                                                                                            .setHeaders(headers)
                                                                                                            .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*"));
        try {
            blob.uploadWithResponse(options, null, Context.NONE);
            return true;
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 409 || e.getStatusCode() == 412) {
                return false;
            }
            throw failure("write", key, e);
        }
    }

    byte[] download(String key) {
        BlobClient blob = client().getBlobClient(key);
        return translating("read",
                           key,
                           () -> blob.downloadContent()
                                     .toBytes());
    }

    InputStream open(String key) {
        BlobClient blob = client().getBlobClient(key);
        return translating("open", key, () -> decoded(blob.openInputStream()));
    }

    Optional<BlobProperties> findProperties(String key) {
        BlobClient blob = client().getBlobClient(key);
        try {
            return Optional.of(blob.getProperties());
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw failure("read the properties of", key, e);
        }
    }

    void deleteIfExists(String key) {
        BlobClient blob = client().getBlobClient(key);
        translating("delete", key, blob::deleteIfExists);
    }

    TemporaryUrl readUrl(String key, ReadUrl request) {
        SasWindow window = request.windowAt(clock.instant());
        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(window.expiry()
                                                                                       .atOffset(ZoneOffset.UTC),
                                                                                 new BlobSasPermission().setReadPermission(true))
                                                                                                                                 .setStartTime(window.start()
                                                                                                                                                     .atOffset(ZoneOffset.UTC));
        if (request.attachmentName() != null) {
            values.setContentDisposition(AttachmentDisposition.of(request.attachmentName()));
        }
        if (request.contentType() != null) {
            values.setContentType(request.contentType());
        }
        BlobClient blob = client().getBlobClient(key);
        return translating("sign a read URL for",
                           key,
                           () -> new TemporaryUrl(URI.create(blob.getBlobUrl() + "?" + sasIssuer.sasToken(blob, values)), window.expiry()));
    }

    private static InputStream decoded(BlobInputStream stream) {
        if (!"gzip".equalsIgnoreCase(stream.getProperties()
                                           .getContentEncoding())) {
            return stream;
        }
        try {
            return new GZIPInputStream(stream, DECODE_BUFFER_BYTES);
        } catch (IOException e) {
            stream.close();
            throw new UncheckedIOException(e);
        }
    }

    private BlobContainerClient client() {
        if (client == null) {
            throw new ExternalServiceException(SERVICE, UNAVAILABLE);
        }
        return client;
    }

    private BlobContainerClient writable() {
        BlobContainerClient container = client();
        if (autoCreate && !created.get()) {
            try {
                container.createIfNotExists();
            } catch (BlobStorageException e) {
                throw new ExternalServiceException(SERVICE, "Could not create container " + container.getBlobContainerName(), e);
            }
            created.set(true);
        }
        return container;
    }

    private <T> T translating(String operation, String key, Supplier<T> action) {
        try {
            return action.get();
        } catch (BlobStorageException | UncheckedIOException e) {
            throw failure(operation, key, e);
        }
    }

    private ExternalServiceException failure(String operation, String key, RuntimeException cause) {
        return new ExternalServiceException(SERVICE,
                                            "Could not " + operation + " blob " + key + " in container " + client.getBlobContainerName(),
                                            cause);
    }
}
