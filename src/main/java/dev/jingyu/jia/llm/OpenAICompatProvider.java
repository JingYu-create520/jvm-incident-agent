package dev.jingyu.jia.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jingyu.jia.analyze.AnalysisResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Any OpenAI-compatible {@code /chat/completions} endpoint: OpenAI, DeepSeek, Qwen,
 * Ollama, vLLM, a company gateway.
 *
 * <p>Configured by environment, never by a config file it has to search for:
 * {@code JIA_LLM_BASE_URL}, {@code JIA_LLM_API_KEY}, {@code JIA_LLM_MODEL}
 * (optionally {@code JIA_LLM_TIMEOUT_MS}). Only the summary of parsed findings leaves the
 * machine — never a raw dump, log or heap histogram.
 */
public final class OpenAICompatProvider implements Provider {

    public static final String ENV_BASE_URL = "JIA_LLM_BASE_URL";
    public static final String ENV_API_KEY = "JIA_LLM_API_KEY";
    public static final String ENV_MODEL = "JIA_LLM_MODEL";
    public static final String ENV_TIMEOUT = "JIA_LLM_TIMEOUT_MS";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final PromptBuilder prompts = new PromptBuilder();

    public OpenAICompatProvider() {
        this(env(ENV_BASE_URL), env(ENV_API_KEY), env(ENV_MODEL),
                Long.parseLong(env(ENV_TIMEOUT, "45000")));
    }

    public OpenAICompatProvider(String baseUrl, String apiKey, String model, long timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new LlmConfigException(ENV_BASE_URL + " is not set; it must point at the root of an "
                    + "OpenAI-compatible API, e.g. https://api.openai.com/v1");
            }
        if (model == null || model.isBlank()) {
            throw new LlmConfigException(ENV_MODEL + " is not set");
        }
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 15_000)))
                .build();
        this.endpoint = URI.create(trimSlash(baseUrl) + "/chat/completions");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public String name() {
        return "openai-compatible:" + model;
    }

    @Override
    public boolean remote() {
        return true;
    }

    @Override
    public String narrate(AnalysisResult result) {
        return complete(prompts.narrate(result));
    }

    public String explain(dev.jingyu.jia.model.Finding finding) {
        return complete(prompts.explain(finding));
    }

    private String complete(String userPrompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.2);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", PromptBuilder.SYSTEM);
        messages.addObject().put("role", "user").put("content", userPrompt);
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
                            StandardCharsets.UTF_8));
            if (!apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new LlmUnavailableException(endpoint + " returned HTTP " + res.statusCode() + ": "
                        + snippet(res.body()));
            }
            JsonNode root = mapper.readTree(res.body());
            JsonNode text = root.path("choices").path(0).path("message").path("content");
            if (text.isMissingNode() || text.asText().isBlank()) {
                throw new LlmUnavailableException("no message content in response: " + snippet(res.body()));
            }
            return text.asText().strip();
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new LlmUnavailableException("cannot reach " + endpoint + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("interrupted while waiting for " + endpoint);
        } catch (RuntimeException e) {
            throw new LlmUnavailableException(e.toString());
        }
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** Thrown when the environment is not configured for an LLM at all. */
    public static final class LlmConfigException extends RuntimeException {
        public LlmConfigException(String message) {
            super(message);
        }
    }

    /** Thrown when the endpoint was configured but did not answer usefully. */
    public static final class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) {
            super(message);
        }
    }
}
