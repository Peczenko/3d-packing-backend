package com.packing.backend.infra.packing.preview;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.packing.backend.infra.packing.preview.PackerOutput.CUBE_FACES;
import static com.packing.backend.infra.packing.preview.PackerOutput.CUBE_VERTICES;
import static com.packing.backend.infra.packing.preview.PackerOutput.PLACEMENTS;
import static com.packing.backend.infra.packing.preview.PackerOutput.TETRAHEDRON_FACES;
import static com.packing.backend.infra.packing.preview.PackerOutput.TETRAHEDRON_VERTICES;
import static com.packing.backend.infra.packing.preview.PackerOutput.layout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class InstanceExtractorTest {

    @Test
    void storesEachItemOnceWithATransformPerCopyThatReproducesThePackedVertices() throws IOException {
        PackerOutput output = new PackerOutput().place(TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[0])
                                                .place(TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[1])
                                                .place(TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[2])
                                                .place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[1]);

        List<PreviewPart> parts = extract(output.text(), layout("tetra.obj", 3, "cube.obj", 1));

        assertThat(parts).extracting(PreviewPart::name)
                         .containsExactly("tetra.obj", "cube.obj");
        PreviewPart tetra = parts.getFirst();
        assertThat(tetra.index()).isZero();
        assertThat(tetra.vertexCount()).isEqualTo(4);
        assertThat(tetra.indices()).containsExactly(TETRAHEDRON_FACES);
        assertThat(tetra.instances()).hasSize(3);
        for (int copy = 0; copy < 3; copy++) {
            assertReproduces(tetra, copy, TETRAHEDRON_VERTICES, PLACEMENTS[copy]);
        }
        PreviewPart cube = parts.get(1);
        assertThat(cube.index()).isEqualTo(1);
        assertThat(cube.instances()).hasSize(1);
        assertReproduces(cube, 0, CUBE_VERTICES, PLACEMENTS[1]);
    }

    @Test
    void centersTheStoredMeshOnItsOwnCentroid() throws IOException {
        PackerOutput output = new PackerOutput().place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[2]);

        PreviewPart cube = extract(output.text(), layout("cube.obj", 1)).getFirst();

        double[] sum = new double[3];
        for (int i = 0; i < cube.positions().length; i++) {
            sum[i % 3] += cube.positions()[i];
        }
        assertThat(sum[0]).isCloseTo(0, within(1e-4));
        assertThat(sum[1]).isCloseTo(0, within(1e-4));
        assertThat(sum[2]).isCloseTo(0, within(1e-4));
    }

    @Test
    void emitsEachItemAsSoonAsItsLastCopyHasBeenRead() throws IOException {
        List<String> emitted = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        InstanceExtractor extractor = new InstanceExtractor(layout("a.obj", 1, "b.obj", 1), part -> emitted.add(part.name() + "@" + seen.size()));
        PackerObjReader.Sink spy = new PackerObjReader.Sink() {

            @Override
            public void object(String name) {
                seen.add(name);
                extractor.object(name);
            }

            @Override
            public void vertex(double x, double y, double z) {
                extractor.vertex(x, y, z);
            }

            @Override
            public void triangle(int a, int b, int c) {
                extractor.triangle(a, b, c);
            }
        };
        String obj = new PackerOutput().place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[0])
                                       .place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[1])
                                       .text();

        PackerObjReader.read(new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)), spy);
        extractor.finish();

        assertThat(emitted).containsExactly("a.obj@2", "b.obj@2");
    }

    @Test
    void rejectsMoreObjectsThanTheJobPlaced() {
        String obj = new PackerOutput().place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[0])
                                       .place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[1])
                                       .text();

        assertThatThrownBy(() -> extract(obj, layout("cube.obj", 1))).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                     .hasMessageContaining("more objects");
    }

    @Test
    void rejectsFewerObjectsThanTheJobPlaced() {
        String obj = new PackerOutput().place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[0])
                                       .text();

        assertThatThrownBy(() -> extract(obj, layout("cube.obj", 2))).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                     .hasMessageContaining("fewer objects");
        assertThatThrownBy(() -> extract("", layout("cube.obj", 1))).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                    .hasMessageContaining("no objects");
    }

    @Test
    void rejectsACopyWhoseGeometryDiffersFromItsFirstCopy() {
        String obj = new PackerOutput().place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[0])
                                       .place(TETRAHEDRON_VERTICES, TETRAHEDRON_FACES, PLACEMENTS[1])
                                       .text();

        assertThatThrownBy(() -> extract(obj, layout("cube.obj", 2))).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                     .hasMessageContaining("copy 2 of 'cube.obj'");
    }

    @Test
    void rejectsACopyConnectedDifferently() {
        int[] reordered = CUBE_FACES.clone();
        reordered[0] = CUBE_FACES[1];
        reordered[1] = CUBE_FACES[0];
        String obj = new PackerOutput().place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[0])
                                       .place(CUBE_VERTICES, reordered, PLACEMENTS[1])
                                       .text();

        assertThatThrownBy(() -> extract(obj, layout("cube.obj", 2))).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                     .hasMessageContaining("not connected like its first copy");
    }

    @Test
    void rejectsACopyThatIsScaledRatherThanMoved() {
        String obj = new PackerOutput().place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[0])
                                       .place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[1], 1.05)
                                       .text();

        assertThatThrownBy(() -> extract(obj, layout("cube.obj", 2))).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                     .hasMessageContaining("not a rotated and moved copy");
    }

    @Test
    void rejectsAFaceThatReachesIntoAnotherObject() {
        String obj = new PackerOutput().place(CUBE_VERTICES, CUBE_FACES, PLACEMENTS[0])
                                       .raw("o Object_1\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 10 11\n")
                                       .text();

        assertThatThrownBy(() -> extract(obj, layout("cube.obj", 1, "triangle.obj", 1))).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                                        .hasMessageContaining("outside its own object");
    }

    @Test
    void rejectsGeometryBeforeTheFirstObjectAndObjectsWithoutTriangles() {
        assertThatThrownBy(() -> extract("v 0 0 0\n", layout("a.obj", 1))).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                          .hasMessageContaining("before the first object");
        assertThatThrownBy(() -> extract("o a\nv 0 0 0\n", layout("a.obj", 1))).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                               .hasMessageContaining("no triangles");
    }

    private static List<PreviewPart> extract(String obj, List<InstanceExtractor.Item> layout) throws IOException {
        List<PreviewPart> parts = new ArrayList<>();
        InstanceExtractor extractor = new InstanceExtractor(layout, parts::add);
        PackerObjReader.read(new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)), extractor);
        extractor.finish();
        return parts;
    }

    private static void assertReproduces(PreviewPart part, int copy, double[] meshVertices, double[] placement) {
        PreviewPart.Instance instance = part.instances()
                                            .get(copy);
        double norm = Math.sqrt(placement[0] * placement[0] + placement[1] * placement[1] + placement[2] * placement[2] + placement[3] * placement[3]);
        for (int v = 0; v < part.vertexCount(); v++) {
            double[] placed = RigidFit.rotate(instance.qx(),
                                              instance.qy(),
                                              instance.qz(),
                                              instance.qw(),
                                              part.positions()[3 * v],
                                              part.positions()[3 * v + 1],
                                              part.positions()[3 * v + 2]);
            double[] expected = RigidFit.rotate(placement[0] / norm,
                                                placement[1] / norm,
                                                placement[2] / norm,
                                                placement[3] / norm,
                                                meshVertices[3 * v],
                                                meshVertices[3 * v + 1],
                                                meshVertices[3 * v + 2]);
            assertThat(placed[0] + instance.tx()).isCloseTo(expected[0] + placement[4], within(1e-3));
            assertThat(placed[1] + instance.ty()).isCloseTo(expected[1] + placement[5], within(1e-3));
            assertThat(placed[2] + instance.tz()).isCloseTo(expected[2] + placement[6], within(1e-3));
        }
    }
}
