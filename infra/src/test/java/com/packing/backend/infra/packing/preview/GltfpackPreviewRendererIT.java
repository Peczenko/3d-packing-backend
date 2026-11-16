package com.packing.backend.infra.packing.preview;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.port.out.PackingArtifact;
import com.packing.backend.core.packing.port.out.PackingArtifact.PreviewArtifact;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.shared.TemporaryUrl;
import com.packing.backend.core.packing.port.out.PackingPreviewRenderer;
import com.packing.backend.domain.packing.PackingInputs;
import com.packing.backend.domain.packing.PackingItem;
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
                                                    List.of(PackingInputs.item("torus.obj", COPIES), PackingInputs.item("cube.obj", 1)),
                                                    "c".repeat(64),
                                                    1)
                                            .models();

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

    @Test
    void buildsTheSameInstancedPreviewFromPlacementsWithOneGltfpackPassPerInput() throws IOException {
        double[] torus = torusVertices(48, 96);
        int[] faces = torusFaces(48, 96);
        StringBuilder placements = new StringBuilder("{\"version\":1,\"binOffsets\":[[0,0,0],[50,0,0]],\"placements\":[");
        for (int copy = 0; copy < COPIES; copy++) {
            double angle = copy * 0.7;
            placements.append("{\"item\":0,\"bin\":")
                      .append(1 + copy % 2)
                      .append(",\"matrix\":[1,0,0,")
                      .append(3 * copy)
                      .append(",0,")
                      .append(Math.cos(angle))
                      .append(',')
                      .append(-Math.sin(angle))
                      .append(',')
                      .append(copy)
                      .append(",0,")
                      .append(Math.sin(angle))
                      .append(',')
                      .append(Math.cos(angle))
                      .append(',')
                      .append(-copy)
                      .append("]},");
        }
        placements.append("{\"item\":1,\"bin\":2,\"matrix\":[1,0,0,5,0,1,0,6,0,0,1,7]}]}");
        Artifacts artifacts = new Artifacts(null);
        artifacts.placements = placements.toString();
        artifacts.inputs = List.of(new PackerOutput().place(torus, faces, PackerOutput.PLACEMENTS[0])
                                                     .text(),
                                   new PackerOutput().place(PackerOutput.CUBE_VERTICES, PackerOutput.CUBE_FACES, PackerOutput.PLACEMENTS[0])
                                                     .text());
        List<PackingItem> items = List.of(PackingInputs.item("torus.obj", COPIES), PackingInputs.item("cube.obj", 1));
        artifacts.items = items;
        GltfpackPreviewRenderer renderer = new GltfpackPreviewRenderer(artifacts, new Gltfpack(GLTFPACK, Duration.ofMinutes(2)));

        PackingPreviewRenderer.Rendered rendered = renderer.render(PackingJobId.generate(), items, "c".repeat(64), 2);

        long torusTriangles = faces.length / 3;
        List<PreviewModel> models = rendered.models();
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
        assertThat(rendered.bins()).hasSize(2);
        assertThat(rendered.bins()
                           .get(0)
                           .getFirst()
                           .triangles()).isEqualTo(COPIES / 2 * torusTriangles);
        assertThat(rendered.bins()
                           .get(1)
                           .getFirst()
                           .triangles()).isEqualTo(COPIES / 2 * torusTriangles + 12);
        for (PreviewQuality quality : artifacts.previews.keySet()) {
            ObjectNode json = Glb.open(artifacts.previews.get(quality))
                                 .json();
            assertThat(json.path("extensionsUsed")
                           .toString()).contains("EXT_meshopt_compression", "EXT_mesh_gpu_instancing");
            assertThat(json.path("nodes")).hasSize(2);
            assertThat(json.at("/nodes/0/name")
                           .asText()).isEqualTo("torus.obj");
            assertThat(json.at("/accessors/" + json.at("/nodes/0/extensions/EXT_mesh_gpu_instancing/attributes/TRANSLATION")
                                                   .asInt()
                    + "/count")
                           .asInt()).isEqualTo(COPIES);
        }
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
        private String                          placements;
        private List<String>                    inputs   = List.of();
        private List<PackingItem>               items    = List.of();

        private Artifacts(String obj) {
            this.obj = obj;
        }

        @Override
        public InputStream open(PackingArtifact artifact) {
            if (!(artifact instanceof PackingArtifact.Result)) {
                throw new UnsupportedOperationException();
            }
            return new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Optional<byte[]> findPlacements(PackingJobId jobId, String resultChecksum) {
            return Optional.ofNullable(placements)
                           .map(json -> json.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream openInput(PackingItem item) {
            return new ByteArrayInputStream(inputs.get(items.indexOf(item))
                                                  .getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void storePreview(PreviewArtifact preview, Path glb) {
            if (!(preview instanceof PackingArtifact.Preview whole)) {
                return;
            }
            try {
                Path copy = stored.resolve(whole.quality()
                                                .name()
                        + ".glb");
                Files.copy(glb, copy);
                previews.put(whole.quality(), copy);
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
        public TemporaryUrl readUrl(PackingArtifact artifact, Duration validity, boolean asAttachment) {
            throw new UnsupportedOperationException();
        }
    }
}
