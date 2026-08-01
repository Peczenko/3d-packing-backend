package com.packing.backend.infra.storage;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.infra.packing.PackingContractCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AzurePackingJobArtifactStoreIT {

    private static final String CONTAINER = "packing";

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

    private BlobContainerClient container;
    private PackingJobArtifactStore artifacts;

    @BeforeEach
    void setUp() {
        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                .connectionString(AZURITE.getConnectionString())
                .buildClient();
        container = serviceClient.getBlobContainerClient(CONTAINER);
        container.createIfNotExists();
        artifacts = new AzurePackingJobArtifactStore(container, new AccountKeyBlobSasIssuer(),
                new PackingContractCodec(new ObjectMapper()));
    }

    @Test
    void writesTheCodecEnvelopeAtTheDeterministicRequestKeyWithoutChangingNestedSpec() {
        PackingJobId jobId = jobId();
        PackingRequestEnvelope envelope = PackingRequestEnvelope.versionOne(60,
                "{\"nested\":{\"flag\":true,\"values\":[1,{\"name\":\"opaque\"}]}}");

        artifacts.writeRequestIfAbsent(jobId, envelope);

        byte[] request = container.getBlobClient(requestKey(jobId)).downloadContent().toBytes();
        assertThat(new String(request, StandardCharsets.UTF_8)).isEqualTo(
                "{\"requestVersion\":1,\"maxRuntimeSeconds\":60,\"spec\":{\"nested\":{\"flag\":true,\"values\":[1,{\"name\":\"opaque\"}]}}}");
    }

    @Test
    void acceptsAnIdenticalRequestRetryWithoutReplacingTheExistingBlob() {
        PackingJobId jobId = jobId();
        PackingRequestEnvelope envelope = PackingRequestEnvelope.versionOne(60, "{\"nested\":{\"value\":1}}");

        artifacts.writeRequestIfAbsent(jobId, envelope);
        String originalEtag = container.getBlobClient(requestKey(jobId)).getProperties().getETag();
        artifacts.writeRequestIfAbsent(jobId, envelope);

        assertThat(container.getBlobClient(requestKey(jobId)).getProperties().getETag())
                .isEqualTo(originalEtag);
    }

    @Test
    void rejectsARequestRetryWhoseCodecBytesDifferFromTheStoredRequest() {
        PackingJobId jobId = jobId();
        artifacts.writeRequestIfAbsent(jobId, PackingRequestEnvelope.versionOne(60, "{\"value\":1}"));

        assertThatThrownBy(() -> artifacts.writeRequestIfAbsent(jobId,
                PackingRequestEnvelope.versionOne(61, "{\"value\":1}")))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void returnsEmptyWhenTheDeterministicResultBlobDoesNotExist() {
        assertThat(artifacts.findResult(jobId())).isEmpty();
    }

    @Test
    void readsResultMetadataAndLengthAndSignsTheDeterministicResultKey() {
        PackingJobId jobId = jobId();
        byte[] output = "packed result".getBytes(StandardCharsets.UTF_8);
        container.getBlobClient(resultKey(jobId)).uploadWithResponse(
                new BlobParallelUploadOptions(new ByteArrayInputStream(output))
                        .setHeaders(new BlobHttpHeaders().setContentType("application/octet-stream"))
                        .setMetadata(Map.of(
                                "fileName", "output.bin",
                                "contentType", "application/x-packing-result",
                                "checksumSha256", "a".repeat(64),
                                "engineVersion", "packer 0.3.0",
                                "engineChecksumSha256", "b".repeat(64))),
                null, null);

        assertThat(artifacts.findResult(jobId)).contains(new PackingJobArtifactStore.ResultArtifact(
                "output.bin", "application/x-packing-result", output.length, "a".repeat(64),
                "packer 0.3.0", "b".repeat(64)));

        PackingJobArtifactStore.TemporaryUrl url =
                artifacts.createResultDownloadUrl(jobId, Duration.ofMinutes(10));
        assertThat(url.url().getPath()).endsWith("/" + resultKey(jobId));
        assertThat(url.expiresAt()).isBetween(
                java.time.Instant.now().plusSeconds(540), java.time.Instant.now().plusSeconds(660));
    }

    private static PackingJobId jobId() {
        return new PackingJobId(UUID.randomUUID());
    }

    private static String requestKey(PackingJobId jobId) {
        return "packing-jobs/" + jobId + "/request.json";
    }

    private static String resultKey(PackingJobId jobId) {
        return "packing-jobs/" + jobId + "/result/output";
    }
}
