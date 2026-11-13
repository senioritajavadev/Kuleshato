package kuleshato;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Сценарная модель Ollama для тестов: отвечает по очереди заранее заданными сообщениями и запоминает запросы. */
final class ScriptedOllama implements AutoCloseable {
    private final com.sun.net.httpserver.HttpServer server;
    final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

    ScriptedOllama(List<Map<String, Object>> replies) throws Exception {
        server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/api/chat", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int index = Math.min(next.getAndIncrement(), replies.size() - 1);
            byte[] bytes = Json.stringify(Map.of("message", replies.get(index))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static Map<String, Object> toolCall(String name, Map<String, Object> arguments) {
        return Map.of("role", "assistant", "content", "",
            "tool_calls", List.of(Map.of("function", Map.of("name", name, "arguments", arguments))));
    }

    static Map<String, Object> answer(String text) {
        return Map.of("role", "assistant", "content", text);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
