package com.packing.backend.infra.packing.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GlbMerger {

    static final String INSTANCING = "EXT_mesh_gpu_instancing";

    private static final Set<String> MESHOPT      = Set.of("EXT_meshopt_compression", "KHR_meshopt_compression");
    private static final Set<String> UNSUPPORTED  = Set.of("textures", "images", "samplers", "cameras", "skins", "animations");
    private static final String      BUFFER       = "buffer";
    private static final String      BYTE_OFFSET  = "byteOffset";
    private static final String      BYTE_LENGTH  = "byteLength";
    private static final String      EXTENSIONS   = "extensions";
    private static final String      ATTRIBUTES   = "attributes";
    private static final String      ACCESSORS    = "accessors";
    private static final String      BUFFER_VIEWS = "bufferViews";
    private static final String      MESHES       = "meshes";
    private static final String      MATERIALS    = "materials";
    private static final String      NODES        = "nodes";
    private static final String      INDICES      = "indices";

    private GlbMerger() {
    }

    static Merged merge(List<Part> parts, Path output) throws IOException {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Nothing to merge");
        }
        List<Glb.Container> containers = new ArrayList<>();
        for (Part part : parts) {
            containers.add(Glb.open(part.glb()));
        }

        ObjectNode merged = Glb.JSON.createObjectNode();
        merged.set("asset",
                   containers.getFirst()
                             .json()
                             .path("asset")
                             .deepCopy());
        merged.put("scene", 0);
        ArrayNode sceneNodes = merged.putArray("scenes")
                                     .addObject()
                                     .putArray(NODES);
        ArrayNode nodes = merged.putArray(NODES);
        ArrayNode meshes = merged.putArray(MESHES);
        ArrayNode materials = merged.putArray(MATERIALS);
        ArrayNode accessors = merged.putArray(ACCESSORS);
        ArrayNode views = merged.putArray(BUFFER_VIEWS);
        Set<String> used = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();

        long binLength = 0;
        long fallbackLength = 0;
        ObjectNode fallbackExtensions = null;
        for (int p = 0; p < containers.size(); p++) {
            Glb.Container container = containers.get(p);
            ObjectNode json = container.json();
            for (String unsupported : UNSUPPORTED) {
                if (json.path(unsupported)
                        .size() > 0) {
                    throw new IOException(container.path() + " uses " + unsupported + ", which a preview never carries");
                }
            }
            json.path("extensionsUsed")
                .forEach(name -> used.add(name.asText()));
            json.path("extensionsRequired")
                .forEach(name -> required.add(name.asText()));

            int binIndex = -1;
            int fallbackIndex = -1;
            JsonNode buffers = json.path("buffers");
            for (int b = 0; b < buffers.size(); b++) {
                JsonNode buffer = buffers.get(b);
                if (buffer.has("uri")) {
                    throw new IOException(container.path() + " refers to an external buffer");
                }
                JsonNode meshopt = meshoptExtension(buffer);
                if (meshopt != null && meshopt.path("fallback")
                                              .asBoolean()) {
                    fallbackIndex = b;
                    fallbackExtensions = (ObjectNode) buffer.get(EXTENSIONS)
                                                            .deepCopy();
                } else if (binIndex < 0) {
                    binIndex = b;
                } else {
                    throw new IOException(container.path() + " has more than one embedded buffer");
                }
            }
            if (binIndex >= 0 && buffers.get(binIndex)
                                        .path(BYTE_LENGTH)
                                        .asLong()
                    > container.binLength()) {
                throw new IOException(container.path() + " declares more buffer bytes than its BIN chunk holds");
            }

            int viewOffset = views.size();
            int accessorOffset = accessors.size();
            int meshOffset = meshes.size();
            int materialOffset = materials.size();
            int nodeOffset = nodes.size();

            for (JsonNode source : json.path(BUFFER_VIEWS)) {
                ObjectNode view = (ObjectNode) source.deepCopy();
                relocate(view, binIndex, fallbackIndex, binLength, fallbackLength, container);
                JsonNode meshopt = meshoptExtension(view);
                if (meshopt instanceof ObjectNode compressed) {
                    relocate(compressed, binIndex, fallbackIndex, binLength, fallbackLength, container);
                }
                views.add(view);
            }
            for (JsonNode source : json.path(ACCESSORS)) {
                ObjectNode accessor = (ObjectNode) source.deepCopy();
                if (accessor.has("sparse")) {
                    throw new IOException(container.path() + " uses a sparse accessor");
                }
                shift(accessor, "bufferView", viewOffset);
                accessors.add(accessor);
            }
            for (JsonNode source : json.path(MATERIALS)) {
                materials.add(source.deepCopy());
            }
            for (JsonNode source : json.path(MESHES)) {
                ObjectNode mesh = (ObjectNode) source.deepCopy();
                mesh.put("name",
                         parts.get(p)
                              .name());
                for (JsonNode primitive : mesh.path("primitives")) {
                    ObjectNode values = (ObjectNode) primitive;
                    if (values.path(ATTRIBUTES) instanceof ObjectNode attributes) {
                        shiftAll(attributes, accessorOffset);
                    }
                    shift(values, INDICES, accessorOffset);
                    shift(values, "material", materialOffset);
                    for (JsonNode target : values.path("targets")) {
                        if (target instanceof ObjectNode morph) {
                            shiftAll(morph, accessorOffset);
                        }
                    }
                }
                meshes.add(mesh);
            }
            for (JsonNode source : json.path(NODES)) {
                ObjectNode node = (ObjectNode) source.deepCopy();
                shift(node, "mesh", meshOffset);
                JsonNode children = node.path("children");
                for (int c = 0; c < children.size(); c++) {
                    ((ArrayNode) children).set(c,
                                               children.get(c)
                                                       .asInt()
                                                       + nodeOffset);
                }
                JsonNode instancing = node.path(EXTENSIONS)
                                          .path(INSTANCING)
                                          .path(ATTRIBUTES);
                if (instancing instanceof ObjectNode attributes) {
                    shiftAll(attributes, accessorOffset);
                }
                nodes.add(node);
            }
            JsonNode scenes = json.path("scenes");
            JsonNode roots = scenes.path(json.path("scene")
                                             .asInt(0))
                                   .path(NODES);
            for (JsonNode root : roots) {
                int index = root.asInt() + nodeOffset;
                ((ObjectNode) nodes.get(index)).put("name",
                                                    parts.get(p)
                                                         .name());
                sceneNodes.add(index);
            }

            binLength += container.binLength();
            if (fallbackIndex >= 0) {
                fallbackLength += Glb.pad(buffers.get(fallbackIndex)
                                                 .path(BYTE_LENGTH)
                                                 .asLong());
            }
        }

        ArrayNode buffers = merged.putArray("buffers");
        buffers.addObject()
               .put(BYTE_LENGTH, binLength);
        if (fallbackExtensions != null) {
            ObjectNode fallback = buffers.addObject();
            fallback.put(BYTE_LENGTH, fallbackLength);
            fallback.set(EXTENSIONS, fallbackExtensions);
        }
        if (!used.isEmpty()) {
            ArrayNode list = merged.putArray("extensionsUsed");
            used.forEach(list::add);
        }
        if (!required.isEmpty()) {
            ArrayNode list = merged.putArray("extensionsRequired");
            required.forEach(list::add);
        }
        dropEmpty(merged);

        long triangles = triangles(merged);
        Glb.write(output, merged, binLength, channel -> {
            for (Glb.Container container : containers) {
                container.copyBinTo(channel);
            }
        });
        return new Merged(triangles, Files.size(output));
    }

    static long triangles(ObjectNode json) {
        JsonNode accessors = json.path(ACCESSORS);
        JsonNode meshes = json.path(MESHES);
        long total = 0;
        for (JsonNode node : json.path(NODES)) {
            if (!node.has("mesh")) {
                continue;
            }
            long instances = 1;
            JsonNode instancing = node.path(EXTENSIONS)
                                      .path(INSTANCING)
                                      .path(ATTRIBUTES);
            Iterator<JsonNode> attributes = instancing.elements();
            if (attributes.hasNext()) {
                instances = accessors.path(attributes.next()
                                                     .asInt())
                                     .path("count")
                                     .asLong();
            }
            long perInstance = 0;
            for (JsonNode primitive : meshes.path(node.get("mesh")
                                                      .asInt())
                                            .path("primitives")) {
                if (primitive.path("mode")
                             .asInt(PreviewPartWriter.TRIANGLES)
                        != PreviewPartWriter.TRIANGLES) {
                    continue;
                }
                JsonNode counted = primitive.has(INDICES)
                                                          ? accessors.path(primitive.get(INDICES)
                                                                                    .asInt())
                                                          : accessors.path(primitive.path(ATTRIBUTES)
                                                                                    .path("POSITION")
                                                                                    .asInt());
                perInstance += counted.path("count")
                                      .asLong()
                        / 3;
            }
            total += perInstance * instances;
        }
        return total;
    }

    private static void relocate(ObjectNode view,
                                 int binIndex,
                                 int fallbackIndex,
                                 long binOffset,
                                 long fallbackOffset,
                                 Glb.Container container) throws IOException {
        int buffer = view.path(BUFFER)
                         .asInt(-1);
        if (binIndex >= 0 && buffer == binIndex) {
            view.put(BUFFER, 0);
            view.put(BYTE_OFFSET,
                     view.path(BYTE_OFFSET)
                         .asLong()
                             + binOffset);
        } else if (fallbackIndex >= 0 && buffer == fallbackIndex) {
            view.put(BUFFER, 1);
            view.put(BYTE_OFFSET,
                     view.path(BYTE_OFFSET)
                         .asLong()
                             + fallbackOffset);
        } else {
            throw new IOException(container.path() + " has a buffer view on an unknown buffer " + buffer);
        }
    }

    private static JsonNode meshoptExtension(JsonNode owner) {
        JsonNode extensions = owner.path(EXTENSIONS);
        for (String name : MESHOPT) {
            if (extensions.has(name)) {
                return extensions.get(name);
            }
        }
        return null;
    }

    private static void shift(ObjectNode owner, String field, int offset) {
        if (owner.has(field)) {
            owner.put(field,
                      owner.get(field)
                           .asInt()
                              + offset);
        }
    }

    private static void shiftAll(ObjectNode values, int offset) {
        List<String> names = values.properties()
                                   .stream()
                                   .map(Map.Entry::getKey)
                                   .toList();
        for (String name : names) {
            shift(values, name, offset);
        }
    }

    private static void dropEmpty(ObjectNode json) {
        for (String name : List.of(MATERIALS, MESHES, ACCESSORS, BUFFER_VIEWS, NODES)) {
            if (json.path(name)
                    .isEmpty()) {
                json.remove(name);
            }
        }
    }

    record Part(Path glb, String name) {
    }

    record Merged(long triangles, long sizeBytes) {
    }
}
