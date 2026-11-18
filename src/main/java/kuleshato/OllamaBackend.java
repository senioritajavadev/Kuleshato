package kuleshato;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Адаптер Ollama: {@code POST /api/chat} с потоковой передачей, только по loopback-адресу.
 *
 * <p>Потоковая передача нужна не для красоты: по ней видно, жива ли генерация. Запрос прерывается, если модель
 * перестала присылать токены ({@link #IDLE_TIMEOUT}), или по общему лимиту запроса; наблюдатель получает этап,
 * число токенов и время раз в секунду, а через несколько секунд — где загружена модель ({@code /api/ps}).
 */
final class OllamaBackend implements ModelBackend {
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    /** Сколько можно ждать следующий токен, когда генерация уже началась. */
    static final Duration IDLE_TIMEOUT = Duration.ofSeconds(120);
    /** Через сколько после начала запроса проверить, где загружена модель. */
    private static final Duration PLACEMENT_CHECK = Duration.ofSeconds(3);
    private static final Object END = new Object();

    private final String baseUrl;
    private final String model;
    private Usage usage = Usage.EMPTY;
    private volatile Progress progress = new Progress() {};
    private Duration idleTimeout = IDLE_TIMEOUT;
    private Duration placementCheck = PLACEMENT_CHECK;

    /** Сроки для тестов: тишина, после которой запрос прерывается, и момент проверки размещения модели. */
    OllamaBackend withTimings(Duration idle, Duration placement) {
        this.idleTimeout = idle;
        this.placementCheck = placement;
        return this;
    }

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
    public void progress(Progress listener) {
        progress = listener == null ? new Progress() {} : listener;
    }

    @Override
    public Map<String, Object> complete(Request request) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", request.messages());
        if (request.tools() != null) body.put("tools", request.tools());
        body.put("stream", true);
        body.put("think", request.think());
        if (request.json()) body.put("format", "json");
        body.put("options", Map.of(
            "num_ctx", request.contextTokens(), "num_predict", request.maxTokens(), "temperature", request.temperature()));
        HttpRequest http = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
            .build();
        Stream stream = new Stream(request, progress, idleTimeout, placementCheck);
        CompletableFuture<HttpResponse<InputStream>> pending = HTTP.sendAsync(http, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response = stream.awaitHeaders(pending, this);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String text;
            try (InputStream error = response.body()) {
                text = new String(error.readNBytes(1000), StandardCharsets.UTF_8);
            }
            throw new IOException("Ollama returned HTTP " + response.statusCode() + ": " + text);
        }
        Map<String, Object> result = stream.read(response.body(), this);
        synchronized (this) {
            usage = usage.plus(stream.promptTokens, stream.outputTokens, stream.outputNanos);
        }
        return result;
    }

    /** Состояние одного потокового ответа: накопленный текст, вызовы инструментов, счётчики и сроки. */
    private static final class Stream {
        final Request request;
        final Progress progress;
        final Duration idleTimeout;
        final Duration placementCheck;
        final long started = System.nanoTime();
        long lastChunk = started;
        boolean placementChecked;
        boolean anyChunk;
        final StringBuilder content = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final List<Object> toolCalls = new ArrayList<>();
        String role = "assistant";
        int thinkingTokens;
        int answerTokens;
        long promptTokens;
        long outputTokens;
        long outputNanos;

        Stream(Request request, Progress progress, Duration idleTimeout, Duration placementCheck) {
            this.request = request;
            this.progress = progress;
            this.idleTimeout = idleTimeout;
            this.placementCheck = placementCheck;
        }

        Duration elapsed() {
            return Duration.ofNanos(System.nanoTime() - started);
        }

        Phase phase() {
            return answerTokens > 0 || !toolCalls.isEmpty() ? Phase.ANSWER : thinkingTokens > 0 ? Phase.THINKING : Phase.PROMPT;
        }

        /** Раз в секунду: строка состояния, проверка размещения модели и общий лимит запроса. */
        void tick(OllamaBackend backend) throws IOException {
            Duration elapsed = elapsed();
            if (elapsed.compareTo(request.timeout()) > 0) {
                throw new TimeoutException("Ollama не ответила за " + minutes(request.timeout()) + ". Запрос остановлен.", null);
            }
            if (!placementChecked && elapsed.compareTo(placementCheck) >= 0) {
                placementChecked = true;
                Placement placement = backend.placement();
                if (placement != null) progress.placement(placement);
            }
            progress.update(phase(), thinkingTokens, answerTokens, elapsed);
        }

        HttpResponse<InputStream> awaitHeaders(CompletableFuture<HttpResponse<InputStream>> pending, OllamaBackend backend)
            throws IOException {
            while (true) {
                try {
                    return pending.get(1, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException waiting) {
                    try {
                        tick(backend);
                    } catch (IOException stop) {
                        pending.cancel(true);
                        throw stop;
                    }
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof IOException io) throw new IOException("Не удалось связаться с Ollama: " + io.getMessage(), io);
                    throw new IOException("Не удалось связаться с Ollama: " + cause, cause);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    pending.cancel(true);
                    throw new IOException("Запрос к Ollama прерван.", error);
                }
            }
        }

        Map<String, Object> read(InputStream input, OllamaBackend backend) throws IOException {
            BlockingQueue<Object> lines = new LinkedBlockingQueue<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader buffered = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = buffered.readLine()) != null) lines.add(line);
                } catch (IOException error) {
                    lines.add(error);
                } finally {
                    lines.add(END);
                }
            });
            try {
                long lastTick = System.nanoTime();
                while (true) {
                    Object next = lines.poll(250, TimeUnit.MILLISECONDS);
                    if (next == END) break;
                    if (next instanceof IOException error) throw new IOException("Ollama оборвала ответ: " + error.getMessage(), error);
                    if (next instanceof String line && !line.isBlank()) {
                        lastChunk = System.nanoTime();
                        anyChunk = true;
                        if (chunk(line)) break;
                        if (request.thinkingBudget() > 0 && thinkingTokens > request.thinkingBudget()
                            && answerTokens == 0 && toolCalls.isEmpty()) {
                            throw new ThinkingBudgetExceeded(thinkingTokens);
                        }
                    }
                    long now = System.nanoTime();
                    if (now - lastTick >= 1_000_000_000L) {
                        lastTick = now;
                        tick(backend);
                    }
                    Duration silence = Duration.ofNanos(now - lastChunk);
                    if (anyChunk && silence.compareTo(idleTimeout) > 0) {
                        throw new TimeoutException("Ollama перестала присылать токены: тишина " + silence.toSeconds()
                            + " с. Запрос остановлен.", null);
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Запрос к Ollama прерван.", error);
            } finally {
                input.close();
                reader.interrupt();
            }
            progress.update(phase(), thinkingTokens, answerTokens, elapsed());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("role", role);
            result.put("content", content.toString());
            if (!thinking.isEmpty()) result.put("thinking", thinking.toString());
            if (!toolCalls.isEmpty()) result.put("tool_calls", List.copyOf(toolCalls));
            return result;
        }

        /** Разбирает одну строку NDJSON; возвращает {@code true} на последней. Ответ без потока — одна такая строка. */
        private boolean chunk(String line) throws IOException {
            Object parsed;
            try {
                parsed = Json.parse(line);
            } catch (IllegalArgumentException error) {
                throw new IOException("Ollama returned invalid JSON.", error);
            }
            if (!(parsed instanceof Map<?, ?> map)) throw new IOException("Ollama returned an invalid response.");
            if (map.get("error") != null) throw new IOException("Ollama: " + map.get("error"));
            if (map.get("message") instanceof Map<?, ?> message) {
                if (message.get("role") instanceof String text && !text.isBlank()) role = text;
                if (message.get("content") instanceof String text && !text.isEmpty()) {
                    content.append(text);
                    answerTokens++;
                }
                if (message.get("thinking") instanceof String text && !text.isEmpty()) {
                    thinking.append(text);
                    thinkingTokens++;
                }
                if (message.get("tool_calls") instanceof List<?> calls) toolCalls.addAll(calls);
            } else if (!map.containsKey("done")) {
                throw new IOException("Ollama response did not contain a message");
            }
            boolean done = Boolean.TRUE.equals(map.get("done"));
            if (done || !map.containsKey("done")) {
                promptTokens = number(map.get("prompt_eval_count"));
                outputTokens = number(map.get("eval_count"));
                outputNanos = number(map.get("eval_duration"));
            }
            return done;
        }

        private static String minutes(Duration duration) {
            return duration.toMinutes() > 0 ? duration.toMinutes() + " мин" : duration.toSeconds() + " с";
        }
    }

    /** {@code GET /api/ps}: доля модели в видеопамяти и окно, с которым она загружена. */
    @Override
    public Placement placement() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/ps")).timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return null;
            return placement(response.body(), model);
        } catch (IOException | RuntimeException error) {
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static Placement placement(String json, String model) {
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (IllegalArgumentException error) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> map) || !(map.get("models") instanceof List<?> models)) return null;
        for (Object raw : models) {
            if (!(raw instanceof Map<?, ?> entry)) continue;
            String name = String.valueOf(entry.get("name"));
            if (!name.equals(model) && !name.equals(model + ":latest") && !String.valueOf(entry.get("model")).equals(model)) continue;
            long size = number(entry.get("size"));
            long vram = number(entry.get("size_vram"));
            if (size <= 0) return null;
            return new Placement(name, Math.min(1.0, (double) vram / size), (int) number(entry.get("context_length")));
        }
        return null;
    }
}
