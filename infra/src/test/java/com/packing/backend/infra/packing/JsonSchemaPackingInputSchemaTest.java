package com.packing.backend.infra.packing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.packing.PackingInputInvalidException;
import com.packing.backend.core.packing.PackingInputSchemaChangedException;
import com.packing.backend.core.packing.port.out.PackingInputSchema.Reconciled;
import com.packing.backend.core.packing.port.out.PackingInputSchema.RequestedItem;
import com.packing.backend.core.packing.port.out.PackingInputSchema.SchemaDocument;
import com.packing.backend.core.packing.port.out.PackingInputSchema.Settings;
import com.packing.backend.core.packing.port.out.PackingInputSchema.ValidatedInput;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.packing.PackingParameters;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class JsonSchemaPackingInputSchemaTest {

    private static final String FILE_ID = "0b6c3c1e-4c4f-4a39-9b5e-2b8c2e6b1f00";
    private static final FileId FILE    = new FileId(UUID.fromString(FILE_ID));

    private final JsonSchemaPackingInputSchema schema = new JsonSchemaPackingInputSchema(new ObjectMapper());
    private final JsonSchemaPackingInputSchema richer = new JsonSchemaPackingInputSchema(new ObjectMapper(),
                                                                                         "packing-input-test/job-input.schema.json");

    @Test
    void appliesTheSchemaDefaultsToAMinimalBody() {
        ValidatedInput input = schema.validate("{\"items\":[{\"fileId\":\"" + FILE_ID + "\"}]}");

        assertThat(input).isEqualTo(new ValidatedInput(1,
                                                       List.of(new RequestedItem(FILE, 1)),
                                                       60,
                                                       new PackingParameters("{}")));
    }

    @Test
    void keepsSubmittedValuesAndIgnoresTheReadOnlyFileName() {
        ValidatedInput input = schema.validate("""
            {"schemaVersion":1,"maxRuntimeSeconds":120.0,"parameters":{},
             "items":[{"fileId":"%s","fileName":"renamed.obj","copies":3}]}""".formatted(FILE_ID));

        assertThat(input.items()).containsExactly(new RequestedItem(FILE, 3));
        assertThat(input.maxRuntimeSeconds()).isEqualTo(120);
    }

    @Test
    void reportsEveryViolationByJsonPointer() {
        assertThatThrownBy(() -> schema.validate("""
            {"bogus":true,"maxRuntimeSeconds":0,"parameters":{"seed":1},
             "items":[{"fileId":"not-a-uuid","copies":101},{}]}"""))
                                                                    .isInstanceOfSatisfying(PackingInputInvalidException.class,
                                                                                            e -> {
                                                                                                assertThat(e.errors()).containsOnlyKeys(
                                                                                                                                        "/bogus",
                                                                                                                                        "/items/0/copies",
                                                                                                                                        "/items/0/fileId",
                                                                                                                                        "/items/1/fileId",
                                                                                                                                        "/maxRuntimeSeconds",
                                                                                                                                        "/parameters/seed");
                                                                                                assertThat(e.errors()).contains(
                                                                                                                                entry("/bogus", "is not allowed"),
                                                                                                                                entry("/items/0/copies",
                                                                                                                                      "must have a maximum value of 100"),
                                                                                                                                entry("/items/1/fileId", "is required"),
                                                                                                                                entry("/maxRuntimeSeconds",
                                                                                                                                      "must have a minimum value of 1"),
                                                                                                                                entry("/parameters/seed", "is not allowed"));
                                                                                            });
    }

    @Test
    void requiresAtLeastOneItem() {
        assertThatThrownBy(() -> schema.validate("{}"))
                                                       .isInstanceOfSatisfying(PackingInputInvalidException.class,
                                                                               e -> assertThat(e.errors()).containsExactly(entry("/items", "is required")));
        assertThatThrownBy(() -> schema.validate("{\"items\":[]}"))
                                                                   .isInstanceOfSatisfying(PackingInputInvalidException.class,
                                                                                           e -> assertThat(e.errors()).containsOnlyKeys("/items"));
    }

    @Test
    void rejectsABodyThatIsNotAnObject() {
        assertThatThrownBy(() -> schema.validate("[]"))
                                                       .isInstanceOfSatisfying(PackingInputInvalidException.class,
                                                                               e -> assertThat(e.errors()).containsOnlyKeys(""));
    }

    @Test
    void rejectsSomethingThatIsNotJson() {
        assertThatThrownBy(() -> schema.validate("{"))
                                                      .isInstanceOfSatisfying(PackingInputInvalidException.class,
                                                                              e -> assertThat(e.errors()).containsExactly(entry("", "must be a JSON document")));
    }

    @Test
    void reportsAStaleSchemaVersionBeforeAnyOtherProblem() {
        assertThatThrownBy(() -> schema.validate("{\"schemaVersion\":2}"))
                                                                          .isInstanceOf(PackingInputSchemaChangedException.class)
                                                                          .hasMessage("Input schema changed; reload the form");
    }

    @Test
    void treatsANonIntegerSchemaVersionAsAValidationError() {
        assertThatThrownBy(() -> schema.validate("{\"schemaVersion\":\"1\",\"items\":[{\"fileId\":\"" + FILE_ID + "\"}]}"))
                                                                                                                           .isInstanceOfSatisfying(PackingInputInvalidException.class,
                                                                                                                                                   e -> assertThat(e.errors()).containsOnlyKeys("/schemaVersion"));
    }

    @Test
    void acceptsOnlyTheFormatsTheSchemaDeclares() {
        assertThat(schema.acceptedFormats()).isEqualTo(Set.of(ModelFormat.OBJ));
    }

    @Test
    void servesTheSchemaFileVerbatimWithAStableEtag() throws IOException {
        SchemaDocument document = schema.document();

        assertThat(document.version()).isEqualTo(1);
        assertThat(document.json()).isEqualTo(Files.readString(Path.of("..", "contracts", "packing-input", "v1", "job-input.schema.json")));
        assertThat(document.etag()).matches("[0-9a-f]{64}")
                                   .isEqualTo(new JsonSchemaPackingInputSchema(new ObjectMapper()).document()
                                                                                                  .etag());
    }

    @Test
    void defaultsComeFromTheCurrentSchema() {
        assertThat(schema.defaults()).isEqualTo(new Settings(60, new PackingParameters("{}")));
    }

    @Test
    void defaultsReachIntoNestedParameters() throws Exception {
        Settings defaults = richer.defaults();

        assertThat(defaults.maxRuntimeSeconds()).isEqualTo(30);
        JSONAssert.assertEquals("{\"seed\":7,\"buildVolume\":{\"widthMm\":220}}",
                                defaults.parameters()
                                        .json(),
                                JSONCompareMode.STRICT);
    }

    @Test
    void validateFillsNestedParameterDefaults() throws Exception {
        ValidatedInput input = richer.validate("{\"items\":[{\"fileId\":\"" + FILE_ID + "\"}],\"parameters\":{\"buildVolume\":{\"widthMm\":300}}}");

        JSONAssert.assertEquals("{\"seed\":7,\"buildVolume\":{\"widthMm\":300}}",
                                input.parameters()
                                     .json(),
                                JSONCompareMode.STRICT);
    }

    @Test
    void reconcileKeepsEveryStillValidSetting() throws Exception {
        Reconciled reconciled = richer.reconcile(new Settings(120, new PackingParameters("{\"seed\":3}")));

        assertThat(reconciled.resetFields()).isEmpty();
        assertThat(reconciled.settings()
                             .maxRuntimeSeconds()).isEqualTo(120);
        JSONAssert.assertEquals("{\"seed\":3,\"buildVolume\":{\"widthMm\":220}}",
                                reconciled.settings()
                                          .parameters()
                                          .json(),
                                JSONCompareMode.STRICT);
    }

    @Test
    void reconcileResetsAnOutOfRangeRuntime() {
        assertThat(schema.reconcile(new Settings(9_000, new PackingParameters("{}"))))
                                                                                      .isEqualTo(new Reconciled(new Settings(60, new PackingParameters("{}")),
                                                                                                                List.of("/maxRuntimeSeconds")));
    }

    @Test
    void reconcileDropsParametersTheSchemaNoLongerHas() {
        assertThat(schema.reconcile(new Settings(60, new PackingParameters("{\"seed\":7}"))))
                                                                                             .isEqualTo(new Reconciled(new Settings(60, new PackingParameters("{}")),
                                                                                                                       List.of("/parameters/seed")));
    }

    @Test
    void reconcileResetsAnInvalidParameterAsAWhole() throws Exception {
        Reconciled reconciled = richer.reconcile(new Settings(30,
                                                              new PackingParameters("{\"seed\":-1,\"buildVolume\":{\"widthMm\":0}}")));

        assertThat(reconciled.resetFields()).containsExactly("/parameters/buildVolume", "/parameters/seed");
        JSONAssert.assertEquals("{\"seed\":7,\"buildVolume\":{\"widthMm\":220}}",
                                reconciled.settings()
                                          .parameters()
                                          .json(),
                                JSONCompareMode.STRICT);
    }
}
