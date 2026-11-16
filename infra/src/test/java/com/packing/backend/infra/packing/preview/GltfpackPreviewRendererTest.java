package com.packing.backend.infra.packing.preview;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static com.packing.backend.infra.packing.preview.PackerOutput.CUBE_FACES;
import static com.packing.backend.infra.packing.preview.PackerOutput.CUBE_VERTICES;
import static com.packing.backend.infra.packing.preview.PackerOutput.PLACEMENTS;
import static com.packing.backend.infra.packing.preview.PackerOutput.TETRAHEDRON_FACES;
import static com.packing.backend.infra.packing.preview.PackerOutput.TETRAHEDRON_VERTICES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GltfpackPreviewRendererTest {

    private static final PackingJobId      JOB_ID           = PackingJobId.generate();
    private static final String            CHECKSUM         = "c".repeat(64);
    private static final double[]          SECOND_CONTAINER = { 0, 0, 0, 1, 1_000, 0, 0 };
    private static final List<PackingItem> ITEMS            = List.of(PackingInputs.item("tetra.obj", 3), PackingInputs.item("cube.obj", 1));
    private static final String            OBJ              = new PackerOutput().place(TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[0])
                                                                                .place(TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[1])
                                                                                .place(TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[2])
                                                                                .place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[0])
                                                                                .text();

    private static final double[][]          MATRICES = {
            { 1, 0, 0, 10, 0, 1, 0, 20, 0, 0, 1, 30 },
            { 0, -1, 0, 5, 1, 0, 0, 0, 0, 0, 1, 2.5 },
            { 0, 0, 1, -7, 1, 0, 0, 8, 0, 1, 0, 9 },
            { -1, 0, 0, 100, 0, -1, 0, 50, 0, 0, 1, 0 } };
    private static final String              PLACED   = "{\"version\":1,\"binOffsets\":[[0,0,0],[1000,0,0]],\"placements\":["
            + "{\"item\":0,\"bin\":1,\"matrix\":" + Arrays.toString(MATRICES[0]) + "},"
            + "{\"item\":0,\"bin\":2,\"matrix\":" + Arrays.toString(MATRICES[1]) + "},"
            + "{\"item\":0,\"bin\":1,\"matrix\":" + Arrays.toString(MATRICES[2]) + "},"
            + "{\"item\":1,\"bin\":2,\"matrix\":" + Arrays.toString(MATRICES[3]) + "}]}";
    private static final double[]            IDENTITY = { 0, 0, 0, 1, 0, 0, 0 };
    private static final Map<String, String> INPUTS   = Map.of("tetra.obj",
                                                               new PackerOutput().place(TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, IDENTITY)
                                                                                 .text(),
                                                               "cube.obj",
                                                               new PackerOutput().place(CUBE_VERTICES, CUBE_FACES, IDENTITY)
                                                                                 .text());

    @TempDir
    Path stored;

    private final Gltfpack gltfpack = mock(Gltfpack.class);

    @Test
    void storesEachQualityThatIsMeaningfullyLighterThanTheOneAboveIt() throws IOException {
        pretendToPackEverything();
        Artifacts artifacts = new Artifacts(OBJ);

        PackingPreviewRenderer.Rendered rendered = new GltfpackPreviewRenderer(artifacts, gltfpack).render(JOB_ID, ITEMS, CHECKSUM, 1);
        List<PreviewModel> models = rendered.models();

        assertThat(models).extracting(PreviewModel::quality, PreviewModel::triangles)
                          .containsExactly(tuple(PreviewQuality.HIGH, 4L * 3 + 12),
                                           tuple(PreviewQuality.LOW, 3L + 1));
        assertThat(rendered.bins()).isEmpty();
        assertThat(artifacts.openedBins).isEmpty();
        assertThat(artifacts.previews).containsOnlyKeys(PreviewQuality.HIGH, PreviewQuality.LOW);
        assertThat(models.getFirst()
                         .sizeBytes()).isEqualTo(Files.size(artifacts.previews.get(PreviewQuality.HIGH)));
        ObjectNode high = Glb.open(artifacts.previews.get(PreviewQuality.HIGH))
                             .json();
        assertThat(high.at("/meshes/0/name")
                       .asText()).isEqualTo("tetra.obj");
        assertThat(high.at("/meshes/1/name")
                       .asText()).isEqualTo("cube.obj");
        verify(gltfpack, times(2 * PreviewQuality.values().length)).pack(any(), any(), any());
        assertThat(leftoverWorkspaces()).isEmpty();
    }

    @Test
    void aResultInSeveralContainersGetsOnePreviewLadderPerContainerBuiltFromThatContainersOwnFile() throws IOException {
        pretendToPackEverything();
        String combined = new PackerOutput().place("bin_1_mesh_1", TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[0])
                                            .place("bin_2_mesh_2", TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, SECOND_CONTAINER)
                                            .place("bin_1_mesh_3", TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[2])
                                            .place("bin_2_mesh_4", CUBE_VERTICES, CUBE_FACES, SECOND_CONTAINER)
                                            .text();
        String first = new PackerOutput().place("bin_1_mesh_1", TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[0])
                                         .place("bin_1_mesh_3", TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[2])
                                         .text();
        String second = new PackerOutput().place("bin_2_mesh_2", TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[0])
                                          .place("bin_2_mesh_4", CUBE_VERTICES, CUBE_FACES, PLACEMENTS[0])
                                          .text();
        Artifacts artifacts = new Artifacts(combined, first, second);

        PackingPreviewRenderer.Rendered rendered = new GltfpackPreviewRenderer(artifacts, gltfpack).render(JOB_ID, ITEMS, CHECKSUM, 2);

        assertThat(rendered.models()).extracting(PreviewModel::quality, PreviewModel::triangles)
                                     .containsExactly(tuple(PreviewQuality.HIGH, 4L * 3 + 12),
                                                      tuple(PreviewQuality.LOW, 3L + 1));
        assertThat(rendered.bins()).hasSize(2);
        assertThat(rendered.bins()
                           .get(0)).extracting(PreviewModel::quality, PreviewModel::triangles)
                                   .containsExactly(tuple(PreviewQuality.HIGH, 4L * 2),
                                                    tuple(PreviewQuality.LOW, 2L));
        assertThat(rendered.bins()
                           .get(1)).extracting(PreviewModel::quality, PreviewModel::triangles)
                                   .containsExactly(tuple(PreviewQuality.HIGH, 4L + 12),
                                                    tuple(PreviewQuality.LOW, 1L + 1));
        assertThat(artifacts.openedBins).containsExactly(1, 2);
        assertThat(artifacts.binPreviews).containsOnlyKeys(1, 2);
        assertThat(artifacts.binPreviews.get(2)).containsOnlyKeys(PreviewQuality.HIGH, PreviewQuality.LOW);
        assertThat(rendered.bins()
                           .get(1)
                           .getFirst()
                           .sizeBytes()).isEqualTo(Files.size(artifacts.binPreviews.get(2)
                                                                                   .get(PreviewQuality.HIGH)));
        ObjectNode secondHigh = Glb.open(artifacts.binPreviews.get(2)
                                                              .get(PreviewQuality.HIGH))
                                   .json();
        assertThat(secondHigh.at("/meshes/0/name")
                             .asText()).isEqualTo("tetra.obj");
        assertThat(secondHigh.at("/meshes/1/name")
                             .asText()).isEqualTo("cube.obj");
        verify(gltfpack, times((2 + 1 + 2) * PreviewQuality.values().length)).pack(any(), any(), any());
        assertThat(leftoverWorkspaces()).isEmpty();
    }

    @Test
    void placementsBuildEveryPreviewFromTheInputsWithoutReadingTheResult() throws IOException {
        pretendToPackEverything();
        Artifacts artifacts = new Artifacts(null);
        artifacts.inputs = INPUTS;
        artifacts.placements = PLACED;

        PackingPreviewRenderer.Rendered rendered = new GltfpackPreviewRenderer(artifacts, gltfpack).render(JOB_ID, ITEMS, CHECKSUM, 2);

        assertThat(rendered.models()).extracting(PreviewModel::quality, PreviewModel::triangles)
                                     .containsExactly(tuple(PreviewQuality.HIGH, 4L * 3 + 12),
                                                      tuple(PreviewQuality.LOW, 3L + 1));
        assertThat(rendered.bins()).hasSize(2);
        assertThat(rendered.bins()
                           .get(0)).extracting(PreviewModel::quality, PreviewModel::triangles)
                                   .containsExactly(tuple(PreviewQuality.HIGH, 4L * 2),
                                                    tuple(PreviewQuality.LOW, 2L));
        assertThat(rendered.bins()
                           .get(1)).extracting(PreviewModel::quality, PreviewModel::triangles)
                                   .containsExactly(tuple(PreviewQuality.HIGH, 4L + 12),
                                                    tuple(PreviewQuality.LOW, 1L + 1));
        assertThat(artifacts.openedInputs).containsExactlyElementsOf(ITEMS);
        assertThat(artifacts.openedBins).isEmpty();
        verify(gltfpack, times(2 * PreviewQuality.values().length)).pack(any(), any(), any());
        assertThat(leftoverWorkspaces()).isEmpty();

        double[][] tetra = { MATRICES[0], MATRICES[1], MATRICES[2] };
        assertThat(placedVertices(artifacts.previews.get(PreviewQuality.HIGH), 0)).containsExactly(expected(TETRAHEDRON_VERTICES,
                                                                                                             tetra,
                                                                                                             new double[] { 0, 1_000, 0 }),
                                                                                                    within(1e-3));
        assertThat(placedVertices(artifacts.previews.get(PreviewQuality.HIGH), 1)).containsExactly(expected(CUBE_VERTICES,
                                                                                                             new double[][] { MATRICES[3] },
                                                                                                             new double[] { 1_000 }),
                                                                                                    within(1e-3));
        Path secondHigh = artifacts.binPreviews.get(2)
                                               .get(PreviewQuality.HIGH);
        assertThat(placedVertices(secondHigh, 0)).containsExactly(expected(TETRAHEDRON_VERTICES, new double[][] { MATRICES[1] }, new double[] { 0 }),
                                                                  within(1e-3));
        assertThat(placedVertices(secondHigh, 1)).containsExactly(expected(CUBE_VERTICES, new double[][] { MATRICES[3] }, new double[] { 0 }),
                                                                  within(1e-3));
        assertThat(Glb.open(secondHigh)
                      .json()
                      .at("/nodes/0/name")
                      .asText()).isEqualTo("tetra.obj");
    }

    @Test
    void placementsThatDoNotMatchTheJobFallBackToTheResult() throws IOException {
        pretendToPackEverything();
        Artifacts artifacts = new Artifacts(OBJ);
        artifacts.placements = "{\"version\":1,\"binOffsets\":[[0,0,0]],\"placements\":[]}";

        PackingPreviewRenderer.Rendered rendered = new GltfpackPreviewRenderer(artifacts, gltfpack).render(JOB_ID, ITEMS, CHECKSUM, 1);

        assertThat(rendered.models()).extracting(PreviewModel::quality, PreviewModel::triangles)
                                     .containsExactly(tuple(PreviewQuality.HIGH, 4L * 3 + 12),
                                                      tuple(PreviewQuality.LOW, 3L + 1));
        assertThat(artifacts.openedInputs).isEmpty();
    }

    @Test
    void aMalformedInputStoresNothing() {
        Artifacts artifacts = new Artifacts(null);
        artifacts.inputs = Map.of("tetra.obj", "v 0 0 0\nf 1 2 3\n", "cube.obj", INPUTS.get("cube.obj"));
        artifacts.placements = PLACED;

        assertThatThrownBy(() -> new GltfpackPreviewRenderer(artifacts, gltfpack).render(JOB_ID, ITEMS, CHECKSUM, 2))
                                                                                                                     .isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                                                                     .hasMessageContaining("'tetra.obj' is malformed");
        assertThat(artifacts.previews).isEmpty();
        assertThat(leftoverWorkspaces()).isEmpty();
    }

    private static double[] expected(double[] vertices, double[][] matrices, double[] offsets) {
        double[] expected = new double[matrices.length * vertices.length];
        int at = 0;
        for (int copy = 0; copy < matrices.length; copy++) {
            double[] m = matrices[copy];
            for (int v = 0; v < vertices.length; v += 3) {
                for (int row = 0; row < 3; row++) {
                    expected[at++] = m[4 * row] * vertices[v] + m[4 * row + 1] * vertices[v + 1] + m[4 * row + 2] * vertices[v + 2]
                            + m[4 * row + 3] + (row == 0 ? offsets[copy] : 0);
                }
            }
        }
        return expected;
    }

    private static double[] placedVertices(Path glb, int node) throws IOException {
        Glb.Container container = Glb.open(glb);
        ObjectNode json = container.json();
        ByteBuffer bin;
        try (FileChannel channel = FileChannel.open(glb)) {
            bin = ByteBuffer.allocate((int) container.binLength())
                            .order(ByteOrder.LITTLE_ENDIAN);
            channel.read(bin, container.binOffset());
            bin.flip();
        }
        JsonNode attributes = json.at("/nodes/" + node + "/extensions/EXT_mesh_gpu_instancing/attributes");
        float[] translations = floats(json, bin, attributes.path("TRANSLATION")
                                                           .asInt());
        float[] rotations = floats(json, bin, attributes.path("ROTATION")
                                                        .asInt());
        float[] positions = floats(json,
                                   bin,
                                   json.at("/meshes/" + json.at("/nodes/" + node + "/mesh")
                                                            .asInt()
                                           + "/primitives/0/attributes/POSITION")
                                       .asInt());
        double[] placed = new double[translations.length / 3 * positions.length];
        int at = 0;
        for (int i = 0; i < translations.length / 3; i++) {
            for (int v = 0; v < positions.length; v += 3) {
                double[] rotated = RigidFit.rotate(rotations[4 * i],
                                                   rotations[4 * i + 1],
                                                   rotations[4 * i + 2],
                                                   rotations[4 * i + 3],
                                                   positions[v],
                                                   positions[v + 1],
                                                   positions[v + 2]);
                placed[at++] = rotated[0] + translations[3 * i];
                placed[at++] = rotated[1] + translations[3 * i + 1];
                placed[at++] = rotated[2] + translations[3 * i + 2];
            }
        }
        return placed;
    }

    private static float[] floats(ObjectNode json, ByteBuffer bin, int accessor) {
        JsonNode described = json.at("/accessors/" + accessor);
        JsonNode view = json.at("/bufferViews/" + described.path("bufferView")
                                                           .asInt());
        int width = switch (described.path("type")
                                     .asText()) {
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            default -> throw new IllegalStateException();
        };
        float[] values = new float[width * described.path("count")
                                                    .asInt()];
        bin.asFloatBuffer()
           .get(view.path("byteOffset")
                    .asInt()
                   / 4, values);
        return values;
    }

    @Test
    void aMultiContainerResultWhoseObjectsDoNotNameTheirContainerStoresNothing() {
        Artifacts artifacts = new Artifacts(OBJ, OBJ, OBJ);

        assertThatThrownBy(() -> new GltfpackPreviewRenderer(artifacts, gltfpack).render(JOB_ID, ITEMS, CHECKSUM, 2))
                                                                                                                     .isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                                                                     .hasMessageContaining("does not name its container");
        assertThat(artifacts.previews).isEmpty();
        assertThat(artifacts.binPreviews).isEmpty();
        assertThat(leftoverWorkspaces()).isEmpty();
    }

    @Test
    void aResultThatDoesNotMatchTheJobStoresNothing() {
        Artifacts artifacts = new Artifacts(new PackerOutput().place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[0])
                                                              .text());

        assertThatThrownBy(() -> new GltfpackPreviewRenderer(artifacts, gltfpack).render(JOB_ID, ITEMS, CHECKSUM, 1))
                                                                                                                     .isInstanceOf(UnrecognizedPackerOutputException.class);
        assertThat(artifacts.previews).isEmpty();
        assertThat(leftoverWorkspaces()).isEmpty();
    }

    @Test
    void aGltfpackFailureStoresNothing() throws IOException {
        doThrow(new IOException("gltfpack exited with 3")).when(gltfpack)
                                                          .pack(any(), any(), any());
        Artifacts artifacts = new Artifacts(OBJ);

        assertThatThrownBy(() -> new GltfpackPreviewRenderer(artifacts, gltfpack).render(JOB_ID, ITEMS, CHECKSUM, 1))
                                                                                                                     .isInstanceOf(UncheckedIOException.class)
                                                                                                                     .hasRootCauseMessage("gltfpack exited with 3");
        assertThat(artifacts.previews).isEmpty();
        assertThat(leftoverWorkspaces()).isEmpty();
    }

    private void pretendToPackEverything() throws IOException {
        doAnswer(invocation -> {
            pretendToPack(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(gltfpack)
          .pack(any(), any(), any());
    }

    private static void pretendToPack(Path input, Path output, PreviewQuality quality) throws IOException {
        Glb.Container container = Glb.open(input);
        ObjectNode json = container.json()
                                   .deepCopy();
        if (quality == PreviewQuality.LOW) {
            ((ObjectNode) json.at("/accessors/1")).put("count", 3);
        }
        Glb.write(output, json, container.binLength(), container::copyBinTo);
    }

    private static List<Path> leftoverWorkspaces() {
        try (Stream<Path> entries = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return entries.filter(path -> path.getFileName()
                                              .toString()
                                              .startsWith("packing-preview-" + JOB_ID))
                          .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final class Artifacts implements PackingJobArtifactStore {

        private final String                                  obj;
        private final List<String>                            bins;
        private final Map<PreviewQuality, Path>               previews    = new EnumMap<>(PreviewQuality.class);
        private final Map<Integer, Map<PreviewQuality, Path>> binPreviews = new TreeMap<>();
        private final List<Integer>                           openedBins  = new ArrayList<>();
        private final List<PackingItem>                       openedInputs = new ArrayList<>();
        private String                                        placements;
        private Map<String, String>                           inputs      = Map.of();

        private Artifacts(String obj, String... bins) {
            this.obj = obj;
            this.bins = List.of(bins);
        }

        @Override
        public InputStream open(PackingArtifact artifact) {
            assertThat(artifact.jobId()).isEqualTo(JOB_ID);
            return switch (artifact) {
                case PackingArtifact.Result ignored -> new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8));
                case PackingArtifact.BinResult bin -> {
                    assertThat(bin.resultChecksum()).isEqualTo(CHECKSUM);
                    assertThat(bin.binCount()).isEqualTo(bins.size());
                    openedBins.add(bin.number());
                    yield new ByteArrayInputStream(bins.get(bin.number() - 1)
                                                       .getBytes(StandardCharsets.UTF_8));
                }
                default -> throw new UnsupportedOperationException();
            };
        }

        @Override
        public Optional<byte[]> findPlacements(PackingJobId jobId, String resultChecksum) {
            assertThat(jobId).isEqualTo(JOB_ID);
            assertThat(resultChecksum).isEqualTo(CHECKSUM);
            return Optional.ofNullable(placements)
                           .map(json -> json.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream openInput(PackingItem item) {
            openedInputs.add(item);
            return new ByteArrayInputStream(inputs.get(item.fileName()
                                                           .value())
                                                  .getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void storePreview(PreviewArtifact preview, Path glb) {
            switch (preview) {
                case PackingArtifact.Preview whole -> previews.put(whole.quality(),
                                                                   copy(glb,
                                                                        whole.quality()
                                                                             .name()));
                case PackingArtifact.BinPreview bin -> binPreviews.computeIfAbsent(bin.number(), ignored -> new EnumMap<>(PreviewQuality.class))
                                                                  .put(bin.quality(),
                                                                       copy(glb,
                                                                            "bin-" + bin.number() + "-" + bin.quality()
                                                                                                             .name()));
            }
        }

        private Path copy(Path glb, String name) {
            try {
                Path copy = stored.resolve(name + ".glb");
                Files.copy(glb, copy);
                return copy;
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
