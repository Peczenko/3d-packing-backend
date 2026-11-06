package com.packing.backend.infra.packing.preview;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.packing.backend.infra.packing.preview.PackerOutput.CUBE_FACES;
import static com.packing.backend.infra.packing.preview.PackerOutput.CUBE_VERTICES;
import static com.packing.backend.infra.packing.preview.PackerOutput.PLACEMENTS;
import static com.packing.backend.infra.packing.preview.PackerOutput.TETRAHEDRON_FACES;
import static com.packing.backend.infra.packing.preview.PackerOutput.TETRAHEDRON_VERTICES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GltfpackPreviewRendererTest {

    private static final PackingJobId      JOB_ID = PackingJobId.generate();
    private static final List<PackingItem> ITEMS  = List.of(PackingInputs.item("tetra.obj", 3), PackingInputs.item("cube.obj", 1));
    private static final String            OBJ    = new PackerOutput().place(TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[0])
                                                                      .place(TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[1])
                                                                      .place(TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[2])
                                                                      .place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[0])
                                                                      .text();

    @TempDir
    Path stored;

    private final Gltfpack gltfpack = mock(Gltfpack.class);

    @Test
    void storesEachQualityThatIsMeaningfullyLighterThanTheOneAboveIt() throws IOException {
        doAnswer(invocation -> {
            pretendToPack(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(gltfpack)
          .pack(any(), any(), any());
        Artifacts artifacts = new Artifacts(OBJ);

        List<PreviewModel> models = new GltfpackPreviewRenderer(artifacts, gltfpack).render(JOB_ID, ITEMS);

        assertThat(models).extracting(PreviewModel::quality, PreviewModel::triangles)
                          .containsExactly(tuple(PreviewQuality.HIGH, 4L * 3 + 12),
                                           tuple(PreviewQuality.LOW, 3L + 1));
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
    void aResultThatDoesNotMatchTheJobStoresNothing() {
        Artifacts artifacts = new Artifacts(new PackerOutput().place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[0])
                                                              .text());

        assertThatThrownBy(() -> new GltfpackPreviewRenderer(artifacts, gltfpack).render(JOB_ID, ITEMS))
                                                                                                        .isInstanceOf(UnrecognizedPackerOutputException.class);
        assertThat(artifacts.previews).isEmpty();
        assertThat(leftoverWorkspaces()).isEmpty();
    }

    @Test
    void aGltfpackFailureStoresNothing() throws IOException {
        doThrow(new IOException("gltfpack exited with 3")).when(gltfpack)
                                                          .pack(any(), any(), any());
        Artifacts artifacts = new Artifacts(OBJ);

        assertThatThrownBy(() -> new GltfpackPreviewRenderer(artifacts, gltfpack).render(JOB_ID, ITEMS)).isInstanceOf(UncheckedIOException.class)
                                                                                                        .hasRootCauseMessage("gltfpack exited with 3");
        assertThat(artifacts.previews).isEmpty();
        assertThat(leftoverWorkspaces()).isEmpty();
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

        private final String                    obj;
        private final Map<PreviewQuality, Path> previews = new EnumMap<>(PreviewQuality.class);

        private Artifacts(String obj) {
            this.obj = obj;
        }

        @Override
        public InputStream openResult(PackingJobId jobId) {
            assertThat(jobId).isEqualTo(JOB_ID);
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
        public TemporaryUrl createPreviewUrl(PackingJobId jobId, PreviewQuality quality, Duration validity, String attachmentFileName) {
            throw new UnsupportedOperationException();
        }
    }
}
