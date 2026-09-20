package dev.jingyu.jia.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jingyu.jia.analyze.AnalysisResult;
import dev.jingyu.jia.analyze.Engine;
import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.analyze.Rules;
import dev.jingyu.jia.llm.MockProvider;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.parse.ParseReport;
import dev.jingyu.jia.parse.SnapshotLoader;
import dev.jingyu.jia.report.JsonReport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP server over stdio: newline-framed JSON-RPC 2.0.
 *
 * <p>Hand-written on purpose. The official Java SDK (verified present at
 * {@code io.modelcontextprotocol.sdk:mcp:2.0.1}) drags in reactor-core and a full Jackson
 * stack to serve three methods; {@code initialize}, {@code tools/list} and {@code tools/call}
 * fit in this file, which keeps the shipped jar a single small dependency set and the protocol
 * surface auditable in one sitting. See {@code docs/adr/0001-hand-written-mcp-transport.md}.
 *
 * <p>Framing follows MCP: one JSON-RPC message per line, no batching, responses in the same
 * order. Logs go to stderr — stdout is the wire.
 */
public final class McpServer {

    public static final String PROTOCOL_VERSION = "2024-11-05";
    public static final String SERVER_NAME = "jvm-incident-agent";

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonReport json = new JsonReport();
    private final List<Rule> rules = Rules.all();

    public void serve() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode request;
            try {
                request = mapper.readTree(line);
            } catch (IOException | RuntimeException e) {
                // Jackson throws checked JsonProcessingException here; either way the client gets -32700.
                write(out, error(null, -32700, "parse error: " + shortMessage(e)));
                continue;
            }
            JsonNode id = request.get("id");
            String method = request.path("method").asText("");
            if (method.isEmpty()) {
                continue; // a response or notification from the client; nothing to do
            }
            if (!request.has("id")) {
                continue; // notifications/initialized and friends
            }
            try {
                JsonNode result = dispatch(method, request.path("params"));
                if (result != null) {
                    write(out, reply(id, result));
                }
            } catch (UnknownMethod e) {
                write(out, error(id, -32601, e.getMessage()));
            } catch (InvalidParams e) {
                write(out, error(id, -32602, e.getMessage()));
            } catch (RuntimeException | IOException e) {
                write(out, error(id, -32603, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        out.flush();
    }

    private JsonNode dispatch(String method, JsonNode params) throws IOException {
        switch (method) {
            case "initialize" -> {
                ObjectNode result = mapper.createObjectNode();
                result.put("protocolVersion", PROTOCOL_VERSION);
                ObjectNode caps = result.putObject("capabilities");
                caps.putObject("tools").put("listChanged", false);
                ObjectNode info = result.putObject("serverInfo");
                info.put("name", SERVER_NAME);
                info.put("version", Engine.VERSION);
                info.put("title", "Deterministic JVM incident analysis with evidence chains");
                result.put("instructions", "Analyze JVM incident artifacts locally. Every finding quotes "
                        + "file:line evidence from the input; findings never change between runs.");
                return result;
            }
            case "tools/list" -> {
                ObjectNode result = mapper.createObjectNode();
                result.set("tools", toolList());
                return result;
            }
            case "tools/call" -> {
                return callTool(params);
            }
            case "ping" -> {
                return mapper.createObjectNode();
            }
            default -> throw new UnknownMethod("method not found: " + method);
        }
    }

    private ArrayNode toolList() {
        ArrayNode tools = mapper.createArrayNode();
        tools.addObject()
                .put("name", "analyze_snapshot")
                .put("description", "Analyze a JVM incident snapshot: a directory of thread dumps, GC "
                        + "logs, heap histograms and application logs, or the text of one such file. "
                        + "Returns ranked findings, each with file:line evidence.")
                .set("inputSchema", objectSchema(List.of("path"), Map.of(
                        "path", "absolute path to a snapshot directory or to one artifact file",
                        "content", "raw artifact text, when there is no file on disk",
                        "fileName", "name to attribute content to (default pasted-input.txt)",
                        "format", "md or json (default json)")));
        tools.addObject()
                .put("name", "explain_finding")
                .put("description", "Return the documentation for one rule id (TDA001, GCA003, …): what "
                        + "it detects, how, and its known false-positive limits.")
                .set("inputSchema", objectSchema(List.of("ruleId"),
                        Map.of("ruleId", "rule identifier, e.g. TDA001")));
        tools.addObject()
                .put("name", "list_rules")
                .put("description", "List every rule with its id, title and the artifact it reads.")
                .set("inputSchema", objectSchema(List.of(), Map.of()));
        return tools;
    }

    private ObjectNode objectSchema(List<String> required, Map<String, String> descriptions) {
        ObjectNode s = mapper.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        descriptions.forEach((name, desc) -> props.putObject(name).put("type", "string").put("description", desc));
        ArrayNode req = s.putArray("required");
        required.forEach(req::add);
        s.put("additionalProperties", false);
        return s;
    }

    private ObjectNode callTool(JsonNode params) throws IOException {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        return switch (name) {
            case "analyze_snapshot" -> analyzeTool(args);
            case "explain_finding" -> explainTool(args.path("ruleId").asText(""));
            case "list_rules" -> textResult(json.rulesJson());
            default -> throw new UnknownMethod("unknown tool: " + name);
        };
    }

    private ObjectNode analyzeTool(JsonNode args) throws IOException {
        String path = args.path("path").asText("");
        String content = args.path("content").asText("");
        ParseReport<Snapshot> loaded;
        if (!content.isBlank()) {
            loaded = SnapshotLoader.loadText(
                    args.path("fileName").asText("pasted-input.txt"), content);
        } else if (!path.isBlank()) {
            Path target = Path.of(path);
            if (!java.nio.file.Files.exists(target)) {
                throw new InvalidParams("no such file or directory: " + path);
            }
            loaded = SnapshotLoader.load(target);
        } else {
            throw new InvalidParams("provide either 'path' or 'content'");
        }
        AnalysisResult result = new Engine().analyze(loaded.value(), Config.defaults());
        String format = args.path("format").asText("json");
        String body = "md".equalsIgnoreCase(format)
                ? new dev.jingyu.jia.report.MarkdownReport().render(result, new MockProvider().narrate(result))
                : json.render(result, null);
        ObjectNode text = mapper.createObjectNode();
        text.put("type", "text");
        text.put("text", body);
        ArrayNode content2 = mapper.createArrayNode().add(text);
        ObjectNode result2 = mapper.createObjectNode();
        result2.set("content", content2);
        result2.put("isError", false);
        result2.put("structuredContent", result.findings().size() + " findings, "
                + result.hypotheses().size() + " hypotheses");
        return result2;
    }

    private ObjectNode explainTool(String ruleId) {
        Optional<Rule> rule = Rules.byId(ruleId);
        if (rule.isEmpty()) {
            return textResult("No rule named '" + ruleId + "'. Known ids: "
                    + String.join(", ", rules.stream().map(Rule::id).toList()));
        }
        return textResult(rule.get().doc());
    }

    private ObjectNode textResult(String text) {
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "text");
        item.put("text", text);
        ObjectNode result = mapper.createObjectNode();
        result.set("content", mapper.createArrayNode().add(item));
        result.put("isError", false);
        return result;
    }

    /** One-line error text: a JSON-RPC message must never contain a raw newline. */
    private static String shortMessage(Throwable t) {
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return m.replaceAll("\\s+", " ").replace("  ", " ").strip();
    }

    private ObjectNode reply(JsonNode id, JsonNode result) {
        ObjectNode n = mapper.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id);
        n.set("result", result);
        return n;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode n = mapper.createObjectNode();
        n.put("jsonrpc", "2.0");
        if (id == null) {
            n.putNull("id");
        } else {
            n.set("id", id);
        }
        ObjectNode err = n.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return n;
    }

    private void write(BufferedWriter out, ObjectNode message) throws IOException {
        out.write(mapper.writeValueAsString(message));
        out.write("\n");
        out.flush();
    }

    private static final class UnknownMethod extends RuntimeException {
        UnknownMethod(String message) {
            super(message);
        }
    }

    private static final class InvalidParams extends RuntimeException {
        InvalidParams(String message) {
            super(message);
        }
    }
}
