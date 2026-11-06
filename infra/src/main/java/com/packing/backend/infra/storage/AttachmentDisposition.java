package com.packing.backend.infra.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class AttachmentDisposition {

    private AttachmentDisposition() {
    }

    static String of(String filename) {
        String asciiFallback = filename.replaceAll("[^\\x20-\\x7E]", "_")
                                       .replace("\"", "");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                                   .replace("+", "%20");
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded;
    }
}
