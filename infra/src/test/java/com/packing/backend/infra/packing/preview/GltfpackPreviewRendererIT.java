package com.packing.backend.infra.packing.preview;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.domain.packing.PackingInputs;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PreviewModel;
import com.packing.backend.domain.packing.PreviewQuality;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GltfpackPreviewRendererIT {

    private static final String GLTFPACK = Optional.ofNullable(System.getenv("GLTFPACK_PATH"))
                                                   .orElse("gltfpack");
    private static final int    COPIES   = 6;

    @TempDir
    Path stored;

    @BeforeAll
    static void requireGltfpack() {
        assumeTrue(runs(GLTFPACK), "gltfpack is not installed; set GLTFPACK_PATH to run this test");
    }

    @Test
    void buildsCompressedInstancedPreviewsThatShrinkWithQuality() throws IOException {
        double[] torus = torusVertices(48, 96);
        int[] faces = torusFaces(48, 96);
        PackerOutput output = new PackerOutput();
        for (int copy = 0; copy < COPIES; copy++) {
            double angle = copy * 0.7;
            output.place(torus, faces, new double[] { Math.sin(angle / 2), 0, 0, Math.cos(angle / 2), 3 * copy, copy, -copy });
        }
        output.place(PackerOutput.CUBE_VERTICES, PackerOutput.CUBE_FACES, PackerOutput.PLACEMENTS[2]);
        String obj = output.text();
        Artifacts artifacts = new Artifacts(obj);
        GltfpackPreviewRenderer renderer = new GltfpackPreviewRenderer(artifacts, new Gltfpack(GLTFPACK, Duration.ofMinutes(2)));

        List<PreviewModel> models = renderer.render(PackingJobId.generate(),
                                                    List.of(PackingInputs.item("torus.obj", COPIES), PackingInputs.item("cube.obj", 1)));

        long torusTriangles = faces.length / 3;
        assertThat(models.getFirst()
                         .quality()).isEqualTo(PreviewQuality.HIGH);
        assertThat(models.getFirst()
                         .triangles()).isEqualTo(COPIES * torusTriangles + 12);
        assertThat(models).hasSizeGreaterThan(1);
        for (int i = 1; i < models.size(); i++) {
            assertThat(models.get(i)
                             .triangles()).isLessThan(models.get(i - 1)
                                                            .triangles());
        }
        ObjectNode high = Glb.open(artifacts.previews.get(PreviewQuality.HIGH))
                             .json();
        assertThat(high.path("extensionsUsed")
                       .toString()).contains("EXT_meshopt_compression", "EXT_mesh_gpu_instancing");
        assertThat(models.getFirst()
                         .sizeBytes()).isLessThan(obj.getBytes(StandardCharsets.UTF_8).length / 4);
    }

    private static boolean runs(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "-v").redirectErrorStream(true)
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

    private static double[] torusVertices(int rings, int segments) {
        double[] vertices = new double[3 * rings * segments];
        for (int r = 0; r < rings; r++) {
            double tube = 2 * Math.PI * r / rings;
            for (int s = 0; s < segments; s++) {
                double around = 2 * Math.PI * s / segments;
                int at = 3 * (r * segments + s);
                vertices[at] = (2 + 0.5 * Math.cos(tube)) * Math.cos(around);
                vertices[at + 1] = 0.5 * Math.sin(tube);
                vertices[at + 2] = (2 + 0.5 * Math.cos(tube)) * Math.sin(around);
            }
        }
        return vertices;
    }

    private static int[] torusFaces(int rings, int segments) {
        int[] faces = new int[6 * rings * segments];
        int at = 0;
        for (int r = 0; r < rings; r++) {
            for (int s = 0; s < segments; s++) {
                int a = r * segments + s;
                int b = r * segments + (s + 1) % segments;
                int c = (r + 1) % rings * segments + s;
                int d = (r + 1) % rings * segments + (s + 1) % segments;
                faces[at++] = a;
                faces[at++] = c;
                faces[at++] = b;
                faces[at++] = b;
                faces[at++] = c;
                faces[at++] = d;
            }
        }
        return faces;
    }

    private final class Artifacts implements PackingJobArtifactStore {

        private final String                    obj;
        private final Map<PreviewQuality, Path> previews = new EnumMap<>(PreviewQuality.class);

        private Artifacts(String obj) {
            this.obj = obj;
        }

        @Override
        public InputStream openResult(PackingJobId jobId) {
            return new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void storePreview(PackingJobId jobId, PreviewQuality quality, Path glb) {
            try {
                Path copy = stored.resolve(quality.name() + ".glb");
                Files.copy(glb, copy);
                previews.put(quality, copy);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void writeRequestIfAbsent(PackingJobId jobId, PackingRequestEnvelope envelope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ResultArtifact> findResult(PackingJobId jobId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TemporaryUrl createResultDownloadUrl(PackingJobId jobId, Duration validity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TemporaryUrl createResultBinUrl(PackingJobId jobId, String resultChecksum, int number, int binCount, Duration validity,
                                               String attachmentFileName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TemporaryUrl createPreviewUrl(PackingJobId jobId, PreviewQuality quality, Duration validity, String attachmentFileName) {
            throw new UnsupportedOperationException();
        }
    }
}
