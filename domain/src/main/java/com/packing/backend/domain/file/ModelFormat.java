package com.packing.backend.domain.file;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The client's declared {@code Content-Type} is ignored: browsers send
 * {@code application/octet-stream} for {@code .stl}/{@code .step} far more often than a
 * real model media type, so the file extension is the only signal trusted here.
 */
public enum ModelFormat {

    STL("model/stl", "stl"),
    OBJ("model/obj", "obj"),
    STEP("model/step", "step", "stp"),
    THREE_MF("model/3mf", "3mf"),
    PLY("model/ply", "ply"),
    GLTF("model/gltf+json", "gltf"),
    GLB("model/gltf-binary", "glb");

    private static final Map<String, ModelFormat> BY_EXTENSION;
    private static final Set<String> ALL_EXTENSIONS;

    static {
        Map<String, ModelFormat> byExtension = new LinkedHashMap<>();
        for (ModelFormat format : values()) {
            for (String extension : format.extensions) {
                byExtension.put(extension, format);
            }
        }
        BY_EXTENSION = Collections.unmodifiableMap(byExtension);
        ALL_EXTENSIONS = Collections.unmodifiableSet(new TreeSet<>(byExtension.keySet()));
    }

    private final String contentType;
    private final Set<String> extensions;

    ModelFormat(String contentType, String... extensions) {
        this.contentType = contentType;
        this.extensions = Set.of(extensions);
    }

    public static ModelFormat fromExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            throw new DomainRuleViolationException("File extension must not be blank");
        }
        ModelFormat format = BY_EXTENSION.get(extension.trim().toLowerCase(Locale.ROOT));
        if (format == null) {
            throw new DomainRuleViolationException(
                    "Unsupported 3D model format '" + extension + "'. Supported formats: "
                            + String.join(", ", ALL_EXTENSIONS));
        }
        return format;
    }

    public static Set<String> allExtensions() {
        return ALL_EXTENSIONS;
    }

    public String contentType() {
        return contentType;
    }

    public Set<String> extensions() {
        return new TreeSet<>(extensions);
    }

    @Override
    public String toString() {
        return name() + Arrays.toString(extensions.toArray());
    }
}
