package com.packing.backend.domain.file;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public enum ModelFormat {

    STL("model/stl", true, "stl"),
    OBJ("model/obj", true, "obj"),
    STEP("model/step", false, "step", "stp"),
    THREE_MF("model/3mf", true, "3mf"),
    PLY("model/ply", true, "ply"),
    GLTF("model/gltf+json", true, "gltf"),
    GLB("model/gltf-binary", true, "glb");

    private static final Map<String, ModelFormat> BY_EXTENSION;
    private static final Set<String>              UPLOADABLE_EXTENSIONS;

    static {
        Map<String, ModelFormat> byExtension = new LinkedHashMap<>();
        Set<String> uploadable = new TreeSet<>();
        for (ModelFormat format : values()) {
            for (String extension : format.extensions) {
                byExtension.put(extension, format);
                if (format.uploadable) {
                    uploadable.add(extension);
                }
            }
        }
        BY_EXTENSION = Collections.unmodifiableMap(byExtension);
        UPLOADABLE_EXTENSIONS = Collections.unmodifiableSet(uploadable);
    }

    private final String      contentType;
    private final boolean     uploadable;
    private final Set<String> extensions;

    ModelFormat(String contentType, boolean uploadable, String... extensions) {
        this.contentType = contentType;
        this.uploadable = uploadable;
        this.extensions = Set.of(extensions);
    }

    public static ModelFormat fromExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            throw new DomainRuleViolationException("File extension must not be blank");
        }
        ModelFormat format = BY_EXTENSION.get(extension.trim()
                                                       .toLowerCase(Locale.ROOT));
        if (format == null) {
            throw new DomainRuleViolationException(
                                                   "Unsupported 3D model format '" + extension + "'. Supported formats: "
                                                           + String.join(", ", UPLOADABLE_EXTENSIONS));
        }
        return format;
    }

    public static Set<String> uploadableExtensions() {
        return UPLOADABLE_EXTENSIONS;
    }

    public void requireUploadable() {
        if (!uploadable) {
            throw new DomainRuleViolationException(
                                                   name() + " models cannot be converted to OBJ for packing; export the model as STL or 3MF instead");
        }
    }

    public boolean isUploadable() {
        return uploadable;
    }

    public String contentType() {
        return contentType;
    }

    public Set<String> extensions() {
        return new TreeSet<>(extensions);
    }
}
