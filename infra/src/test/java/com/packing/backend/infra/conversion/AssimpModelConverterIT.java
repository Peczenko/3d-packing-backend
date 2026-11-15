package com.packing.backend.infra.conversion;

import com.packing.backend.core.file.ModelConversionException;
import com.packing.backend.core.file.port.out.ModelConverter.ConvertedModel;
import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.infra.shared.mesh.ObjReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AssimpModelConverterIT {

    private static final String ASSIMP = Optional.ofNullable(System.getenv("ASSIMP_PATH"))
                                                 .orElse("assimp");

    private final AssimpModelConverter converter = new AssimpModelConverter(new Assimp(ASSIMP, Duration.ofMinutes(1)), 1);

    @BeforeAll
    static void requireAssimp() {
        assumeTrue(runs(ASSIMP), "assimp is not installed; set ASSIMP_PATH to run this test");
    }

    @Test
    void weldsAnAsciiStlCube() throws IOException {
        Mesh mesh = convert("cube-ascii.stl", ModelFormat.STL);

        assertThat(mesh.vertices()).hasSize(8);
        assertThat(mesh.triangles()).isEqualTo(12);
        assertThat(mesh.extent()).containsExactly(10, 10, 10);
    }

    @Test
    void dropsTheDegenerateTrianglesOfABinaryStl() throws IOException {
        Mesh mesh = convert("cube-degenerate.stl", ModelFormat.STL);

        assertThat(mesh.triangles()).isEqualTo(12);
    }

    @Test
    void triangulatesTheQuadsOfABinaryPly() throws IOException {
        Mesh mesh = convert("cube.ply", ModelFormat.PLY);

        assertThat(mesh.triangles()).isEqualTo(12);
        assertThat(mesh.text()).doesNotContain("vn", "vt", "mtllib");
    }

    @Test
    void scalesGltfMetresToMillimetresAndAppliesTheNodeTransform() throws IOException {
        Mesh mesh = convert("cube.gltf", ModelFormat.GLTF);

        assertThat(mesh.triangles()).isEqualTo(12);
        assertThat(mesh.extent()).containsExactly(50, 50, 50);
        assertThat(mesh.minimum()[0]).isEqualTo(1000.0);
    }

    @Test
    void readsABinaryGlb() throws IOException {
        Mesh mesh = convert("cube.glb", ModelFormat.GLB);

        assertThat(mesh.extent()).containsExactly(50, 50, 50);
    }

    @Test
    void placesEveryBuildItemOfA3mfInItsDeclaredUnit() throws IOException {
        Mesh mesh = convert("two-cubes-inch.3mf", ModelFormat.THREE_MF);

        assertThat(mesh.triangles()).isEqualTo(24);
        assertThat(mesh.extent()[0]).isEqualTo(3 * 25.4, offset(1e-4));
        assertThat(mesh.extent()[1]).isEqualTo(25.4, offset(1e-4));
    }

    @Test
    void refusesAFileWithNoGeometry() {
        assertThatThrownBy(() -> convert("not-a-model.stl", ModelFormat.STL)).isInstanceOf(ModelConversionException.class)
                                                                             .hasMessageContaining("no triangles");
    }

    @Test
    void refusesAFileTheImporterCannotReadWithItsReasonButNotItsPath() {
        ContentSource garbage = () -> new ByteArrayInputStream("garbage".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> converter.toObj(garbage, ModelFormat.GLB)).isInstanceOf(ModelConversionException.class)
                                                                           .hasMessageStartingWith("The GLB file could not be read: ")
                                                                           .hasMessageNotContaining("source.glb")
                                                                           .hasMessageNotContaining(System.getProperty("java.io.tmpdir"));
    }

    @Test
    void reportsAMissingConverterAsAnExternalFailure() {
        AssimpModelConverter missing = new AssimpModelConverter(new Assimp("assimp-that-does-not-exist", Duration.ofSeconds(5)), 1);

        assertThatThrownBy(() -> missing.toObj(fixture("cube-ascii.stl"), ModelFormat.STL)).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void removesItsWorkspaceOnceTheConvertedModelIsClosed() throws IOException {
        Path workspace;
        try (ConvertedModel converted = converter.toObj(fixture("cube-ascii.stl"), ModelFormat.STL)) {
            workspace = ((AssimpModelConverter.TemporaryObj) converted).work();
            assertThat(Files.list(workspace)).extracting(path -> path.getFileName()
                                                                     .toString())
                                             .containsExactly("model.obj");
        }
        assertThat(workspace).doesNotExist();
    }

    private Mesh convert(String name, ModelFormat format) throws IOException {
        try (ConvertedModel converted = converter.toObj(fixture(name), format); InputStream in = converted.open()) {
            byte[] bytes = in.readAllBytes();
            Mesh mesh = new Mesh(new String(bytes, StandardCharsets.US_ASCII));
            ObjReader.read(new ByteArrayInputStream(bytes), mesh);
            return mesh;
        }
    }

    private static ContentSource fixture(String name) {
        return () -> Objects.requireNonNull(AssimpModelConverterIT.class.getResourceAsStream("/conversion/" + name), name);
    }

    private static boolean runs(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "version").redirectErrorStream(true)
                                                                       .start();
            process.getInputStream()
                   .readAllBytes();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            return false;
        }
    }

    private static final class Mesh implements ObjReader.Sink {

        private final String         text;
        private final List<double[]> vertices = new ArrayList<>();
        private int                  triangles;

        private Mesh(String text) {
            this.text = text;
        }

        @Override
        public void object(String name) {
        }

        @Override
        public void vertex(double x, double y, double z) {
            vertices.add(new double[] { x, y, z });
        }

        @Override
        public void triangle(int a, int b, int c) {
            triangles++;
        }

        String text() {
            return text;
        }

        List<double[]> vertices() {
            return vertices;
        }

        int triangles() {
            return triangles;
        }

        double[] minimum() {
            double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
            for (double[] v : vertices) {
                for (int i = 0; i < 3; i++) {
                    min[i] = Math.min(min[i], v[i]);
                }
            }
            return min;
        }

        double[] extent() {
            double[] min = minimum();
            double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
            for (double[] v : vertices) {
                for (int i = 0; i < 3; i++) {
                    max[i] = Math.max(max[i], v[i]);
                }
            }
            return new double[] { max[0] - min[0], max[1] - min[1], max[2] - min[2] };
        }
    }
}
