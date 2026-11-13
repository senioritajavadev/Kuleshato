package kuleshato;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SessionStore {
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private final Path directory;

    SessionStore() {
        this(Path.of(System.getProperty("user.home"), ".kuleshato", "sessions"));
    }

    SessionStore(Path directory) {
        this.directory = directory;
    }

    Map<String, Object> create(Path workspace, String model) {
        String now = Instant.now().toString();
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", UUID.randomUUID().toString());
        session.put("workspace", workspace.toString());
        session.put("model", model);
        session.put("createdAt", now);
        session.put("updatedAt", now);
        session.put("messages", new ArrayList<>());
        session.put("plan", null);
        session.put("activeSkills", new ArrayList<>());
        return session;
    }

    void save(Map<String, Object> session) throws IOException {
        String id = string(session.get("id"), "session id");
        if (!id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("Нельзя сохранить сессию с некорректным ID.");
        }
        Files.createDirectories(directory);
        session.put("updatedAt", Instant.now().toString());
        byte[] content = Json.stringify(session).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (content.length > MAX_BYTES) throw new IOException("История сессии превышает 16 МБ.");
        Path target = directory.resolve(id + ".json");
        Path temporary = directory.resolve(id + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Map<String, Object> load(String id, Path workspace) throws IOException {
        Path path = sessionPath(id);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Файл сессии отсутствует или не является обычным файлом: " + id);
        }
        if (Files.size(path) > MAX_BYTES) throw new IOException("История сессии превышает 16 МБ.");
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(path));
        } catch (IllegalArgumentException error) {
            throw new IOException("Файл сессии содержит некорректный JSON: " + id, error);
        }
        if (!(parsed instanceof Map<?, ?> source) || !id.equals(source.get("id"))
            || !(source.get("workspace") instanceof String savedWorkspace)
            || !(source.get("model") instanceof String)
            || !(source.get("messages") instanceof List<?> messages)
            || messages.stream().anyMatch(message ->
                !(message instanceof Map<?, ?> map) || !(map.get("role") instanceof String))) {
            throw new IOException("Файл сессии имеет неверный формат: " + id);
        }
        if (!workspace.toString().equals(savedWorkspace)) {
            throw new IOException("Эта сессия принадлежит другому рабочему каталогу.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) source;
        result.putIfAbsent("plan", null);
        result.putIfAbsent("activeSkills", new ArrayList<>());
        return result;
    }

    List<Map<String, Object>> list(Path workspace) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(item -> item.getFileName().toString().endsWith(".json"))
                .filter(item -> Files.isRegularFile(item, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
                String id = path.getFileName().toString().replaceFirst("\\.json$", "");
                if (!id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) continue;
                if (Files.size(path) > MAX_BYTES) continue;
                Object value;
                try {
                    value = Json.parse(Files.readString(path));
                } catch (IllegalArgumentException error) {
                    throw new IOException("Файл сессии содержит некорректный JSON: " + id, error);
                }
                if (!(value instanceof Map<?, ?> session) || !id.equals(session.get("id"))
                    || !workspace.toString().equals(session.get("workspace"))
                    || !(session.get("messages") instanceof List<?> messages)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("updatedAt", session.containsKey("updatedAt") ? session.get("updatedAt") : "");
                item.put("model", session.containsKey("model") ? session.get("model") : "");
                item.put("messages", messages.size());
                result.add(item);
            }
        }
        result.sort((left, right) -> String.valueOf(right.get("updatedAt")).compareTo(String.valueOf(left.get("updatedAt"))));
        return result.subList(0, Math.min(20, result.size()));
    }

    private Path sessionPath(String id) throws IOException {
        if (!id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("Некорректный ID сессии.");
        }
        return directory.resolve(id + ".json");
    }

    private static String string(Object value, String field) throws IOException {
        if (value instanceof String text) return text;
        throw new IOException("Invalid " + field);
    }
}
