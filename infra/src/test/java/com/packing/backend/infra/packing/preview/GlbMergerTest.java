package com.packing.backend.infra.packing.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlbMergerTest {

    private static final float[] TRIANGLE_POSITIONS = { 0, 0, 0, 1, 0, 0, 0, 1, 0 };
    private static final int[]   TRIANGLE_INDICES   = { 0, 1, 2 };
    private static final float[] QUAD_POSITIONS     = { 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0 };
    private static final int[]   QUAD_INDICES       = { 0, 1, 2, 0, 2, 3 };

    @TempDir
    Path work;

    @Test
    void writesAPartAsAGlbThatGltfpackCanRead() throws IOException {
        PreviewPart part = new PreviewPart(2,
                                           "triangle.obj",
                                           TRIANGLE_POSITIONS,
                                           TRIANGLE_INDICES,
                                           List.of(
                                                   new PreviewPart.Instance(0, 0, 0, 1, 10, 20, 30),
                                                   new PreviewPart.Instance(0, 0, 1, 0, -1, -2, -3)));
        Path path = work.resolve("part.glb");

        PreviewPartWriter.write(part, path);

        Glb.Container container = Glb.open(path);
        ObjectNode json = container.json();
        assertThat(json.at("/asset/version")
                       .asText()).isEqualTo("2.0");
        assertThat(json.at("/scenes/0/nodes")
                       .size()).isEqualTo(2);
        assertThat(json.at("/nodes/1/rotation")
                       .toString()).isEqualTo("[0.0,0.0,1.0,0.0]");
        assertThat(json.at("/nodes/0/translation")
                       .toString()).isEqualTo("[10.0,20.0,30.0]");
        assertThat(json.at("/accessors/0/count")
                       .asInt()).isEqualTo(3);
        assertThat(json.at("/accessors/0/max")
                       .toString()).isEqualTo("[1.0,1.0,0.0]");
        assertThat(json.at("/accessors/1/count")
                       .asInt()).isEqualTo(3);
        assertThat(json.at("/buffers/0/byteLength")
                       .asLong()).isEqualTo(48);
        assertThat(container.binLength()).isEqualTo(48);
        ByteBuffer bin = bin(path, container);
        assertThat(bin.getFloat(12)).isEqualTo(1f);
        assertThat(bin.getInt(36 + 8)).isEqualTo(2);
        assertThat(GlbMerger.triangles(json)).isEqualTo(2);
    }

    @Test
    void mergesPartsIntoOneSceneWithEveryReferenceRelocated() throws IOException {
        Path triangle = write("triangle.obj", 0, TRIANGLE_POSITIONS, TRIANGLE_INDICES, 1);
        Path quad = write("quad.obj", 1, QUAD_POSITIONS, QUAD_INDICES, 3);
        Path merged = work.resolve("merged.glb");

        GlbMerger.Merged result = GlbMerger.merge(List.of(new GlbMerger.Part(triangle, "triangle.obj"), new GlbMerger.Part(quad, "quad.obj")),
                                                  merged);

        Glb.Container container = Glb.open(merged);
        ObjectNode json = container.json();
        assertThat(result.triangles()).isEqualTo(1 + 3 * 2);
        assertThat(result.sizeBytes()).isEqualTo(Files.size(merged));
        assertThat(json.at("/scenes/0/nodes")
                       .toString()).isEqualTo("[0,1,2,3]");
        assertThat(json.at("/nodes/0/name")
                       .asText()).isEqualTo("triangle.obj");
        assertThat(json.at("/nodes/3/name")
                       .asText()).isEqualTo("quad.obj");
        assertThat(json.at("/nodes/3/mesh")
                       .asInt()).isEqualTo(1);
        assertThat(json.at("/meshes/1/name")
                       .asText()).isEqualTo("quad.obj");
        assertThat(json.at("/meshes/1/primitives/0/attributes/POSITION")
                       .asInt()).isEqualTo(2);
        assertThat(json.at("/meshes/1/primitives/0/indices")
                       .asInt()).isEqualTo(3);
        assertThat(json.at("/meshes/1/primitives/0/material")
                       .asInt()).isEqualTo(1);
        assertThat(json.at("/accessors/3/bufferView")
                       .asInt()).isEqualTo(3);
        assertThat(json.at("/bufferViews/2/byteOffset")
                       .asLong()).isEqualTo(48);
        assertThat(json.at("/bufferViews/3/byteOffset")
                       .asLong()).isEqualTo(48 + 48);
        assertThat(json.at("/buffers")
                       .size()).isEqualTo(1);
        assertThat(json.at("/buffers/0/byteLength")
                       .asLong()).isEqualTo(48 + 72);

        byte[] expected = concat(bytes(triangle, Glb.open(triangle)), bytes(quad, Glb.open(quad)));
        assertThat(bytes(merged, container)).isEqualTo(expected);
    }

    @Test
    void relocatesMeshoptCompressedViewsFallbackBuffersAndInstanceAttributes() throws IOException {
        Path first = compressedInstancedPart("first", 5, 40, 64);
        Path second = compressedInstancedPart("second", 2, 24, 32);
        Path merged = work.resolve("merged.glb");

        GlbMerger.Merged result = GlbMerger.merge(List.of(new GlbMerger.Part(first, "first.obj"), new GlbMerger.Part(second, "second.obj")), merged);

        ObjectNode json = Glb.open(merged)
                             .json();
        assertThat(json.at("/extensionsUsed")
                       .toString()).isEqualTo("[\"EXT_meshopt_compression\",\"EXT_mesh_gpu_instancing\"]");
        assertThat(json.at("/extensionsRequired")
                       .toString()).isEqualTo("[\"EXT_meshopt_compression\"]");
        assertThat(json.at("/buffers")
                       .size()).isEqualTo(2);
        assertThat(json.at("/buffers/0/byteLength")
                       .asLong()).isEqualTo(40 + 24);
        assertThat(json.at("/buffers/1/byteLength")
                       .asLong()).isEqualTo(64 + 32);
        assertThat(json.at("/buffers/1/extensions/EXT_meshopt_compression/fallback")
                       .asBoolean()).isTrue();

        JsonNode secondIndexView = json.at("/bufferViews/3");
        assertThat(secondIndexView.at("/buffer")
                                  .asInt()).isEqualTo(1);
        assertThat(secondIndexView.at("/byteOffset")
                                  .asLong()).isEqualTo(64 + 16);
        assertThat(secondIndexView.at("/extensions/EXT_meshopt_compression/buffer")
                                  .asInt()).isZero();
        assertThat(secondIndexView.at("/extensions/EXT_meshopt_compression/byteOffset")
                                  .asLong()).isEqualTo(40 + 8);

        assertThat(json.at("/nodes/1/extensions/EXT_mesh_gpu_instancing/attributes/TRANSLATION")
                       .asInt()).isEqualTo(3 + 2);
        assertThat(json.at("/nodes/1/extensions/EXT_mesh_gpu_instancing/attributes/ROTATION")
                       .asInt()).isEqualTo(3 + 2);
        assertThat(result.triangles()).isEqualTo(5 * 4 + 2 * 4);
    }

    @Test
    void refusesPartsCarryingThingsAPreviewNeverHas() throws IOException {
        ObjectNode json = Glb.JSON.createObjectNode();
        json.putObject("asset")
            .put("version", "2.0");
        json.putArray("textures")
            .addObject();
        Path textured = work.resolve("textured.glb");
        Glb.write(textured, json, 0, channel -> {
        });

        assertThatThrownBy(() -> GlbMerger.merge(List.of(new GlbMerger.Part(textured, "x")), work.resolve("out.glb"))).isInstanceOf(IOException.class)
                                                                                                                      .hasMessageContaining("textures");
    }

    private Path write(String name, int index, float[] positions, int[] indices, int copies) throws IOException {
        PreviewPart.Instance[] instances = new PreviewPart.Instance[copies];
        Arrays.fill(instances, new PreviewPart.Instance(0, 0, 0, 1, 0, 0, 0));
        Path path = work.resolve(name + ".glb");
        PreviewPartWriter.write(new PreviewPart(index, name, positions, indices, List.of(instances)), path);
        return path;
    }

    private Path compressedInstancedPart(String name, int instances, int binLength, int fallbackLength) throws IOException {
        ObjectNode json = Glb.JSON.createObjectNode();
        json.putObject("asset")
            .put("version", "2.0")
            .put("generator", "gltfpack 1.3");
        json.putArray("extensionsUsed")
            .add("EXT_meshopt_compression")
            .add("EXT_mesh_gpu_instancing");
        json.putArray("extensionsRequired")
            .add("EXT_meshopt_compression");
        json.put("scene", 0);
        json.putArray("scenes")
            .addObject()
            .putArray("nodes")
            .add(0);
        ObjectNode node = json.putArray("nodes")
                              .addObject();
        node.put("mesh", 0);
        node.putObject("extensions")
            .putObject("EXT_mesh_gpu_instancing")
            .putObject("attributes")
            .put("TRANSLATION", 2)
            .put("ROTATION", 2);
        ObjectNode primitive = json.putArray("meshes")
                                   .addObject()
                                   .putArray("primitives")
                                   .addObject();
        primitive.putObject("attributes")
                 .put("POSITION", 0);
        primitive.put("indices", 1);
        json.putArray("accessors")
            .add(accessor(0, 4))
            .add(accessor(1, 12))
            .add(accessor(1, instances));
        json.putArray("bufferViews")
            .add(view(0, 0))
            .add(view(16, 8));
        json.putArray("buffers")
            .add(Glb.JSON.createObjectNode()
                         .put("byteLength", binLength))
            .add(fallback(fallbackLength));
        Path path = work.resolve(name + ".glb");
        Glb.write(path, json, binLength, channel -> Glb.writeFully(channel, ByteBuffer.allocate(binLength)));
        return path;
    }

    private static ObjectNode accessor(int view, int count) {
        return Glb.JSON.createObjectNode()
                       .put("bufferView", view)
                       .put("count", count)
                       .put("componentType", 5126)
                       .put("type", "SCALAR");
    }

    private static ObjectNode view(int fallbackOffset, int binOffset) {
        ObjectNode view = Glb.JSON.createObjectNode()
                                  .put("buffer", 1)
                                  .put("byteOffset", fallbackOffset)
                                  .put("byteLength", 16);
        view.putObject("extensions")
            .putObject("EXT_meshopt_compression")
            .put("buffer", 0)
            .put("byteOffset", binOffset)
            .put("byteLength", 8)
            .put("byteStride", 4)
            .put("count", 4)
            .put("mode", "ATTRIBUTES");
        return view;
    }

    private static ObjectNode fallback(int length) {
        ObjectNode buffer = Glb.JSON.createObjectNode()
                                    .put("byteLength", length);
        buffer.putObject("extensions")
              .putObject("EXT_meshopt_compression")
              .put("fallback", true);
        return buffer;
    }

    private static ByteBuffer bin(Path path, Glb.Container container) throws IOException {
        return ByteBuffer.wrap(bytes(path, container))
                         .order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] bytes(Path path, Glb.Container container) throws IOException {
        byte[] all = Files.readAllBytes(path);
        return Arrays.copyOfRange(all, (int) container.binOffset(), (int) (container.binOffset() + container.binLength()));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
