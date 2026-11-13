package kuleshato;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

record ProjectConfig(
    String model, String ollamaUrl, int contextWindow, boolean autoCompact, boolean autoRunChecks, String backend
) {
    static final ProjectConfig DEFAULT = new ProjectConfig("qwen3:8b", "http://127.0.0.1:11434", 40_960, true, true, "ollama");
    static final Path RELATIVE_PATH = Path.of(".kuleshato", "config.json");

    static ProjectConfig load(Path root) throws IOException {
        Path path = safePath(root, RELATIVE_PATH);
        if (!Files.exists(path)) return DEFAULT;
        if (Files.size(path) > 16 * 1024) throw new IOException("Файл настроек превышает лимит 16 КБ.");
        Object value;
        try {
            value = Json.parse(Files.readString(path));
        } catch (IllegalArgumentException error) {
            throw new IOException("Некорректный JSON в " + RELATIVE_PATH + ": " + error.getMessage(), error);
        }
        if (!(value instanceof Map<?, ?> raw)) throw new IOException("Настройки проекта должны быть JSON-объектом.");
        for (Object key : raw.keySet()) {
            if (!SetHolder.ALLOWED.contains(key)) throw new IOException("Неизвестная настройка проекта: " + key);
        }
        String model = string(raw.containsKey("model") ? raw.get("model") : DEFAULT.model(), "model");
        String url = string(raw.containsKey("ollamaUrl") ? raw.get("ollamaUrl") : DEFAULT.ollamaUrl(), "ollamaUrl");
        Object contextValue = raw.containsKey("contextWindow") ? raw.get("contextWindow") : DEFAULT.contextWindow();
        if (!(contextValue instanceof Number number) || number.doubleValue() != number.intValue()
            || number.intValue() < 4096 || number.intValue() > 131_072) {
            throw new IOException("\"contextWindow\" должен быть целым числом от 4096 до 131072.");
        }
        Object compactValue = raw.containsKey("autoCompact") ? raw.get("autoCompact") : DEFAULT.autoCompact();
        if (!(compactValue instanceof Boolean compact)) throw new IOException("\"autoCompact\" должен быть true или false.");
        Object checksValue = raw.containsKey("autoRunChecks") ? raw.get("autoRunChecks") : DEFAULT.autoRunChecks();
        if (!(checksValue instanceof Boolean checks)) throw new IOException("\"autoRunChecks\" должен быть true или false.");
        String backend = string(raw.containsKey("backend") ? raw.get("backend") : DEFAULT.backend(), "backend");
        if (!ModelBackend.SUPPORTED.contains(backend)) {
            throw new IOException("Адаптер модели \"" + backend + "\" не поддерживается; доступны: "
                + String.join(", ", ModelBackend.SUPPORTED) + ".");
        }
        if (!model.matches("[A-Za-z0-9._/-]+(?::[A-Za-z0-9._-]+)?")) throw new IOException("Некорректное имя модели Ollama.");
        validateOllamaUrl(url);
        return new ProjectConfig(model, url, number.intValue(), compact, checks, backend);
    }

    static void initialize(Path root) throws IOException {
        Path path = safePath(root, RELATIVE_PATH);
        Files.createDirectories(path.getParent());
        String content = "{\n  \"model\": \"qwen3:8b\",\n  \"ollamaUrl\": \"http://127.0.0.1:11434\",\n"
            + "  \"contextWindow\": 40960,\n  \"autoCompact\": true,\n  \"autoRunChecks\": true,\n  \"backend\": \"ollama\"\n}\n";
        try {
            Files.writeString(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException error) {
            throw new IOException("Настройки уже существуют; файл не перезаписан.", error);
        }
    }

    static Path safePath(Path root, Path relative) throws IOException {
        Path workspace = root.toRealPath();
        Path target = workspace.resolve(relative).normalize();
        if (!target.startsWith(workspace)) throw new IOException("Путь настроек выходит за пределы проекта.");
        Path parent = target.getParent();
        if (Files.exists(parent) && !parent.toRealPath().startsWith(workspace)) {
            throw new IOException("Путь настроек выходит за пределы проекта.");
        }
        if (Files.exists(target) && !target.toRealPath().startsWith(workspace)) {
            throw new IOException("Путь настроек выходит за пределы проекта.");
        }
        return target;
    }

    static void validateOllamaUrl(String text) throws IOException {
        try {
            URI uri = URI.create(text);
            String host = uri.getHost() == null ? "" : uri.getHost().replace("[", "").replace("]", "");
            boolean loopback = "localhost".equalsIgnoreCase(host) || ipv4Loopback(host)
                || "::1".equals(host);
            if (!"http".equals(uri.getScheme()) || !loopback || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IOException("Ollama разрешён только по loopback HTTP-адресу.");
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("Некорректный URL Ollama.", error);
        }
    }

    private static boolean ipv4Loopback(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        try {
            int first = Integer.parseInt(parts[0]);
            if (first != 127) return false;
            for (String part : parts) {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255 || !part.matches("\\d{1,3}")) return false;
            }
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static String string(Object value, String name) throws IOException {
        if (!(value instanceof String text) || text.isBlank()) throw new IOException("Настройка \"" + name + "\" должна быть строкой.");
        return text;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> ALLOWED =
            java.util.Set.of("model", "ollamaUrl", "contextWindow", "autoCompact", "autoRunChecks", "backend");
    }
}
