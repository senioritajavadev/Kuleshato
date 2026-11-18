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
 *
 * <p>Файлы сборки ({@code pom.xml}, {@code build.gradle}…) тоже исполняемые данные: при {@code mvn test} выполняются
 * их плагины. Но их меняют постоянно, поэтому их изменение не выгружает память и навыки, а только выключает автозапуск
 * проверок, пока пользователь не посмотрит изменения. Для изменившегося файла Kuleshato показывает diff относительно
 * одобренной версии: её копия хранится рядом с {@code trust.json}, вне проекта.
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
        /** Память, навыки и скрипты проверки одобрены (изменения файлов сборки на это не влияют). */
        boolean trusted() {
            return state == State.NOTHING || state == State.TRUSTED || changes.stream().allMatch(change -> isBuildFile(change.path()));
        }

        /** Файлы сборки не менялись с одобрения — проверки Maven/Gradle можно запускать автоматически. */
        boolean buildTrusted() {
            return state == State.NOTHING || state == State.TRUSTED || changes.stream().noneMatch(change -> isBuildFile(change.path()));
        }

        boolean fullyTrusted() {
            return trusted() && buildTrusted();
        }
    }

    static final int MAX_FILES = 200;
    private static final long MAX_HASH_BYTES = 4L * 1024 * 1024;
    /** Скрипты проверки и обёртки сборки: их запускает автозапуск проверок ({@link CommandPolicy}). */
    private static final List<String> ROOT_SCRIPTS = List.of(
        "test.ps1", "test.sh", "test.bat", "test.cmd", "build.ps1", "build.sh", "build.bat", "build.cmd",
        "mvnw", "mvnw.cmd", "gradlew", "gradlew.bat",
        ".mvn/wrapper/maven-wrapper.properties", "gradle/wrapper/gradle-wrapper.properties");
    private static final java.util.Set<String> BUILD_FILES = java.util.Set.of(
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");
    private static final java.util.Set<String> SKIP = java.util.Set.of(
        ".git", ".idea", ".gradle", ".kuleshato", "build", "out", "target", "node_modules", "dist");
    private static final int MAX_BUILD_DEPTH = 4;
    private static final long MAX_COPY_BYTES = 256 * 1024;
    private static final int MAX_DIFF_LINES = 40;

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
        for (Path build : buildFiles(workspace)) result.put(relative(workspace, build), hash(build));
        return result;
    }

    /** Файлы сборки корня и модулей (до {@value #MAX_BUILD_DEPTH} уровней), без каталогов сборки и IDE. */
    private static List<Path> buildFiles(Path workspace) throws IOException {
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(workspace, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_BUILD_DEPTH,
            new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    return !dir.equals(workspace) && SKIP.contains(dir.getFileName().toString())
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (BUILD_FILES.contains(file.getFileName().toString()) && result.size() < 50) result.add(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException error) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        result.sort(null);
        return result;
    }

    static boolean isBuildFile(String relativePath) {
        String path = relativePath.replace('\\', '/');
        return BUILD_FILES.contains(path.substring(path.lastIndexOf('/') + 1));
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
        Map<String, String> current = fingerprint(root);
        all.put(key(root), new LinkedHashMap<>(current));
        writeStore(all);
        Path copies = copies(root);
        deleteTree(copies);
        for (String path : current.keySet()) copy(root, path);
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
        copy(root, path);
    }

    void revoke(Path root) throws IOException {
        Map<String, Object> all = readStore();
        if (all.remove(key(root)) != null) writeStore(all);
        deleteTree(copies(root));
    }

    /**
     * Что изменилось в файле с момента одобрения — diff относительно сохранённой копии; для нового файла — его начало.
     * {@code null}, если копии нет или файл не текстовый.
     */
    String diff(Path root, Change change) {
        try {
            Path current = root.toRealPath().resolve(change.path());
            if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS) || Files.size(current) > MAX_COPY_BYTES) return null;
            String now = text(current);
            if (now == null) return null;
            String before = "";
            if (change.kind().equals("изменён")) {
                Path saved = copyPath(root, change.path());
                if (saved == null || !Files.isRegularFile(saved)) return null;
                before = text(saved);
                if (before == null) return null;
            }
            String diff = WorkspaceTools.formatDiff(change.path(), before, now);
            List<String> lines = diff.lines().toList();
            if (lines.size() <= MAX_DIFF_LINES) return diff;
            return String.join("\n", lines.subList(0, MAX_DIFF_LINES)) + "\n … ещё " + (lines.size() - MAX_DIFF_LINES) + " строк";
        } catch (IOException error) {
            return null;
        }
    }

    private static String text(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        for (byte value : bytes) if (value == 0) return null;
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Каталог копий одобренных файлов этого проекта: рядом с {@code trust.json}, по хэшу пути проекта. */
    private Path copies(Path root) throws IOException {
        String id = HexFormat.of().formatHex(sha256().digest(key(root).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        return store.resolveSibling("trusted").resolve(id);
    }

    private Path copyPath(Path root, String relativePath) throws IOException {
        Path base = copies(root);
        Path target = base.resolve(relativePath).normalize();
        return target.startsWith(base) ? target : null;
    }

    private void copy(Path root, String relativePath) throws IOException {
        Path source = root.toRealPath().resolve(relativePath);
        Path target = copyPath(root, relativePath);
        if (target == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > MAX_COPY_BYTES) return;
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 недоступен", error);
        }
    }

    /** Относится ли путь к файлам, которым нужно доверие (для обновления хэша после собственной записи). */
    static boolean isTracked(String relativePath) {
        String path = relativePath.replace('\\', '/');
        return path.startsWith(".kuleshato/") || ROOT_SCRIPTS.contains(path) || isBuildFile(path);
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
        return describe(status, false);
    }

    /** {@code verbose} — с размерами файлов. */
    static String describe(Status status, boolean verbose) {
        boolean scriptsOnly = scriptsOnly(status);
        boolean buildOnly = status.changes().stream().allMatch(change -> isBuildFile(change.path()));
        StringBuilder text = new StringBuilder(status.state() == State.CHANGED
            ? buildOnly
                ? "Файлы сборки изменились с момента одобрения — проверка (например, mvn test) выполнит их плагины:\n"
                : "Файлы, влияющие на поведение Kuleshato, изменились с момента одобрения:\n"
            : buildOnly
                ? "В проекте есть файлы сборки — после правок Kuleshato запускает проверку, и она выполняет плагины из них:\n"
                : scriptsOnly
                    ? "В проекте есть скрипты проверки — после правок Kuleshato может запускать их без подтверждения:\n"
                    : "В проекте уже есть файлы, которые меняют поведение Kuleshato:\n");
        for (Change change : status.changes()) {
            text.append("  ").append(change.kind()).append(": ").append(change.path())
                .append(verbose && change.size() >= 0 ? " (" + change.size() + " байт)" : "").append(" — ").append(purpose(change.path())).append('\n');
        }
        List<String> effects = new ArrayList<>();
        if (status.changes().stream().anyMatch(change -> change.path().equals(".kuleshato/memory.md"))) effects.add("память проекта не загружается");
        if (status.changes().stream().anyMatch(change -> change.path().startsWith(".kuleshato/skills/"))) effects.add("навыки проекта не загружаются");
        if (status.changes().stream().anyMatch(change -> !change.path().startsWith(".kuleshato/"))) {
            effects.add("проверки запускаются только с подтверждением");
        }
        if (!effects.isEmpty()) text.append("Без одобрения ").append(String.join(", ", effects)).append(".\n");
        text.append(scriptsOnly
            ? "Разрешайте, если это ваш проект или вы просмотрели скрипты."
            : "Одобряйте, только если просмотрели эти файлы и доверяете их автору.");
        return text.toString();
    }

    /** Вопрос пользователю: при одних скриптах проверки речь об автозапуске, а не о «доверии к файлам». */
    static String question(Status status) {
        if (status.changes().stream().allMatch(change -> isBuildFile(change.path()))) {
            return "Разрешить автозапуск проверок в этом проекте? [y/N] ";
        }
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
        if (isBuildFile(path)) return "файл сборки: при проверке выполняются его плагины";
        return "скрипт проверки или сборки: запускается автоматически";
    }
}
