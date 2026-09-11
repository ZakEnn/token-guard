package io.tokenguard.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * P1.1 test fixture: a real, spawnable stdio MCP server. Speaks just enough
 * protocol for DownstreamServer: initialize, tools/list, tools/call.
 * Variant "mortal" (args[0]) additionally exposes a `die` tool that kills the
 * process without responding — the §6.7.5 crash scenario.
 */
public final class FakeMcpServerMain {

    private static final ObjectMapper M = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        boolean mortal = args.length > 0 && "mortal".equals(args[0]);
        int many = args.length > 1 && "many".equals(args[0]) ? Integer.parseInt(args[1]) : 0;
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode msg = M.readTree(line);
            JsonNode id = msg.get("id");
            String method = msg.get("method") != null ? msg.get("method").asText() : "";
            if (id == null || id.isNull()) continue; // notifications

            ObjectNode result = M.createObjectNode();
            switch (method) {
                case "initialize" -> {
                    result.put("protocolVersion", "2025-06-18");
                    result.set("capabilities", M.createObjectNode());
                    ObjectNode info = M.createObjectNode();
                    info.put("name", "fake-" + (mortal ? "mortal" : "alpha"));
                    info.put("version", "1.0");
                    result.set("serverInfo", info);
                }
                case "tools/list" -> {
                    ArrayNode tools = M.createArrayNode();
                    tools.add(tool("echo", "Echoes the msg argument back."));
                    if (mortal) tools.add(tool("die", "Kills this server without replying."));
                    if (many > 0) {
                        // P1.2 fixture: a large synthetic tool surface. One
                        // distinctive tool for ranking assertions, the rest noise.
                        tools.add(tool("translate_text",
                                "Translate text between natural languages, e.g. French to English."));
                        String[] verbs = {"get", "list", "create", "update", "delete"};
                        String[] nouns = {"weather", "issue", "file", "record", "invoice",
                                "ticket", "channel", "commit"};
                        for (int i = 0; tools.size() < many; i++)
                            tools.add(tool(verbs[i % verbs.length] + "_" + nouns[i % nouns.length]
                                    + "_" + i, "Fixture tool " + i + " that "
                                    + verbs[i % verbs.length] + "s a " + nouns[i % nouns.length] + "."));
                    }
                    result.set("tools", tools);
                }
                case "tools/call" -> {
                    String tool = msg.get("params").get("name").asText();
                    if ("die".equals(tool)) {
                        System.exit(7); // crash before responding
                    }
                    JsonNode a = msg.get("params").get("arguments");
                    ArrayNode content = M.createArrayNode();
                    ObjectNode text = M.createObjectNode();
                    text.put("type", "text");
                    text.put("text", "echo:" + (a != null && a.has("msg")
                            ? a.get("msg").asText() : ""));
                    content.add(text);
                    result.set("content", content);
                    result.put("isError", false);
                }
                default -> { /* respond with empty result */ }
            }
            ObjectNode env = M.createObjectNode();
            env.put("jsonrpc", "2.0");
            env.set("id", id);
            env.set("result", result);
            out.write(M.writeValueAsString(env));
            out.write('\n');
            out.flush();
        }
    }

    private static ObjectNode tool(String name, String description) {
        ObjectNode t = M.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = M.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", M.createObjectNode());
        t.set("inputSchema", schema);
        return t;
    }
}
