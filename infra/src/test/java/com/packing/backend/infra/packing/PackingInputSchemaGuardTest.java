package com.packing.backend.infra.packing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaId;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.packing.backend.domain.packing.PackingInput;
import com.packing.backend.domain.packing.PackingItem;
import com.packing.backend.domain.packing.PackingJob;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.packing.backend.infra.packing.JsonSchemaPackingInputSchema.CURRENT_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PackingInputSchemaGuardTest {

    private static final Path    CONTRACTS         = Path.of("..", "contracts", "packing-input");
    private static final Pattern VERSION_DIRECTORY = Pattern.compile("v(\\d+)");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theAdapterSpeaksTheNewestDeclaredVersion() throws IOException {
        List<Integer> declared = discoverVersions();

        assertThat(declared.getLast()).isEqualTo(CURRENT_VERSION);
    }

    @Test
    void everyDeclaredSchemaIsValidJsonSchemaAndNamesItsVersion() throws IOException {
        JsonSchema metaSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                                                 .getSchema(SchemaLocation.of(SchemaId.V202012),
                                                            SchemaValidatorsConfig.builder()
                                                                                  .locale(Locale.ENGLISH)
                                                                                  .build());
        for (int version : discoverVersions()) {
            JsonNode schema = schema(version);

            assertThat(metaSchema.validate(schema)).as("v%d against the 2020-12 meta-schema", version)
                                                   .isEmpty();
            assertThat(schema.path("$id")
                             .asText()).isEqualTo("urn:3d-packing:packing-job-input:" + version);
        }
    }

    @Test
    void theCurrentExampleIsAValidInput() throws IOException {
        String example = Files.readString(CONTRACTS.resolve("v" + CURRENT_VERSION)
                                                   .resolve("job-input.example.json"));

        assertThatCode(() -> new JsonSchemaPackingInputSchema(json).validate(example)).doesNotThrowAnyException();
    }

    @Test
    void limitsMatchTheDomainConstants() throws IOException {
        JsonNode properties = schema(CURRENT_VERSION).path("properties");
        JsonNode items = properties.path("items");
        JsonNode copies = items.path("items")
                               .path("properties")
                               .path("copies");
        JsonNode runtime = properties.path("maxRuntimeSeconds");

        assertThat(items.path("minItems")
                        .asInt()).isEqualTo(1);
        assertThat(items.path("maxItems")
                        .asInt()).isEqualTo(PackingInput.MAX_ITEMS);
        assertThat(items.path("x-maxTotalCopies")
                        .asInt()).isEqualTo(PackingInput.MAX_TOTAL_COPIES);
        assertThat(copies.path("minimum")
                         .asInt()).isEqualTo(PackingItem.MIN_COPIES);
        assertThat(copies.path("maximum")
                         .asInt()).isEqualTo(PackingItem.MAX_COPIES);
        assertThat(runtime.path("minimum")
                          .asLong()).isEqualTo(PackingJob.MIN_RUNTIME_SECONDS);
        assertThat(runtime.path("maximum")
                          .asLong()).isEqualTo(PackingJob.MAX_RUNTIME_SECONDS);
    }

    @Test
    void everyEngineParameterDeclaresADefault() throws IOException {
        JsonNode parameters = schema(CURRENT_VERSION).path("properties")
                                                     .path("parameters")
                                                     .path("properties");
        for (Map.Entry<String, JsonNode> parameter : parameters.properties()) {
            assertThat(parameter.getValue()
                                .has("default")).as("parameters.%s declares a default", parameter.getKey())
                                                .isTrue();
        }
    }

    private JsonNode schema(int version) throws IOException {
        return json.readTree(CONTRACTS.resolve("v" + version)
                                      .resolve("job-input.schema.json")
                                      .toFile());
    }

    private static List<Integer> discoverVersions() throws IOException {
        List<Integer> versions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CONTRACTS, Files::isDirectory)) {
            for (Path dir : stream) {
                Matcher matcher = VERSION_DIRECTORY.matcher(dir.getFileName()
                                                               .toString());
                if (matcher.matches()) {
                    versions.add(Integer.parseInt(matcher.group(1)));
                }
            }
        }
        versions.sort(null);
        return versions;
    }
}
