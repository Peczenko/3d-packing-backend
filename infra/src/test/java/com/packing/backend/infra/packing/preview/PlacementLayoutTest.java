package com.packing.backend.infra.packing.preview;

import com.packing.backend.domain.packing.PackingInputs;
import com.packing.backend.domain.packing.PackingItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.groups.Tuple.tuple;

class PlacementLayoutTest {

    private static final Path              EXAMPLE = Path.of("..", "contracts", "packing", "v4", "result-placements.example.json");
    private static final List<PackingItem> ITEMS   = List.of(PackingInputs.item("tetra.obj", 2), PackingInputs.item("cube.obj", 1));

    @Test
    void readsTheWorkersExample() throws IOException {
        PlacementLayout layout = PlacementLayout.parse(Files.readAllBytes(EXAMPLE), ITEMS, 2);

        assertThat(layout.binOffsets()).containsExactly(new double[] { 0, 0, 0 }, new double[] { 1650, 0, 0 });
        assertThat(layout.placements()).extracting(PlacementLayout.Placement::item, PlacementLayout.Placement::bin)
                                       .containsExactly(tuple(0, 1),
                                                        tuple(0, 2),
                                                        tuple(1, 1));
    }

    @Test
    void placesACopyByItsMatrixAroundTheCentroidAndAddsTheContainerOffsetOnlyToTheWholeResult() throws IOException {
        PlacementLayout layout = PlacementLayout.parse(Files.readAllBytes(EXAMPLE), ITEMS, 2);
        double[] centroid = { 1, 2, 3 };

        List<PreviewPart.Instance> whole = layout.instances(0, 0, centroid);
        List<PreviewPart.Instance> second = layout.instances(0, 2, centroid);

        assertThat(whole).hasSize(2);
        assertThat(second).hasSize(1);
        PreviewPart.Instance turned = second.getFirst();
        assertThat(new double[] { turned.tx(), turned.ty(), turned.tz() }).containsExactly(new double[] { 3, 1, 5.5 }, within(1e-12));
        assertThat(whole.get(1)
                        .tx()).isEqualTo(1653);
        assertThat(RigidFit.rotate(turned.qx(), turned.qy(), turned.qz(), turned.qw(), 1, 0, 0)).containsExactly(new double[] { 0, 1, 0 },
                                                                                                                 within(1e-12));
    }

    @Test
    void turnsEveryKindOfRotationIntoTheQuaternionThatRotatesTheSameWay() {
        double[][] quaternions = {
                { 0, 0, 0, 1 }, { 1, 0, 0, 0 }, { 0, 1, 0, 0 }, { 0, 0, 1, 0 },
                { 0.3, -0.5, 0.7, 0.4 }, { 0.9, 0.1, -0.1, 0.05 }, { -0.1, 0.9, 0.2, -0.05 }, { 0.1, 0.2, 0.95, 0.01 } };
        for (double[] q : quaternions) {
            double norm = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
            double[] m = new double[12];
            for (int column = 0; column < 3; column++) {
                double[] axis = RigidFit.rotate(q[0] / norm,
                                                q[1] / norm,
                                                q[2] / norm,
                                                q[3] / norm,
                                                column == 0 ? 1 : 0,
                                                column == 1 ? 1 : 0,
                                                column == 2 ? 1 : 0);
                for (int row = 0; row < 3; row++) {
                    m[4 * row + column] = axis[row];
                }
            }

            double[] back = PlacementLayout.quaternion(m);

            assertThat(RigidFit.rotate(back[0], back[1], back[2], back[3], 1, 2, 3)).containsExactly(RigidFit.rotate(q[0] / norm,
                                                                                                                     q[1] / norm,
                                                                                                                     q[2] / norm,
                                                                                                                     q[3] / norm,
                                                                                                                     1,
                                                                                                                     2,
                                                                                                                     3),
                                                                                                     within(1e-9));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{\"version\":2,\"binOffsets\":[[0,0,0]],\"placements\":[]}",
            "{\"version\":1,\"binOffsets\":[[0,0,0],[5,0,0]],\"placements\":[{\"item\":0,\"bin\":1,\"matrix\":[1,0,0,0,0,1,0,0,0,0,1,0]}]}",
            "{\"version\":1,\"binOffsets\":[[0,0,0]],\"placements\":[]}",
            "{\"version\":1,\"binOffsets\":[[0,0,0]],\"placements\":[{\"item\":1,\"bin\":1,\"matrix\":[1,0,0,0,0,1,0,0,0,0,1,0]}]}",
            "{\"version\":1,\"binOffsets\":[[0,0,0]],\"placements\":[{\"item\":0,\"bin\":2,\"matrix\":[1,0,0,0,0,1,0,0,0,0,1,0]}]}",
            "{\"version\":1,\"binOffsets\":[[0,0,0]],\"placements\":[{\"item\":0,\"bin\":1,\"matrix\":[1,0,0,0]}]}",
            "{\"version\":1,\"binOffsets\":[[0,0,0]],\"placements\":[{\"item\":0,\"bin\":1,\"matrix\":[2,0,0,0,0,1,0,0,0,0,1,0]}]}",
            "{\"version\":1,\"binOffsets\":[[0,0,0]],\"placements\":[{\"item\":0,\"bin\":1,\"matrix\":[-1,0,0,0,0,1,0,0,0,0,1,0]}]}" })
    void refusesPlacementsThatDoNotDescribeTheJob(String json) {
        assertThatThrownBy(() -> PlacementLayout.parse(json.getBytes(StandardCharsets.UTF_8), List.of(PackingInputs.item("cube.obj", 1)), 1))
                                                                                                                                             .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAContainerNothingWasPlacedIn() {
        String json = "{\"version\":1,\"binOffsets\":[[0,0,0],[5,0,0]],\"placements\":[{\"item\":0,\"bin\":1,\"matrix\":[1,0,0,0,0,1,0,0,0,0,1,0]}]}";

        assertThatThrownBy(() -> PlacementLayout.parse(json.getBytes(StandardCharsets.UTF_8), List.of(PackingInputs.item("cube.obj", 1)), 2))
                                                                                                                                             .isInstanceOf(IllegalArgumentException.class)
                                                                                                                                             .hasMessageContaining("container 2 holds no objects");
    }
}
