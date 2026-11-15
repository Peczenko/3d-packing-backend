package com.packing.backend.infra.packing.preview;

import com.packing.backend.infra.shared.mesh.ObjReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinMembershipTest {

    private static final List<InstanceExtractor.Item> LAYOUT = PackerOutput.layout("tetra.obj", 3, "cube.obj", 2);

    @Test
    void countsEachItemsCopiesPerContainerAndOmitsItemsAContainerDoesNotHold() {
        BinMembership membership = observe(3, "bin_1_mesh_1", "bin_2_mesh_2", "bin_1_mesh_3", "bin_3_mesh_4", "bin_3_mesh_5");

        assertThat(membership.layout(1)).containsExactly(new InstanceExtractor.Item("tetra.obj", 2));
        assertThat(membership.layout(2)).containsExactly(new InstanceExtractor.Item("tetra.obj", 1));
        assertThat(membership.layout(3)).containsExactly(new InstanceExtractor.Item("cube.obj", 2));
    }

    @Test
    void passesEveryCallThroughToTheObservedSink() {
        List<String> seen = new ArrayList<>();
        ObjReader.Sink sink = new BinMembership(LAYOUT, 2).observing(new ObjReader.Sink() {

            @Override
            public void object(String name) {
                seen.add("o " + name);
            }

            @Override
            public void vertex(double x, double y, double z) {
                seen.add("v " + x);
            }

            @Override
            public void triangle(int a, int b, int c) {
                seen.add("f " + a);
            }
        });

        sink.object("bin_2_mesh_1");
        sink.vertex(1.5, 0, 0);
        sink.triangle(4, 5, 6);

        assertThat(seen).containsExactly("o bin_2_mesh_1", "v 1.5", "f 4");
    }

    @Test
    void rejectsAnObjectThatDoesNotNameItsContainer() {
        assertThatThrownBy(() -> observe(2, "Object_0")).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                        .hasMessageContaining("does not name its container");
        assertThatThrownBy(() -> observe(2, "bin_1_mesh_99999999999")).isInstanceOf(UnrecognizedPackerOutputException.class);
    }

    @Test
    void rejectsObjectsOutOfInputOrder() {
        assertThatThrownBy(() -> observe(2, "bin_1_mesh_2")).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                            .hasMessageContaining("input order");
        assertThatThrownBy(() -> observe(2, "bin_1_mesh_1", "bin_1_mesh_1")).isInstanceOf(UnrecognizedPackerOutputException.class);
    }

    @Test
    void rejectsAContainerTheJobDidNotFill() {
        assertThatThrownBy(() -> observe(2, "bin_3_mesh_1")).isInstanceOf(UnrecognizedPackerOutputException.class);
        assertThatThrownBy(() -> observe(2, "bin_0_mesh_1")).isInstanceOf(UnrecognizedPackerOutputException.class);
    }

    @Test
    void rejectsMoreObjectsThanTheJobPlaced() {
        assertThatThrownBy(() -> observe(2, "bin_1_mesh_1", "bin_1_mesh_2", "bin_1_mesh_3", "bin_2_mesh_4", "bin_2_mesh_5", "bin_2_mesh_6"))
                                                                                                                                            .isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                                                                                            .hasMessageContaining("more objects");
    }

    @Test
    void rejectsAContainerThatHoldsNothing() {
        BinMembership membership = observe(3, "bin_1_mesh_1", "bin_1_mesh_2", "bin_1_mesh_3", "bin_3_mesh_4", "bin_3_mesh_5");

        assertThatThrownBy(() -> membership.layout(2)).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                      .hasMessageContaining("container 2 holds no objects");
    }

    private static BinMembership observe(int binCount, String... names) {
        BinMembership membership = new BinMembership(LAYOUT, binCount);
        ObjReader.Sink sink = membership.observing(new ObjReader.Sink() {

            @Override
            public void object(String name) {
            }

            @Override
            public void vertex(double x, double y, double z) {
            }

            @Override
            public void triangle(int a, int b, int c) {
            }
        });
        for (String name : names) {
            sink.object(name);
        }
        return membership;
    }
}
