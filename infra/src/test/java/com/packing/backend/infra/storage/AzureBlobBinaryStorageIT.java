package com.packing.backend.infra.storage;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.StorageKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Azurite is deliberately not added to {@code TestcontainersConfiguration}, which is shared
 * with {@code :app} and would then start a storage container for every {@code @JooqTest}
 * and context test.
 */
@Testcontainers
class AzureBlobBinaryStorageIT {

    private static final byte[] BYTES = "solid cube".getBytes(StandardCharsets.UTF_8);
    private static final String CONTENT_TYPE = "model/stl";

    /**
     * Azurite refuses any {@code x-ms-version} newer than the table it was built with, and
     * the storage SDK tracks the live service far more closely than the emulator does — so
     * without {@code --skipApiVersionCheck} every call fails with {@code InvalidHeaderValue}
     * the moment the SDK is upgraded past the pinned image. The flag has to be appended in
     * {@code configure()} because the container builds its own command line there and would
     * otherwise overwrite anything set earlier.
     */
    @Container
    private static final AzuriteContainer AZURITE =
            new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.31.0") {
                @Override
                protected void configure() {
                    super.configure();
                    String[] command = getCommandParts();
                    String[] extended = Arrays.copyOf(command, command.length + 1);
                    extended[command.length] = "--skipApiVersionCheck";
                    setCommandParts(extended);
                }
            };

    private BlobServiceClient serviceClient;

    private BinaryStorage storageWith(BlobStorageProperties properties) {
        return new AzureBlobBinaryStorage(serviceClient, new AccountKeyBlobSasIssuer(), properties);
    }

    private static BlobStorageProperties properties(Duration ttl) {
        return new BlobStorageProperties("models", ttl,
                BlobStorageProperties.SasMode.ACCOUNT_KEY, true, true);
    }

    @BeforeEach
    void setUp() {
        serviceClient = new BlobServiceClientBuilder()
                .connectionString(AZURITE.getConnectionString())
                .buildClient();
    }

    private static StorageKey newKey() {
        return StorageKey.forFile(FileId.generate());
    }

    private static HttpResponse<byte[]> fetch(URI url) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(url).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    @Test
    void createsTheContainerOnFirstWrite() {
        StorageKey key = newKey();

        storageWith(properties(Duration.ofMinutes(5)))
                .write(key, new ByteArrayInputStream(BYTES), BYTES.length, CONTENT_TYPE);

        assertThat(serviceClient.getBlobContainerClient("models").exists()).isTrue();
    }

    @Test
    void writesBytesThatCanBeReadBackThroughTheIssuedUrl() throws Exception {
        BinaryStorage storage = storageWith(properties(Duration.ofMinutes(5)));
        StorageKey key = newKey();
        storage.write(key, new ByteArrayInputStream(BYTES), BYTES.length, CONTENT_TYPE);

        BinaryStorage.TemporaryUrl url = storage.temporaryReadUrl(key, "bracket.stl", CONTENT_TYPE);
        HttpResponse<byte[]> response = fetch(url.url());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(BYTES);
    }

    @Test
    void theIssuedUrlCarriesTheCanonicalTypeAndTheOriginalFilename() throws Exception {
        BinaryStorage storage = storageWith(properties(Duration.ofMinutes(5)));
        StorageKey key = newKey();
        storage.write(key, new ByteArrayInputStream(BYTES), BYTES.length, CONTENT_TYPE);

        HttpResponse<byte[]> response =
                fetch(storage.temporaryReadUrl(key, "bracket.stl", CONTENT_TYPE).url());

        assertThat(response.headers().firstValue("Content-Type")).hasValue(CONTENT_TYPE);
        assertThat(response.headers().firstValue("Content-Disposition"))
                .hasValueSatisfying(disposition -> assertThat(disposition)
                        .startsWith("attachment")
                        .contains("bracket.stl"));
    }

    @Test
    void aNonAsciiFilenameSurvivesAsTheEncodedForm() throws Exception {
        BinaryStorage storage = storageWith(properties(Duration.ofMinutes(5)));
        StorageKey key = newKey();
        storage.write(key, new ByteArrayInputStream(BYTES), BYTES.length, CONTENT_TYPE);

        HttpResponse<byte[]> response =
                fetch(storage.temporaryReadUrl(key, "część.stl", CONTENT_TYPE).url());

        assertThat(response.headers().firstValue("Content-Disposition"))
                .hasValueSatisfying(disposition -> assertThat(disposition)
                        .contains("filename*=UTF-8''")
                        .contains("cz%C4%99%C5%9B%C4%87.stl"));
    }

    @Test
    void reportsTheExpiryItActuallySigned() {
        BinaryStorage storage = storageWith(properties(Duration.ofMinutes(5)));
        StorageKey key = newKey();
        storage.write(key, new ByteArrayInputStream(BYTES), BYTES.length, CONTENT_TYPE);

        BinaryStorage.TemporaryUrl url = storage.temporaryReadUrl(key, "bracket.stl", CONTENT_TYPE);

        assertThat(url.expiresAt()).isBetween(
                java.time.Instant.now().plusSeconds(240),
                java.time.Instant.now().plusSeconds(360));
    }

    @Test
    void anExpiredUrlIsRejected() throws Exception {
        BinaryStorage storage = storageWith(properties(Duration.ofMinutes(5)));
        StorageKey key = newKey();
        storage.write(key, new ByteArrayInputStream(BYTES), BYTES.length, CONTENT_TYPE);

        // A negative lifetime signs a window that has already closed.
        BinaryStorage expired = storageWith(properties(Duration.ofMinutes(-10)));
        HttpResponse<byte[]> response =
                fetch(expired.temporaryReadUrl(key, "bracket.stl", CONTENT_TYPE).url());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void overwritingTheSameKeyReplacesTheContent() throws Exception {
        BinaryStorage storage = storageWith(properties(Duration.ofMinutes(5)));
        StorageKey key = newKey();
        byte[] replacement = "solid pyramid".getBytes(StandardCharsets.UTF_8);

        storage.write(key, new ByteArrayInputStream(BYTES), BYTES.length, CONTENT_TYPE);
        storage.write(key, new ByteArrayInputStream(replacement), replacement.length, CONTENT_TYPE);

        HttpResponse<byte[]> response =
                fetch(storage.temporaryReadUrl(key, "bracket.stl", CONTENT_TYPE).url());
        assertThat(response.body()).isEqualTo(replacement);
    }

    @Test
    void deleteRemovesTheBlobAndIsIdempotent() throws Exception {
        BinaryStorage storage = storageWith(properties(Duration.ofMinutes(5)));
        StorageKey key = newKey();
        storage.write(key, new ByteArrayInputStream(BYTES), BYTES.length, CONTENT_TYPE);

        storage.delete(key);
        storage.delete(key);

        HttpResponse<byte[]> response =
                fetch(storage.temporaryReadUrl(key, "bracket.stl", CONTENT_TYPE).url());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void deletingAKeyThatNeverExistedIsNotAnError() {
        BinaryStorage storage = storageWith(properties(Duration.ofMinutes(5)));
        // Touch the container first so the delete is not merely failing on a missing one.
        storage.write(newKey(), new ByteArrayInputStream(BYTES), BYTES.length, CONTENT_TYPE);

        storage.delete(newKey());
    }

    @Test
    void theUnavailableAdapterReportsTheDependencyRatherThanSilentlyDoingNothing() {
        BinaryStorage storage = new UnavailableBinaryStorage();
        StorageKey key = newKey();

        assertThatThrownBy(() -> storage.write(key, new ByteArrayInputStream(BYTES),
                BYTES.length, CONTENT_TYPE))
                .isInstanceOf(ExternalServiceException.class);
        assertThatThrownBy(() -> storage.temporaryReadUrl(key, "bracket.stl", CONTENT_TYPE))
                .isInstanceOf(ExternalServiceException.class);
        assertThatThrownBy(() -> storage.delete(key))
                .isInstanceOf(ExternalServiceException.class);
    }
}
