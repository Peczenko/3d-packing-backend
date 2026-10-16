package com.packing.backend.infra.packing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.NonValidationKeyword;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.packing.backend.core.packing.PackingInputInvalidException;
import com.packing.backend.core.packing.PackingInputSchemaChangedException;
import com.packing.backend.core.packing.port.out.PackingInputSchema;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.packing.PackingParameters;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Component
public class JsonSchemaPackingInputSchema implements PackingInputSchema {

    public static final int CURRENT_VERSION = 1;

    static final String RESOURCE = "packing-input/v" + CURRENT_VERSION + "/job-input.schema.json";

    static final String SCHEMA_VERSION = "schemaVersion";
    static final String ITEMS          = "items";
    static final String MAX_RUNTIME    = "maxRuntimeSeconds";
    static final String PARAMETERS     = "parameters";

    private static final List<String> ANNOTATION_KEYWORDS = List.of("x-widget",
                                                                    "x-acceptedFormats",
                                                                    "x-maxTotalCopies",
                                                                    "x-unit");

    private final ObjectMapper     objectMapper;
    private final ObjectReader     strictReader;
    private final String           json;
    private final JsonNode         schemaNode;
    private final JsonSchema       schema;
    private final Set<ModelFormat> acceptedFormats;
    private final String           etag;

    public JsonSchemaPackingInputSchema(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.strictReader = objectMapper.reader()
                                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.json = readResource(RESOURCE);
        this.schemaNode = parseSchema(json);
        this.schema = compile(schemaNode);
        this.acceptedFormats = readAcceptedFormats(schemaNode);
        this.etag = sha256(json);
    }

    @Override
    public SchemaDocument document() {
        return new SchemaDocument(CURRENT_VERSION, json, etag);
    }

    @Override
    public Set<ModelFormat> acceptedFormats() {
        return acceptedFormats;
    }

    @Override
    public ValidatedInput validate(String body) {
        JsonNode root = parseBody(body);
        rejectStaleVersion(root);
        Set<ValidationMessage> messages = schema.validate(root);
        if (!messages.isEmpty()) {
            throw new PackingInputInvalidException(errorsOf(messages));
        }
        ObjectNode effective = ((ObjectNode) root).deepCopy();
        applyDefaults(schemaNode, effective);
        return new ValidatedInput(CURRENT_VERSION,
                                  itemsOf(effective),
                                  effective.get(MAX_RUNTIME)
                                           .asLong(),
                                  new PackingParameters(write(effective.get(PARAMETERS))));
    }

    static void applyDefaults(JsonNode schema, JsonNode instance) {
        if (instance instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> property : schema.path("properties")
                                                              .properties()) {
                String name = property.getKey();
                JsonNode subschema = property.getValue();
                if (!object.has(name) && subschema.has("default")) {
                    object.set(name,
                               subschema.get("default")
                                        .deepCopy());
                }
                if (object.has(name)) {
                    applyDefaults(subschema, object.get(name));
                }
            }
        } else if (instance instanceof ArrayNode array && schema.path("items")
                                                                .isObject()) {
            for (JsonNode element : array) {
                applyDefaults(schema.get("items"), element);
            }
        }
    }

    static Map<String, String> errorsOf(Set<ValidationMessage> messages) {
        Map<String, String> errors = new TreeMap<>();
        for (ValidationMessage message : messages) {
            errors.putIfAbsent(pointerOf(message), describe(message));
        }
        return errors;
    }

    static String pointerOf(ValidationMessage message) {
        String location = message.getInstanceLocation()
                                 .toString();
        return switch (message.getType()) {
            case "additionalProperties", "required" -> location + "/" + escape(message.getProperty());
            default -> location;
        };
    }

    static String escape(String token) {
        return token.replace("~", "~0")
                    .replace("/", "~1");
    }

    private static String describe(ValidationMessage message) {
        return switch (message.getType()) {
            case "additionalProperties" -> "is not allowed";
            case "required" -> "is required";
            default -> message.getError();
        };
    }

    private static void rejectStaleVersion(JsonNode root) {
        JsonNode sent = root.path(SCHEMA_VERSION);
        if (sent.isNumber() && sent.canConvertToExactIntegral() && sent.asLong() != CURRENT_VERSION) {
            throw new PackingInputSchemaChangedException();
        }
    }

    private static List<RequestedItem> itemsOf(JsonNode input) {
        List<RequestedItem> items = new ArrayList<>();
        for (JsonNode item : input.get(ITEMS)) {
            items.add(new RequestedItem(new FileId(UUID.fromString(item.get("fileId")
                                                                       .asText())),
                                        item.get("copies")
                                            .asInt()));
        }
        return items;
    }

    private JsonNode parseBody(String body) {
        JsonNode root;
        try {
            root = body == null ? null : strictReader.readTree(body);
        } catch (JsonProcessingException e) {
            root = null;
        }
        if (root == null || root.isMissingNode()) {
            throw new PackingInputInvalidException(Map.of("", "must be a JSON document"));
        }
        return root;
    }

    private JsonNode parseSchema(String text) {
        try {
            return strictReader.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The packing input schema is not valid JSON", e);
        }
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode packing parameters", e);
        }
    }

    private static JsonSchema compile(JsonNode schemaNode) {
        JsonMetaSchema metaSchema = JsonMetaSchema.builder(JsonMetaSchema.getV202012())
                                                  .keywords(ANNOTATION_KEYWORDS.stream()
                                                                               .map(NonValidationKeyword::new)
                                                                               .toList())
                                                  .build();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                                                                  builder -> builder.metaSchema(metaSchema));
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                                                              .pathType(PathType.JSON_POINTER)
                                                              .formatAssertionsEnabled(true)
                                                              .locale(Locale.ENGLISH)
                                                              .build();
        return factory.getSchema(schemaNode, config);
    }

    private static Set<ModelFormat> readAcceptedFormats(JsonNode schemaNode) {
        JsonNode declared = schemaNode.path("properties")
                                      .path(ITEMS)
                                      .path("x-acceptedFormats");
        if (!declared.isArray() || declared.isEmpty()) {
            throw new IllegalStateException("The packing input schema declares no x-acceptedFormats");
        }
        Set<ModelFormat> formats = EnumSet.noneOf(ModelFormat.class);
        declared.forEach(format -> formats.add(ModelFormat.valueOf(format.asText())));
        return Collections.unmodifiableSet(formats);
    }

    private static String readResource(String resource) {
        try (InputStream stream = JsonSchemaPackingInputSchema.class.getClassLoader()
                                                                    .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Packing input schema " + resource + " is not on the classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read packing input schema " + resource, e);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                                         .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of()
                            .formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
