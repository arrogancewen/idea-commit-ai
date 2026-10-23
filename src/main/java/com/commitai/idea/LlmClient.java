package com.commitai.idea;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Calls a user-configured chat model.
 *
 * <p>Two request shapes are supported because that covers essentially every
 * "bring your own model" endpoint:
 * <ul>
 *   <li>{@code openai} - {@code POST {base}/chat/completions}, {@code Authorization: Bearer ...}
 *       (DeepSeek, OpenAI, Moonshot, Qwen, Ollama, OpenRouter, vLLM, ...)</li>
 *   <li>{@code anthropic} - {@code POST {base}/v1/messages}, {@code x-api-key: ...}
 *       (Anthropic, and DeepSeek's {@code /anthropic} compatibility endpoint)</li>
 * </ul>
 */
public final class LlmClient {

    private final CommitAiSettings.State settings;

    public LlmClient(@NotNull CommitAiSettings.State settings) {
        this.settings = settings;
    }

    public @NotNull String complete(@Nullable String apiKey,
                                    @NotNull String systemPrompt,
                                    @NotNull String userPrompt) throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("API key is not configured. Open Settings > Tools > AI Commit Message.");
        }

        String base = settings.baseUrl == null ? "" : settings.baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            throw new IOException("Base URL is not configured.");
        }

        boolean anthropic = CommitAiSettings.API_ANTHROPIC.equals(settings.apiType);
        String url = anthropic ? base + "/v1/messages" : base + "/chat/completions";

        String body = anthropic
                ? anthropicBody(systemPrompt, userPrompt)
                : openAiBody(systemPrompt, userPrompt);

        Duration timeout = Duration.ofSeconds(Math.max(10, settings.timeoutSeconds));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (anthropic) {
            builder.header("x-api-key", apiKey);
            builder.header("anthropic-version", "2023-06-01");
        } else {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        int status = response.statusCode();
        String text = response.body() == null ? "" : response.body();

        if (status < 200 || status >= 300) {
            throw new IOException(describeHttpError(status, text));
        }

        String content = anthropic ? extractAnthropic(text) : extractOpenAi(text);
        if (content == null || content.isBlank()) {
            throw new IOException("Model returned an empty message. Raw response: " + snippet(text));
        }
        return content.trim();
    }

    // ------------------------------------------------------------- requests

    private String openAiBody(String systemPrompt, String userPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"model\":").append(Json.quote(settings.model)).append(',');
        sb.append("\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":").append(Json.quote(systemPrompt)).append("},");
        sb.append("{\"role\":\"user\",\"content\":").append(Json.quote(userPrompt)).append("}");
        sb.append("],");
        // Without an explicit cap the model is free to ramble; a commit message is short.
        sb.append("\"max_tokens\":").append(Math.max(32, settings.maxOutputTokens)).append(',');
        sb.append("\"temperature\":").append(settings.temperature).append(',');
        sb.append("\"stream\":false");
        sb.append('}');
        return sb.toString();
    }

    private String anthropicBody(String systemPrompt, String userPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"model\":").append(Json.quote(settings.model)).append(',');
        sb.append("\"max_tokens\":").append(Math.max(64, settings.maxOutputTokens)).append(',');
        sb.append("\"system\":").append(Json.quote(systemPrompt)).append(',');
        sb.append("\"messages\":[{\"role\":\"user\",\"content\":")
          .append(Json.quote(userPrompt)).append("}]");
        sb.append('}');
        return sb.toString();
    }

    // ------------------------------------------------------------ responses

    /** OpenAI shape: {@code choices[0].message.content}. */
    private static @Nullable String extractOpenAi(String text) {
        try {
            Object root = Json.parse(text);
            Object choice = Json.at(Json.get(root, "choices"), 0);
            return Json.asString(Json.get(Json.get(choice, "message"), "content"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Anthropic shape: {@code content[].text} (all text blocks concatenated). */
    private static @Nullable String extractAnthropic(String text) {
        try {
            Object root = Json.parse(text);
            List<Object> blocks = Json.asList(Json.get(root, "content"));
            if (blocks == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Object block : blocks) {
                String piece = Json.asString(Json.get(block, "text"));
                if (piece != null) {
                    sb.append(piece);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String describeHttpError(int status, String body) {
        String detail = null;
        try {
            Object root = Json.parse(body);
            Object message = Json.get(Json.get(root, "error"), "message");
            detail = Json.asString(message);
        } catch (RuntimeException ignored) {
            // not JSON, fall through to the raw snippet
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP ").append(status);
        switch (status) {
            case 401:
                sb.append(" Unauthorized - check the API key");
                break;
            case 402:
                sb.append(" Payment Required - the provider account is out of credit/quota");
                break;
            case 403:
                sb.append(" Forbidden - key may lack access to this model");
                break;
            case 404:
                sb.append(" Not Found - check the base URL and model name");
                break;
            case 429:
                sb.append(" Too Many Requests - rate limited");
                break;
            default:
                break;
        }
        if (detail != null && !detail.isBlank()) {
            sb.append(": ").append(detail);
        } else {
            sb.append(": ").append(snippet(body));
        }
        return sb.toString();
    }

    private static String snippet(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 400 ? flat : flat.substring(0, 400) + "...";
    }
}
