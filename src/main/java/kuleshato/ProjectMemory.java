package kuleshato;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class ProjectMemory {
    static final Path RELATIVE_PATH = Path.of(".kuleshato", "memory.md");
    static final int MAX_BYTES = 32 * 1024;
    private static final String TEMPLATE = """
        # Память проекта Kuleshato

        Добавьте сюда постоянные сведения о Java-проекте:

        - назначение проекта и его основные части;
        - команды сборки и тестирования (Maven или Gradle);
        - версию Java, соглашения по коду и важные ограничения;
        - решения и детали, которые важно помнить между сессиями.

        Не добавляйте секреты, пароли, токены или приватные ключи.
        """;

    private ProjectMemory() {}

    static String read(Path root) throws IOException {
        Path path = ProjectConfig.safePath(root, RELATIVE_PATH);
        if (!Files.exists(path)) return null;
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) {
            throw new IOException("Файл памяти проекта превышает 32 КБ или не является обычным файлом.");
        }
        String content = Files.readString(path);
        if (content.indexOf('\0') >= 0) throw new IOException("Память проекта не является текстовым файлом.");
        return content.isBlank() ? null : content.trim();
    }

    /** Доля окна контекста, которую может занимать память в системной подсказке. */
    static final double MAX_CONTEXT_SHARE = 0.15;
    static final String LESSONS_HEADER = "## Уроки";

    /** Память для системной подсказки: целиком или явно помеченное начало, если она не помещается в бюджет. */
    record PromptMemory(String text, boolean truncated, int shownChars, int totalChars) {}

    /** Бюджет в символах; приложение оценивает токены как символы / 3. */
    static int budgetChars(int contextWindow) {
        return (int) (contextWindow * MAX_CONTEXT_SHARE * 3);
    }

    static PromptMemory forPrompt(String memory, int contextWindow) {
        int budget = budgetChars(contextWindow);
        if (memory.length() <= budget) return new PromptMemory(memory, false, memory.length(), memory.length());
        int cut = memory.lastIndexOf('\n', budget);
        if (cut < budget / 2) cut = budget;
        String shown = memory.substring(0, cut).stripTrailing();
        return new PromptMemory(shown + "\n[Память сокращена приложением: показано " + shown.length() + " из "
            + memory.length() + " символов; остальное модели не передано.]", true, shown.length(), memory.length());
    }

    static String warning(PromptMemory prompt, int contextWindow) {
        return "Память проекта (" + prompt.totalChars() + " символов) больше бюджета " + budgetChars(contextWindow)
            + " символов (" + Math.round(MAX_CONTEXT_SHARE * 100) + "% окна контекста " + contextWindow
            + "); модели передано только начало. Сократите .kuleshato/memory.md или увеличьте contextWindow.";
    }

    /** Содержимое файла памяти как есть (без обрезки пробелов) или {@code null}, если файла нет. */
    static String readRaw(Path root) throws IOException {
        Path path = ProjectConfig.safePath(root, RELATIVE_PATH);
        if (!Files.exists(path)) return null;
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) {
            throw new IOException("Файл памяти проекта превышает 32 КБ или не является обычным файлом.");
        }
        return Files.readString(path);
    }

    /** Новый текст памяти с уроком в разделе «Уроки»; файл не меняется. */
    static String withLesson(String current, String lesson, java.time.LocalDate date) {
        String base = current == null ? TEMPLATE : current;
        String entry = "- " + lesson + " (" + date + ")";
        java.util.List<String> lines = new java.util.ArrayList<>(base.lines().toList());
        int header = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).strip().equals(LESSONS_HEADER)) header = i;
        if (header < 0) {
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
            lines.addAll(java.util.List.of("", LESSONS_HEADER, "", entry));
            return String.join("\n", lines) + "\n";
        }
        int end = header + 1;
        while (end < lines.size() && !lines.get(end).startsWith("## ")) end++;
        int insert = end;
        while (insert > header + 1 && lines.get(insert - 1).isBlank()) insert--;
        if (insert == header + 1) lines.add(insert++, "");
        lines.add(insert, entry);
        return String.join("\n", lines) + "\n";
    }

    /** Уже есть такой урок (без учёта регистра, пробелов и даты)? */
    static boolean containsLesson(String current, String lesson) {
        if (current == null) return false;
        String needle = normalize(lesson);
        return current.lines().anyMatch(line -> normalize(line.replaceFirst("^-\\s*", "")
            .replaceFirst("\\s*\\(\\d{4}-\\d{2}-\\d{2}\\)\\s*$", "")).equals(needle));
    }

    private static String normalize(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /** Записывает память, только если файл не изменился с момента чтения. */
    static void write(Path root, String expected, String content) throws IOException {
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IOException("Память проекта превысит 32 КБ; урок не записан.");
        }
        Path path = ProjectConfig.safePath(root, RELATIVE_PATH);
        String actual = readRaw(root);
        if (!java.util.Objects.equals(actual, expected)) throw new IOException("Файл памяти изменился во время подтверждения; урок не записан.");
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    static String initialize(Path root) throws IOException {
        Path path = ProjectConfig.safePath(root, RELATIVE_PATH);
        Files.createDirectories(path.getParent());
        try {
            Files.writeString(path, TEMPLATE, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException error) {
            throw new IOException("Память проекта уже существует; файл не перезаписан.", error);
        }
        return TEMPLATE.trim();
    }
}
