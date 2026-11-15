package com.packing.backend.infra.conversion;

import com.packing.backend.core.file.ModelConversionException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ThreeMfUnit {

    static final String DEFAULT_MODEL = "3D/3dmodel.model";

    private static final String RELATIONSHIPS      = "_rels/.rels";
    private static final String MODEL_RELATIONSHIP = "/3dmodel";
    private static final int    MAX_XML_BYTES      = 256 * 1024;

    private static final Map<String, Double> MILLIMETRES = Map.of(
                                                                  "micron",
                                                                  0.001,
                                                                  "millimeter",
                                                                  1.0,
                                                                  "centimeter",
                                                                  10.0,
                                                                  "inch",
                                                                  25.4,
                                                                  "foot",
                                                                  304.8,
                                                                  "meter",
                                                                  1000.0);

    private ThreeMfUnit() {
    }

    static double millimetresPerUnit(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            String model = rootModel(zip).orElse(DEFAULT_MODEL);
            String unit = unitOf(zip, model);
            Double millimetres = MILLIMETRES.get(unit);
            if (millimetres == null) {
                throw new ModelConversionException("The 3MF model declares an unknown unit '" + unit + "'");
            }
            return millimetres;
        } catch (IOException | XMLStreamException e) {
            throw new ModelConversionException("The 3MF package could not be read: " + e.getMessage());
        }
    }

    private static Optional<String> rootModel(ZipFile zip) throws IOException, XMLStreamException {
        byte[] relationships = head(zip, RELATIONSHIPS);
        if (relationships == null) {
            return Optional.empty();
        }
        XMLStreamReader reader = xml(relationships);
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(reader.getLocalName())) {
                    String type = reader.getAttributeValue(null, "Type");
                    String target = reader.getAttributeValue(null, "Target");
                    if (type != null && type.endsWith(MODEL_RELATIONSHIP) && target != null) {
                        return Optional.of(target.startsWith("/") ? target.substring(1) : target);
                    }
                }
            }
            return Optional.empty();
        } finally {
            reader.close();
        }
    }

    private static String unitOf(ZipFile zip, String model) throws IOException, XMLStreamException {
        byte[] head = head(zip, model);
        if (head == null) {
            throw new ModelConversionException("The 3MF package has no model part " + model);
        }
        XMLStreamReader reader = xml(head);
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    String unit = reader.getAttributeValue(null, "unit");
                    return unit == null || unit.isBlank() ? "millimeter" : unit.strip();
                }
            }
            throw new ModelConversionException("The 3MF model part " + model + " is empty");
        } finally {
            reader.close();
        }
    }

    private static byte[] head(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readNBytes(MAX_XML_BYTES);
        }
    }

    private static XMLStreamReader xml(byte[] content) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory.createXMLStreamReader(new ByteArrayInputStream(content));
    }
}
