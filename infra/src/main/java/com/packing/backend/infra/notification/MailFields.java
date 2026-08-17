package com.packing.backend.infra.notification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class MailFields {

    static final String ABSENT = "—";

    private final Map<String, String> values = new LinkedHashMap<>();

    void put(String label, Object value) {
        values.put(label, orAbsent(value));
    }

    void putIfPresent(String label, Object value) {
        putIfPresent(label, value, Object::toString);
    }

    <T> void putIfPresent(String label, T value, Function<T, String> format) {
        if (value == null) {
            return;
        }
        String text = text(format.apply(value));
        if (text != null) {
            values.put(label, text);
        }
    }

    Map<String, String> asMap() {
        return Collections.unmodifiableMap(values);
    }

    String asText() {
        return values.entrySet()
                     .stream()
                     .map(field -> field.getKey() + ": " + field.getValue())
                     .collect(Collectors.joining("\n"));
    }

    static String orAbsent(Object value) {
        String text = text(value);
        return text == null ? ABSENT : text;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }
}
