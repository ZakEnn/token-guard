package io.tokenguard.bench;

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
 * P2.1 fixture: deterministic downstream MCP server for the composition task.
 * get_data_N returns a ~2 KB deterministic payload — the cost an agent would
 * pay to see the raw tool output in context.
 */
public final class FakeDownstream {

    private static final ObjectMapper M = new ObjectMapper();

    public static void main(String[] args) throws Exception {
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
            if (id == null || id.isNull()) continue;

            ObjectNode result = M.createObjectNode();
            switch (method) {
                case "initialize" -> {
                    result.put("protocolVersion", "2025-06-18");
                    result.set("capabilities", M.createObjectNode());
                    ObjectNode info = M.createObjectNode();
                    info.put("name", "bench-downstream");
                    info.put("version", "1.0");
                    result.set("serverInfo", info);
                }
                case "tools/list" -> {
                    ArrayNode tools = M.createArrayNode();
                    for (int i = 0; i < 8; i++) {
                        ObjectNode t = M.createObjectNode();
                        t.put("name", "get_data_" + i);
                        t.put("description", "Returns dataset " + i + " (rows of records).");
                        ObjectNode schema = M.createObjectNode();
                        schema.put("type", "object");
                        schema.set("properties", M.createObjectNode());
                        t.set("inputSchema", schema);
                        tools.add(t);
                    }
                    result.set("tools", tools);
                }
                case "tools/call" -> {
                    String tool = msg.get("params").get("name").asText();
                    int n = Integer.parseInt(tool.substring(tool.lastIndexOf('_') + 1));
                    ArrayNode content = M.createArrayNode();
                    ObjectNode text = M.createObjectNode();
                    text.put("type", "text");
                    text.put("text", payload(n));
                    content.add(text);
                    result.set("content", content);
                    result.put("isError", false);
                }
                default -> { }
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

    /** ~2 KB, fully deterministic per dataset index. */
    static String payload(int n) {
        StringBuilder sb = new StringBuilder("dataset ").append(n).append('\n');
        for (int row = 0; row < 40; row++)
            sb.append("record,").append(n).append(',').append(row)
              .append(",status=open,priority=").append(row % 5)
              .append(",title=Deterministic record number ").append(row)
              .append(" of dataset ").append(n).append('\n');
        sb.append("total: 40 rows; open: 40\n");
        return sb.toString();
    }
}
