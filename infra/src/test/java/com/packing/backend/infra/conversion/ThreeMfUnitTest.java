package com.packing.backend.infra.conversion;

import com.packing.backend.core.file.ModelConversionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreeMfUnitTest {

    private static final String RELS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Target="/%s" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
        </Relationships>
        """;

    @TempDir
    Path work;

    @Test
    void readsTheUnitOfTheShippedFixture() throws URISyntaxException {
        Path fixture = Path.of(Objects.requireNonNull(getClass().getResource("/conversion/two-cubes-inch.3mf"))
                                      .toURI());

        assertThat(ThreeMfUnit.millimetresPerUnit(fixture)).isEqualTo(25.4);
    }

    @ParameterizedTest
    @CsvSource({
            "micron, 0.001",
            "millimeter, 1.0",
            "centimeter, 10.0",
            "inch, 25.4",
            "foot, 304.8",
            "meter, 1000.0"
    })
    void convertsEveryUnitTheSpecificationAllows(String unit, double millimetres) throws IOException {
        Path file = package3mf("3D/3dmodel.model", model("unit=\"" + unit + "\""));

        assertThat(ThreeMfUnit.millimetresPerUnit(file)).isEqualTo(millimetres);
    }

    @Test
    void defaultsToMillimetres() throws IOException {
        assertThat(ThreeMfUnit.millimetresPerUnit(package3mf("3D/3dmodel.model", model("")))).isEqualTo(1.0);
    }

    @Test
    void followsTheRelationshipToARootModelWithAnotherName() throws IOException {
        Path file = package3mf("3D/part.model", model("unit=\"centimeter\""));

        assertThat(ThreeMfUnit.millimetresPerUnit(file)).isEqualTo(10.0);
    }

    @Test
    void refusesAnUnknownUnit() throws IOException {
        Path file = package3mf("3D/3dmodel.model", model("unit=\"furlong\""));

        assertThatThrownBy(() -> ThreeMfUnit.millimetresPerUnit(file)).isInstanceOf(ModelConversionException.class)
                                                                      .hasMessageContaining("furlong");
    }

    @Test
    void refusesSomethingThatIsNotAZip() throws IOException {
        Path file = Files.writeString(work.resolve("fake.3mf"), "not a zip");

        assertThatThrownBy(() -> ThreeMfUnit.millimetresPerUnit(file)).isInstanceOf(ModelConversionException.class);
    }

    @Test
    void neverResolvesAnExternalEntity() throws IOException {
        Path secret = Files.writeString(work.resolve("secret.txt"), "meter");
        String hostile = "<?xml version=\"1.0\"?>\n<!DOCTYPE model [<!ENTITY unit SYSTEM \"" + secret.toUri() + "\">]>\n"
                + "<model unit=\"&unit;\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"/>\n";
        Path file = package3mf("3D/3dmodel.model", hostile);

        assertThatThrownBy(() -> ThreeMfUnit.millimetresPerUnit(file)).isInstanceOf(ModelConversionException.class);
    }

    private static String model(String unitAttribute) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<model " + unitAttribute
                + " xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources/><build/></model>\n";
    }

    private Path package3mf(String modelPath, String model) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("_rels/.rels", RELS.formatted(modelPath));
        parts.put(modelPath, model);
        Path file = work.resolve("model-" + System.nanoTime() + ".3mf");
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue()
                              .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }
}
