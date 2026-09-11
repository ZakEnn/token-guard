package io.tokenguard.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Json;

/** Test fixture: the M0 "trivial tool". */
public final class EchoTool implements Tool {

    @Override public String name() { return "echo"; }
    @Override public String description() { return "Echoes back the given text."; }

    @Override public ObjectNode inputSchema() {
        ObjectNode props = Json.obj();
        props.set("text", Json.prop("string", "Text to echo"));
        return Json.schemaObject(props, "text");
    }

    @Override public ToolResult call(ObjectNode args) {
        return ToolResult.text("echo: " + Json.str(args, "text", ""));
    }
}
