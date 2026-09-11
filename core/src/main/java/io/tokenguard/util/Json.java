package io.tokenguard.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Single shared mapper, tree-model only. No POJO (de)serialization anywhere in
 * this codebase — keeps GraalVM native-image reflection metadata at zero for
 * our own classes (plan §2, MCP protocol row).
 */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static ObjectNode obj() { return MAPPER.createObjectNode(); }
    public static ArrayNode arr() { return MAPPER.createArrayNode(); }

    public static String str(JsonNode node, String field, String dflt) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || !v.isTextual() ? dflt : v.asText();
    }

    public static int intVal(JsonNode node, String field, int dflt) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || !v.canConvertToInt() ? dflt : v.asInt();
    }

    public static boolean boolVal(JsonNode node, String field, boolean dflt) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || !v.isBoolean() ? dflt : v.asBoolean();
    }

    /** JSON Schema helper: {"type":"object","properties":{...},"required":[...]} */
    public static ObjectNode schemaObject(ObjectNode properties, String... required) {
        ObjectNode s = obj();
        s.put("type", "object");
        s.set("properties", properties);
        ArrayNode req = arr();
        for (String r : required) req.add(r);
        if (req.size() > 0) s.set("required", req);
        return s;
    }

    public static ObjectNode prop(String type, String description) {
        ObjectNode p = obj();
        p.put("type", type);
        p.put("description", description);
        return p;
    }
}
