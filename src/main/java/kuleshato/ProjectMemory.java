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

    /** Новый текст памяти с уроком и его происхождением в разделе «Уроки»; файл не меняется. */
    static String withLesson(String current, String lesson, java.time.LocalDate date, Lessons.Provenance provenance) {
        String base = current == null ? TEMPLATE : current;
        String entry = "- " + lesson + " (" + date + ")\n" + provenanceLines(provenance);
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

    private static String provenanceLines(Lessons.Provenance provenance) {
        return "  - " + Lessons.Provenance.SOURCE + provenance.source()
            + "\n  - " + Lessons.Provenance.EVIDENCE + provenance.evidence();
    }

    /** Урок из раздела «Уроки»: номер строки правила (с 0), текст и есть ли у него доказательство ошибки. */
    record Lesson(int line, String text, boolean proven) {}

    static java.util.List<Lesson> lessons(String memory) {
        java.util.List<Lesson> result = new java.util.ArrayList<>();
        if (memory == null) return result;
        java.util.List<String> lines = memory.lines().toList();
        boolean inLessons = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("#")) {
                inLessons = line.strip().equals(LESSONS_HEADER);
                continue;
            }
            if (!inLessons || !line.startsWith("- ")) continue;
            boolean proven = false;
            for (int j = i + 1; j < lines.size() && lines.get(j).startsWith("  "); j++) {
                String detail = lines.get(j).strip();
                String prefix = "- " + Lessons.Provenance.EVIDENCE;
                if (detail.startsWith(prefix) && !detail.substring(prefix.length()).isBlank()) proven = true;
            }
            result.add(new Lesson(i, line.substring(2).strip(), proven));
        }
        return result;
    }

    /** Что из памяти передаётся модели; {@code unproven} — уроки без доказательства, которые не переданы. */
    record ModelMemory(String text, java.util.List<String> unproven) {}

    /**
     * Фильтр уроков: урок без строки «Доказательство» модели не передаётся (как замечание ревью без ссылки на строки).
     * Строки происхождения тоже вырезаются: модели нужно правило, а происхождение — человеку.
     */
    static ModelMemory forModel(String memory) {
        if (memory == null) return new ModelMemory(null, java.util.List.of());
        java.util.List<String> lines = memory.lines().toList();
        java.util.Map<Integer, Lesson> byLine = new java.util.HashMap<>();
        java.util.List<String> unproven = new java.util.ArrayList<>();
        for (Lesson lesson : lessons(memory)) {
            byLine.put(lesson.line(), lesson);
            if (!lesson.proven()) unproven.add(lesson.text());
        }
        StringBuilder kept = new StringBuilder();
        boolean inLesson = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Lesson lesson = byLine.get(i);
            if (lesson != null) {
                inLesson = true;
                if (lesson.proven()) kept.append(line).append('\n');
                continue;
            }
            if (inLesson && line.startsWith("  ")) continue;
            inLesson = false;
            kept.append(line).append('\n');
        }
        String text = kept.toString().strip();
        if (!unproven.isEmpty()) {
            text += "\n[Kuleshato: уроков без доказательства ошибки не передано: " + unproven.size() + ".]";
        }
        return new ModelMemory(text, java.util.List.copyOf(unproven));
    }

    /** Помечает урок, дописанный вручную, как подтверждённый пользователем; файл не меняется. */
    static String withManualProvenance(String current, Lesson lesson, java.time.LocalDate date) {
        java.util.List<String> lines = new java.util.ArrayList<>(current.lines().toList());
        if (lesson.line() >= lines.size() || !lines.get(lesson.line()).startsWith("- ")) return current;
        int insert = lesson.line() + 1;
        while (insert < lines.size() && lines.get(insert).startsWith("  ")) {
            if (lines.get(insert).strip().startsWith("- " + Lessons.Provenance.EVIDENCE)) lines.remove(insert);
            else insert++;
        }
        lines.add(insert, "  - " + Lessons.Provenance.EVIDENCE + "добавлен вручную, подтверждён пользователем " + date);
        return String.join("\n", lines) + "\n";
    }

    /** Уже есть такой урок (без учёта регистра, пробелов и даты)? */
    static boolean containsLesson(String current, String lesson) {
        if (current == null) return false;
        String needle = normalize(lesson);
        return current.lines().filter(line -> line.startsWith("- ")).anyMatch(line -> normalize(line.replaceFirst("^-\\s*", "")
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
