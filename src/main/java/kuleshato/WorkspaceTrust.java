package kuleshato;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Доверие к файлам, которые меняют поведение агента: память, навыки и настройки в {@code .kuleshato/}, а также
 * скрипты проверки, которые Kuleshato может запускать без подтверждения.
 *
 * <p>Это «обучающие данные» агента: подменить их — значит научить его чужому, не ломая проверки. Поэтому такие
 * файлы из склонированного проекта не загружаются, пока пользователь их не одобрит. Хэши одобренных файлов хранятся
 * вне проекта ({@code ~/.kuleshato/trust.json}), чтобы сам репозиторий не мог объявить себя доверенным; изменение
 * или появление файла требует нового одобрения.
 */
final class WorkspaceTrust {
    enum State {
        /** В проекте нет файлов, которым нужно доверие. */
        NOTHING,
        /** Все найденные файлы одобрены и не менялись. */
        TRUSTED,
        /** Проект открыт впервые или доверие отозвано. */
        NEW,
        /** Одобренные ранее файлы изменились или появились новые. */
        CHANGED
    }

    record Change(String path, String kind, long size) {}

    record Status(State state, List<Change> changes) {
        boolean trusted() {
            return state == State.NOTHING || state == State.TRUSTED;
        }
    }

    static final int MAX_FILES = 200;
    private static final long MAX_HASH_BYTES = 4L * 1024 * 1024;
    /** Скрипты проверки и обёртки сборки: их запускает автозапуск проверок ({@link CommandPolicy}). */
    private static final List<String> ROOT_SCRIPTS = List.of(
        "test.ps1", "test.sh", "test.bat", "test.cmd", "build.ps1", "build.sh", "build.bat", "build.cmd",
        "mvnw", "mvnw.cmd", "gradlew", "gradlew.bat",
        ".mvn/wrapper/maven-wrapper.properties", "gradle/wrapper/gradle-wrapper.properties");

    private final Path store;

    WorkspaceTrust() {
        this(Path.of(System.getProperty("user.home"), ".kuleshato", "trust.json"));
    }

    WorkspaceTrust(Path store) {
        this.store = store;
    }

    /** Файлы проекта, которые влияют на поведение агента, с их хэшами; пути — относительные, через «/». */
    static Map<String, String> fingerprint(Path root) throws IOException {
        Path workspace = root.toRealPath();
        Map<String, String> result = new TreeMap<>();
        Path config = workspace.resolve(".kuleshato");
        if (Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(config)) {
                for (Path path : paths.sorted().toList()) {
                    if (result.size() >= MAX_FILES) break;
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    result.put(relative(workspace, path), hash(path));
                }
            }
        } else if (Files.exists(config, LinkOption.NOFOLLOW_LINKS)) {
            result.put(relative(workspace, config), hash(config));
        }
        for (String script : ROOT_SCRIPTS) {
            Path path = workspace.resolve(script);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) result.put(script, hash(path));
        }
        return result;
    }

    Status check(Path root) throws IOException {
        Map<String, String> current = fingerprint(root);
        if (current.isEmpty()) return new Status(State.NOTHING, List.of());
        Map<String, String> approved = approved(root);
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, String> file : current.entrySet()) {
            String known = approved == null ? null : approved.get(file.getKey());
            if (known == null) changes.add(change(root, file.getKey(), "новый"));
            else if (!known.equals(file.getValue())) changes.add(change(root, file.getKey(), "изменён"));
        }
        if (changes.isEmpty()) return new Status(State.TRUSTED, List.of());
        return new Status(approved == null ? State.NEW : State.CHANGED, List.copyOf(changes));
    }

    /** Одобряет текущее состояние всех найденных файлов. */
    void approve(Path root) throws IOException {
        Map<String, Object> all = readStore();
        all.put(key(root), new LinkedHashMap<>(fingerprint(root)));
        writeStore(all);
    }

    /**
     * Обновляет хэш одного файла после его подтверждённой записи самим Kuleshato (урок, шаблон, одобренный diff).
     * Остальные файлы не одобряются: если в проекте есть недоверенные файлы, они остаются недоверенными.
     */
    void refresh(Path root, String relativePath) throws IOException {
        String path = relativePath.replace('\\', '/');
        Map<String, String> current = fingerprint(root);
        if (!current.containsKey(path)) return;
        Map<String, Object> all = readStore();
        Map<String, Object> entry = new LinkedHashMap<>();
        if (all.get(key(root)) instanceof Map<?, ?> existing) existing.forEach((name, value) -> entry.put(String.valueOf(name), value));
        entry.put(path, current.get(path));
        all.put(key(root), entry);
        writeStore(all);
    }

    void revoke(Path root) throws IOException {
        Map<String, Object> all = readStore();
        if (all.remove(key(root)) != null) writeStore(all);
    }

    /** Относится ли путь к файлам, которым нужно доверие (для обновления хэша после собственной записи). */
    static boolean isTracked(String relativePath) {
        String path = relativePath.replace('\\', '/');
        return path.startsWith(".kuleshato/") || ROOT_SCRIPTS.contains(path);
    }

    private Map<String, String> approved(Path root) throws IOException {
        if (!(readStore().get(key(root)) instanceof Map<?, ?> entry)) return null;
        Map<String, String> result = new LinkedHashMap<>();
        entry.forEach((name, value) -> {
            if (value instanceof String hash) result.put(String.valueOf(name), hash);
        });
        return result;
    }

    private static String key(Path root) throws IOException {
        return root.toRealPath().toString();
    }

    private Map<String, Object> readStore() throws IOException {
        if (!Files.isRegularFile(store)) return new LinkedHashMap<>();
        if (Files.size(store) > 4L * 1024 * 1024) throw new IOException("Файл доверия " + store + " превышает 4 МБ.");
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(store, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException error) {
            throw new IOException("Файл доверия " + store + " повреждён: " + error.getMessage(), error);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (parsed instanceof Map<?, ?> map) map.forEach((name, value) -> result.put(String.valueOf(name), value));
        return result;
    }

    private void writeStore(Map<String, Object> all) throws IOException {
        Files.createDirectories(store.getParent());
        Path temporary = store.resolveSibling(store.getFileName() + ".tmp");
        Files.writeString(temporary, Json.stringify(all), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, store, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, store, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Change change(Path root, String path, String kind) {
        long size;
        try {
            size = Files.size(root.toRealPath().resolve(path));
        } catch (IOException error) {
            size = -1;
        }
        return new Change(path, kind, size);
    }

    private static String relative(Path workspace, Path path) {
        return workspace.relativize(path).toString().replace('\\', '/');
    }

    /** Хэш содержимого; для символической ссылки — хэш её цели как текста (ссылку не разыменовываем). */
    private static String hash(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 недоступен", error);
        }
        if (Files.isSymbolicLink(path)) {
            return "link:" + HexFormat.of().formatHex(digest.digest(Files.readSymbolicLink(path).toString().getBytes(StandardCharsets.UTF_8)));
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return "special";
        if (Files.size(path) > MAX_HASH_BYTES) return "size:" + Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis();
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }

    /** Текст запроса доверия: что найдено и что это меняет. */
    static String describe(Status status) {
        boolean scriptsOnly = scriptsOnly(status);
        StringBuilder text = new StringBuilder(status.state() == State.CHANGED
            ? "Файлы, влияющие на поведение Kuleshato, изменились с момента одобрения:\n"
            : scriptsOnly
                ? "В проекте есть скрипты проверки — после правок Kuleshato может запускать их без подтверждения:\n"
                : "В проекте уже есть файлы, которые меняют поведение Kuleshato:\n");
        for (Change change : status.changes()) {
            text.append("  ").append(change.kind()).append(": ").append(change.path())
                .append(change.size() >= 0 ? " (" + change.size() + " байт)" : "").append(" — ").append(purpose(change.path())).append('\n');
        }
        List<String> effects = new ArrayList<>();
        if (status.changes().stream().anyMatch(change -> change.path().equals(".kuleshato/memory.md"))) effects.add("память проекта не загружается");
        if (status.changes().stream().anyMatch(change -> change.path().startsWith(".kuleshato/skills/"))) effects.add("навыки проекта не загружаются");
        if (status.changes().stream().anyMatch(change -> !change.path().startsWith(".kuleshato/"))) {
            effects.add("скрипты проверки запускаются только с подтверждением");
        }
        if (!effects.isEmpty()) text.append("Без одобрения ").append(String.join(", ", effects)).append(".\n");
        text.append(scriptsOnly
            ? "Разрешайте, если это ваш проект или вы просмотрели скрипты."
            : "Одобряйте, только если просмотрели эти файлы и доверяете их автору.");
        return text.toString();
    }

    /** Вопрос пользователю: при одних скриптах проверки речь об автозапуске, а не о «доверии к файлам». */
    static String question(Status status) {
        return scriptsOnly(status) ? "Разрешить автозапуск этих скриптов? [y/N] " : "Доверять этим файлам? [y/N] ";
    }

    private static boolean scriptsOnly(Status status) {
        return status.changes().stream().noneMatch(change -> change.path().startsWith(".kuleshato/"));
    }

    private static String purpose(String path) {
        if (path.equals(".kuleshato/memory.md")) return "память: подмешивается в каждый запрос";
        if (path.startsWith(".kuleshato/skills/")) return "навык: инструкции модели";
        if (path.equals(".kuleshato/config.json")) return "настройки";
        if (path.startsWith(".kuleshato/")) return "файл Kuleshato";
        return "скрипт проверки или сборки: запускается автоматически";
    }
}
