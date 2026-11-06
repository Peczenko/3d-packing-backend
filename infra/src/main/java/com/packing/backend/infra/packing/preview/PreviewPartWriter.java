package com.packing.backend.infra.packing.preview;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

final class PreviewPartWriter {

    static final int FLOAT         = 5126;
    static final int UNSIGNED_INT  = 5125;
    static final int ARRAY_BUFFER  = 34962;
    static final int ELEMENT_ARRAY = 34963;
    static final int TRIANGLES     = 4;

    private static final double GOLDEN_RATIO_CONJUGATE = 0.618033988749895;
    private static final int    CHUNK_FLOATS           = 1 << 14;

    private PreviewPartWriter() {
    }

    static void write(PreviewPart part, Path path) throws IOException {
        long positionBytes = 4L * part.positions().length;
        long indexBytes = 4L * part.indices().length;
        Glb.write(path, json(part, positionBytes, indexBytes), positionBytes + indexBytes, channel -> {
            writeFloats(channel, part.positions());
            writeInts(channel, part.indices());
        });
    }

    static double[] color(int index) {
        double hue = (index * GOLDEN_RATIO_CONJUGATE) % 1;
        double saturation = 0.6;
        double lightness = 0.55;
        double chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
        double segment = hue * 6;
        double second = chroma * (1 - Math.abs(segment % 2 - 1));
        double[] rgb = switch ((int) segment) {
            case 0 -> new double[] { chroma, second, 0 };
            case 1 -> new double[] { second, chroma, 0 };
            case 2 -> new double[] { 0, chroma, second };
            case 3 -> new double[] { 0, second, chroma };
            case 4 -> new double[] { second, 0, chroma };
            default -> new double[] { chroma, 0, second };
        };
        double offset = lightness - chroma / 2;
        double[] linear = new double[4];
        for (int i = 0; i < 3; i++) {
            linear[i] = Math.pow(rgb[i] + offset, 2.2);
        }
        linear[3] = 1;
        return linear;
    }

    private static ObjectNode json(PreviewPart part, long positionBytes, long indexBytes) {
        ObjectNode root = Glb.JSON.createObjectNode();
        root.putObject("asset")
            .put("version", "2.0")
            .put("generator", "3d-packing-backend");
        root.put("scene", 0);
        ArrayNode sceneNodes = root.putArray("scenes")
                                   .addObject()
                                   .putArray("nodes");
        ArrayNode nodes = root.putArray("nodes");
        for (int i = 0; i < part.instances()
                                .size();
                i++) {
            PreviewPart.Instance instance = part.instances()
                                                .get(i);
            ObjectNode node = nodes.addObject();
            node.put("mesh", 0);
            node.putArray("rotation")
                .add(instance.qx())
                .add(instance.qy())
                .add(instance.qz())
                .add(instance.qw());
            node.putArray("translation")
                .add(instance.tx())
                .add(instance.ty())
                .add(instance.tz());
            sceneNodes.add(i);
        }
        ObjectNode primitive = root.putArray("meshes")
                                   .addObject()
                                   .putArray("primitives")
                                   .addObject();
        primitive.putObject("attributes")
                 .put("POSITION", 0);
        primitive.put("indices", 1);
        primitive.put("material", 0);
        primitive.put("mode", TRIANGLES);
        ObjectNode pbr = root.putArray("materials")
                             .addObject()
                             .putObject("pbrMetallicRoughness");
        ArrayNode baseColor = pbr.putArray("baseColorFactor");
        for (double channel : color(part.index())) {
            baseColor.add(channel);
        }
        pbr.put("metallicFactor", 0.0);
        pbr.put("roughnessFactor", 0.8);

        float[] min = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY };
        float[] max = { Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
        float[] positions = part.positions();
        for (int i = 0; i < positions.length; i++) {
            min[i % 3] = Math.min(min[i % 3], positions[i]);
            max[i % 3] = Math.max(max[i % 3], positions[i]);
        }
        ArrayNode accessors = root.putArray("accessors");
        ObjectNode position = accessors.addObject();
        position.put("bufferView", 0);
        position.put("componentType", FLOAT);
        position.put("count", part.vertexCount());
        position.put("type", "VEC3");
        ArrayNode minimum = position.putArray("min");
        ArrayNode maximum = position.putArray("max");
        for (int i = 0; i < 3; i++) {
            minimum.add(min[i]);
            maximum.add(max[i]);
        }
        ObjectNode index = accessors.addObject();
        index.put("bufferView", 1);
        index.put("componentType", UNSIGNED_INT);
        index.put("count", part.indices().length);
        index.put("type", "SCALAR");

        ArrayNode views = root.putArray("bufferViews");
        views.addObject()
             .put("buffer", 0)
             .put("byteOffset", 0)
             .put("byteLength", positionBytes)
             .put("target", ARRAY_BUFFER);
        views.addObject()
             .put("buffer", 0)
             .put("byteOffset", positionBytes)
             .put("byteLength", indexBytes)
             .put("target", ELEMENT_ARRAY);
        root.putArray("buffers")
            .addObject()
            .put("byteLength", positionBytes + indexBytes);
        return root;
    }

    private static void writeFloats(FileChannel channel, float[] values) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4 * CHUNK_FLOATS)
                                      .order(ByteOrder.LITTLE_ENDIAN);
        for (int start = 0; start < values.length; start += CHUNK_FLOATS) {
            int end = Math.min(values.length, start + CHUNK_FLOATS);
            buffer.clear();
            buffer.asFloatBuffer()
                  .put(values, start, end - start);
            buffer.limit(4 * (end - start));
            Glb.writeFully(channel, buffer);
        }
    }

    private static void writeInts(FileChannel channel, int[] values) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4 * CHUNK_FLOATS)
                                      .order(ByteOrder.LITTLE_ENDIAN);
        for (int start = 0; start < values.length; start += CHUNK_FLOATS) {
            int end = Math.min(values.length, start + CHUNK_FLOATS);
            buffer.clear();
            buffer.asIntBuffer()
                  .put(values, start, end - start);
            buffer.limit(4 * (end - start));
            Glb.writeFully(channel, buffer);
        }
    }
}
