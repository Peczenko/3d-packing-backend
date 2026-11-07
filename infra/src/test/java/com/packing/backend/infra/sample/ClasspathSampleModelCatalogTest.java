package com.packing.backend.infra.sample;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.file.SampleModel;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.infra.packing.JsonSchemaPackingInputSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClasspathSampleModelCatalogTest {

    private static final Set<ModelFormat> OBJ_ONLY = Set.of(ModelFormat.OBJ);

    private static ClasspathSampleModelCatalog catalog(String fixture) {
        return new ClasspathSampleModelCatalog(new ObjectMapper(), OBJ_ONLY, "samples-test/" + fixture);
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream stream = ClasspathSampleModelCatalogTest.class.getClassLoader()
                                                                       .getResourceAsStream(path)) {
            assertThat(stream).as(path)
                              .isNotNull();
            return stream.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                                .digest(bytes));
    }

    @Test
    void listsTheSamplesInManifestOrder() {
        assertThat(catalog("valid").list()).extracting(SampleModel::id)
                                           .containsExactly("wedge", "unit-cube");
    }

    @Test
    void describesASampleFromTheManifestAndItsFile() throws Exception {
        byte[] bytes = resource("samples-test/valid/wedge.obj");

        SampleModel wedge = catalog("valid").find("wedge")
                                            .orElseThrow();

        assertThat(wedge.name()).isEqualTo("Wedge");
        assertThat(wedge.description()).isEqualTo("A tetrahedron.");
        assertThat(wedge.filename()
                        .value()).isEqualTo("wedge.obj");
        assertThat(wedge.format()).isEqualTo(ModelFormat.OBJ);
        assertThat(wedge.sizeBytes()).isEqualTo(bytes.length);
        assertThat(wedge.checksum()
                        .value()).isEqualTo(sha256(bytes));
    }

    @Test
    void opensAFreshStreamOverTheSampleEveryTime() throws Exception {
        byte[] bytes = resource("samples-test/valid/cube.obj");
        SampleModel cube = catalog("valid").find("unit-cube")
                                           .orElseThrow();

        for (int i = 0; i < 2; i++) {
            try (InputStream stream = cube.content()
                                          .open()) {
                assertThat(stream.readAllBytes()).isEqualTo(bytes);
            }
        }
    }

    @Test
    void findsNothingForAnUnknownId() {
        assertThat(catalog("valid").find("teapot")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        duplicate-id      | listed twice
        bad-id            | lowercase letters, digits and single hyphens
        missing-file      | is not on the classpath
        unaccepted-format | not a format the packer accepts
        nested-path       | plain file name
        empty-file        | must hold between 1 and
        unknown-property  | Cannot read the sample model catalogue
        blank-description | must not be blank
        """)
    void refusesToStartWithABrokenCatalogue(String fixture, String reason) {
        assertThatThrownBy(() -> catalog(fixture)).isInstanceOf(IllegalStateException.class)
                                                  .hasMessageContaining(reason);
    }

    @Test
    void theShippedCatalogueIsValidAgainstThePackerContract() {
        ObjectMapper objectMapper = new ObjectMapper();
        ClasspathSampleModelCatalog shipped = new ClasspathSampleModelCatalog(objectMapper,
                                                                              new JsonSchemaPackingInputSchema(objectMapper));

        assertThat(shipped.list()).isNotEmpty();
    }
}
