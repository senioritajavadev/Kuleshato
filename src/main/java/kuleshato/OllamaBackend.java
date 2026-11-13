package kuleshato;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Адаптер Ollama: {@code POST /api/chat} без потоковой передачи, только по loopback-адресу. */
final class OllamaBackend implements ModelBackend {
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private final String baseUrl;
    private final String model;
    private Usage usage = Usage.EMPTY;

    @Override
    public synchronized Usage usage() {
        return usage;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    OllamaBackend(String baseUrl, String model) throws IOException {
        ProjectConfig.validateOllamaUrl(baseUrl);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Map<String, Object> complete(Request request) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", request.messages());
        if (request.tools() != null) body.put("tools", request.tools());
        body.put("stream", false);
        body.put("think", request.think());
        if (request.json()) body.put("format", "json");
        body.put("options", Map.of(
            "num_ctx", request.contextTokens(), "num_predict", request.maxTokens(), "temperature", request.temperature()));
        Map<String, Object> payload = post(body, request.timeout());
        if (!(payload.get("message") instanceof Map<?, ?> message)) throw new IOException("Ollama response did not contain a message");
        synchronized (this) {
            usage = usage.plus(number(payload.get("prompt_eval_count")), number(payload.get("eval_count")),
                number(payload.get("eval_duration")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        message.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Map<String, Object> post(Map<String, Object> body, Duration timeout) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException error) {
            throw new TimeoutException("Ollama не ответила за " + timeout.toMinutes() + " мин. Запрос остановлен; "
                + "проверьте нагрузку и размер контекста модели.", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос к Ollama прерван.", error);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama returned HTTP " + response.statusCode() + ": "
                + response.body().substring(0, Math.min(response.body().length(), 1000)));
        }
        Object parsed;
        try {
            parsed = Json.parse(response.body());
        } catch (IllegalArgumentException error) {
            throw new IOException("Ollama returned invalid JSON.", error);
        }
        if (!(parsed instanceof Map<?, ?> map)) throw new IOException("Ollama returned an invalid response.");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
