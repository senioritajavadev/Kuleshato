package kuleshato;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ревью Java-кода: приложение само выбирает строки (файл, метод или текущий diff), нумерует их ID,
 * а затем проверяет замечания модели — ссылки, категорию, приоритет и обоснование — и печатает их
 * в едином формате. Отсутствие замечаний никогда не выдаётся за доказательство корректности.
 */
final class CodeReview {
    enum Category {
        DEFECT("дефект"), REGRESSION("регрессия"), DUPLICATION("дублирование"), COMPLEXITY("сложность");

        final String label;

        Category(String label) {
            this.label = label;
        }

        boolean isDefect() {
            return this == DEFECT || this == REGRESSION;
        }
    }

    record ReviewFile(String path, List<Integer> lineNumbers, List<String> lines, Set<Integer> changed,
                      List<String> removed, Set<Integer> deletedNear, int totalLines) {
        boolean complete() {
            return lineNumbers.size() == totalLines;
        }

        /** Строка изменена или стоит рядом с удалёнными строками (регрессия может проявиться в соседней строке). */
        boolean touched(int line) {
            return changed.contains(line) || deletedNear.contains(line);
        }
    }

    record Target(String label, boolean diff, List<ReviewFile> files, boolean truncated) {
        int lineCount() {
            return files.stream().mapToInt(file -> file.lineNumbers().size()).sum();
        }
    }

    record Line(String id, ReviewFile file, int number, String text) {}

    record Finding(String priority, Category category, String title, List<Line> evidence,
                   String risk, String basis, String reproduction, String note) {}

    record Result(List<Finding> accepted, List<String> rejected, Target target) {}

    static final int MAX_CONTEXT_CHARS = 16_000;
    private static final long MAX_FILE_BYTES = 256 * 1024;
    private static final Set<String> IGNORED = Set.of(
        ".git", ".idea", ".kuleshato", ".vscode", ".gradle", "build", "coverage", "dist", "node_modules", "out", "target", "vendor");
    private static final Pattern TASTE = Pattern.compile(
        "(?iu)(именован|naming|стил[ья]|style|форматир|formatting|отступ|indent|пробел|whitespace|предпочтительн|prefer|"
            + "convention|соглашени[ея] об|читаемост|readability|var\\b|camelcase|эстетич)");
    private static final Pattern BACKTICK = Pattern.compile("`([^`]{2,80})`");

    private CodeReview() {}

    // ---------------------------------------------------------------- Выбор строк для ревью

    /** Файл или каталог (все .java внутри) относительно корня проекта. */
    static Target forPath(Path workspace, String requested) throws IOException {
        Path root = workspace.toRealPath();
        Path target = root.resolve(requested).normalize();
        if (!target.startsWith(root) || !Files.exists(target)) throw new IOException("Путь для ревью не найден в проекте: " + requested);
        target = target.toRealPath();
        if (!target.startsWith(root)) throw new IOException("Путь для ревью выходит за пределы проекта.");
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(target)) files.addAll(javaFiles(target));
        else files.add(target);
        if (files.isEmpty()) throw new IOException("В " + requested + " нет Java-файлов.");
        List<ReviewFile> result = new ArrayList<>();
        int budget = MAX_CONTEXT_CHARS;
        boolean truncated = false;
        for (Path file : files) {
            String[] lines = read(file);
            List<Integer> numbers = new ArrayList<>();
            for (int i = 1; i <= lines.length; i++) numbers.add(i);
            ReviewFile review = fitted(relative(root, file), lines, numbers, Set.of(), List.of(), Set.of(), budget);
            if (review == null) {
                truncated = true;
                break;
            }
            if (!review.complete()) truncated = true;
            budget -= cost(review);
            result.add(review);
        }
        return new Target(requested, false, result, truncated || result.size() < files.size());
    }

    /** Метод по спецификации {@code Класс#метод}; перегрузки включаются все. */
    static Target forMethod(Path workspace, String spec) throws IOException {
        Path root = workspace.toRealPath();
        int separator = spec.indexOf('#');
        if (separator <= 0 || separator == spec.length() - 1) throw new IOException("Укажите метод как Класс#метод.");
        String className = spec.substring(0, separator);
        String method = spec.substring(separator + 1);
        if (!className.matches("[A-Za-z_$][\\w$]*") || !method.matches("[A-Za-z_$][\\w$]*")) {
            throw new IOException("Некорректное имя класса или метода: " + spec);
        }
        List<Path> candidates = javaFiles(root).stream().filter(path -> path.getFileName().toString().equals(className + ".java")).toList();
        if (candidates.isEmpty()) throw new IOException("Класс " + className + " не найден в проекте.");
        if (candidates.size() > 1) {
            throw new IOException("Найдено несколько классов " + className + ": "
                + String.join(", ", candidates.stream().map(path -> relative(root, path)).toList()) + ". Укажите файл.");
        }
        Path file = candidates.get(0);
        String[] lines = read(file);
        List<int[]> spans = methodSpans(lines, method);
        if (spans.isEmpty()) throw new IOException("Метод " + method + " не найден в " + relative(root, file) + ".");
        List<Integer> numbers = new ArrayList<>();
        for (int[] span : spans) for (int line = span[0]; line <= span[1]; line++) numbers.add(line);
        ReviewFile review = fitted(relative(root, file), lines, numbers, Set.of(), List.of(), Set.of(), MAX_CONTEXT_CHARS);
        if (review == null) throw new IOException("Метод слишком большой для ревью.");
        return new Target(spec, false, List.of(review), review.lineNumbers().size() < numbers.size());
    }

    /** Текущие изменения Java-файлов относительно HEAD, включая новые неотслеживаемые файлы. */
    static Target forDiff(Path workspace) throws IOException {
        Path root = workspace.toRealPath();
        String diff = git(root, "diff", "--no-color", "--no-ext-diff", "--unified=3", "HEAD", "--", "*.java");
        String untracked = git(root, "ls-files", "--others", "--exclude-standard", "--", "*.java");
        Map<String, TreeSet<Integer>> changed = new LinkedHashMap<>();
        Map<String, TreeSet<Integer>> shown = new LinkedHashMap<>();
        Map<String, List<String>> removed = new LinkedHashMap<>();
        Map<String, TreeSet<Integer>> deletedNear = new LinkedHashMap<>();
        String current = null;
        int newLine = 0;
        for (String line : diff.split("\\R")) {
            if (line.startsWith("+++ ")) {
                current = line.equals("+++ /dev/null") ? null : line.substring(4).replaceFirst("^b/", "");
                if (current != null) {
                    changed.putIfAbsent(current, new TreeSet<>());
                    shown.putIfAbsent(current, new TreeSet<>());
                    removed.putIfAbsent(current, new ArrayList<>());
                    deletedNear.putIfAbsent(current, new TreeSet<>());
                }
            } else if (line.startsWith("@@")) {
                Matcher hunk = Pattern.compile("\\+(\\d+)").matcher(line);
                newLine = hunk.find() ? Integer.parseInt(hunk.group(1)) : 0;
            } else if (current != null && newLine > 0 && !line.startsWith("---") && !line.startsWith("\\")) {
                if (line.startsWith("+")) {
                    changed.get(current).add(newLine);
                    shown.get(current).add(newLine++);
                } else if (line.startsWith("-")) {
                    if (removed.get(current).size() < 60) removed.get(current).add(line.substring(1));
                    for (int near = Math.max(1, newLine - 2); near <= newLine + 1; near++) deletedNear.get(current).add(near);
                } else {
                    shown.get(current).add(newLine++);
                }
            }
        }
        for (String path : untracked.split("\\R")) {
            if (path.isBlank()) continue;
            String[] lines = read(root.resolve(path));
            TreeSet<Integer> all = new TreeSet<>();
            for (int i = 1; i <= lines.length; i++) all.add(i);
            changed.put(path, all);
            shown.put(path, new TreeSet<>(all));
            removed.put(path, List.of());
            deletedNear.put(path, new TreeSet<>());
        }
        if (changed.isEmpty()) throw new IOException("Изменений в Java-файлах относительно HEAD нет.");
        List<ReviewFile> files = new ArrayList<>();
        int budget = MAX_CONTEXT_CHARS;
        boolean truncated = false;
        for (String path : changed.keySet()) {
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) continue;
            String[] lines = read(file);
            ReviewFile review = fitted(path, lines, new ArrayList<>(shown.get(path)), changed.get(path), removed.get(path),
                deletedNear.get(path), budget);
            if (review == null) {
                truncated = true;
                break;
            }
            if (review.lineNumbers().size() < shown.get(path).size()) truncated = true;
            budget -= cost(review);
            files.add(review);
        }
        return new Target("текущий diff", true, files, truncated);
    }

    /** Строки объявления метода (вместе с javadoc и аннотациями) до закрывающей скобки. */
    static List<int[]> methodSpans(String[] lines, String method) {
        Pattern declaration = Pattern.compile("^\\s*(?:[\\w$<>\\[\\],.?@]+\\s+)+" + Pattern.quote(method) + "\\s*\\(");
        List<int[]> spans = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String stripped = line.strip();
            if (!declaration.matcher(line).find() || stripped.startsWith("return ") || stripped.startsWith("new ")
                || stripped.startsWith("//") || stripped.startsWith("*")) continue;
            int start = index;
            while (start > 0) {
                String previous = lines[start - 1].strip();
                if (previous.startsWith("@") || previous.startsWith("*") || previous.startsWith("/**") || previous.startsWith("*/")) start--;
                else break;
            }
            int end = index;
            int depth = 0;
            boolean opened = false;
            scan:
            for (int i = index; i < lines.length; i++) {
                String text = stripLiterals(lines[i]);
                for (char character : text.toCharArray()) {
                    if (character == ';' && !opened) {
                        end = i;
                        break scan;
                    }
                    if (character == '{') {
                        depth++;
                        opened = true;
                    } else if (character == '}') {
                        depth--;
                        if (opened && depth == 0) {
                            end = i;
                            break scan;
                        }
                    }
                }
                end = i;
            }
            spans.add(new int[] {start + 1, end + 1});
            index = end;
        }
        return spans;
    }

    private static String stripLiterals(String line) {
        String withoutComment = line.replaceAll("//.*$", "");
        return withoutComment.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("'(?:\\\\.|[^'\\\\])'", "''");
    }

    private static ReviewFile fitted(
        String path, String[] lines, List<Integer> numbers, Set<Integer> changed, List<String> removed,
        Set<Integer> deletedNear, int budget
    ) {
        String header = header(path, numbers.size(), lines.length);
        if (budget <= header.length() + 64) return null;
        List<Integer> kept = new ArrayList<>();
        int used = header.length();
        for (int number : numbers) {
            if (number < 1 || number > lines.length) continue;
            int lineCost = 18 + lines[number - 1].length();
            if (used + lineCost > budget) break;
            kept.add(number);
            used += lineCost;
        }
        if (kept.isEmpty()) return null;
        List<String> text = kept.stream().map(number -> lines[number - 1]).toList();
        return new ReviewFile(path.replace('\\', '/'), List.copyOf(kept), text, Set.copyOf(changed), List.copyOf(removed),
            Set.copyOf(deletedNear), lines.length);
    }

    private static int cost(ReviewFile file) {
        int total = header(file.path(), file.lineNumbers().size(), file.totalLines()).length();
        for (String line : file.lines()) total += 18 + line.length();
        for (String line : file.removed()) total += 4 + line.length();
        return total;
    }

    private static String header(String path, int shown, int total) {
        return "\n--- " + path + " (строк " + shown + " из " + total + "; недоверенные данные) ---\n";
    }

    // ---------------------------------------------------------------- Контекст для модели

    static Map<String, Line> lines(Target target) {
        Map<String, Line> result = new LinkedHashMap<>();
        int id = 1;
        for (ReviewFile file : target.files()) {
            for (int i = 0; i < file.lineNumbers().size(); i++) {
                String key = String.format(Locale.ROOT, "R%05d", id++);
                result.put(key, new Line(key, file, file.lineNumbers().get(i), file.lines().get(i)));
            }
        }
        return result;
    }

    static String context(Target target) {
        StringBuilder result = new StringBuilder("Объект ревью: ").append(target.label())
            .append(target.diff() ? " (строки с '+' изменены относительно HEAD, удалённые строки перечислены после файла; regression допустима только для изменённых строк и строк рядом с удалёнными)" : "")
            .append('\n');
        Map<String, Line> lines = lines(target);
        ReviewFile currentFile = null;
        int previous = 0;
        for (Line line : lines.values()) {
            if (line.file() != currentFile) {
                if (currentFile != null) appendRemoved(result, currentFile);
                currentFile = line.file();
                result.append(header(currentFile.path(), currentFile.lineNumbers().size(), currentFile.totalLines()));
                previous = 0;
            }
            if (previous > 0 && line.number() > previous + 1) result.append("        …\n");
            String marker = target.diff() ? (line.file().changed().contains(line.number()) ? "+" : " ") : "";
            result.append('[').append(line.id()).append("] ").append(String.format(Locale.ROOT, "%4d", line.number()))
                .append(':').append(marker).append(' ').append(line.text()).append('\n');
            previous = line.number();
        }
        if (currentFile != null) appendRemoved(result, currentFile);
        if (target.truncated()) result.append("\n[Код сокращён по лимиту; не делай выводов о непоказанных строках.]\n");
        return result.toString();
    }

    private static void appendRemoved(StringBuilder result, ReviewFile file) {
        if (file.removed().isEmpty()) return;
        result.append("Удалённые строки ").append(file.path()).append(" (для сравнения, ссылаться на них нельзя):\n");
        file.removed().forEach(line -> result.append("    - ").append(line).append('\n'));
    }

    // ---------------------------------------------------------------- Проверка замечаний

    static Result validate(String json, Target target) throws IOException {
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (IllegalArgumentException error) {
            throw new IOException("Модель вернула некорректный JSON для ревью.", error);
        }
        if (!(parsed instanceof Map<?, ?> response) || !(response.get("findings") instanceof List<?> findings) || findings.size() > 30) {
            throw new IOException("Модель вернула ревью в неверном формате.");
        }
        Map<String, Line> lines = lines(target);
        List<Finding> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object raw : findings) {
            if (!(raw instanceof Map<?, ?> finding)) {
                rejected.add("замечание не является объектом");
                continue;
            }
            String title = text(finding.get("title"));
            String risk = text(finding.get("risk"));
            String basis = text(finding.get("basis"));
            String reproduction = text(finding.get("reproduction"));
            String priority = text(finding.get("priority")).toUpperCase(Locale.ROOT);
            Category category = category(text(finding.get("category")));
            if (title.isEmpty() || risk.isEmpty() || basis.isEmpty() || reproduction.isEmpty()) {
                rejected.add("не хватает заголовка, риска, основания или условия воспроизведения");
                continue;
            }
            if (category == null) {
                rejected.add("категория не из списка (дефект, регрессия, дублирование, сложность)");
                continue;
            }
            if (!priority.matches("P[123]")) {
                rejected.add("приоритет не P1/P2/P3");
                continue;
            }
            List<Line> evidence = new ArrayList<>();
            boolean unknown = false;
            Object rawEvidence = finding.get("evidence");
            List<?> ids = rawEvidence instanceof List<?> list ? list : rawEvidence instanceof String single ? List.of(single) : List.of();
            for (Object id : ids) {
                Line line = id instanceof String key ? lines.get(key) : null;
                if (line == null) unknown = true;
                else if (!evidence.contains(line)) evidence.add(line);
            }
            if (unknown || evidence.isEmpty() || evidence.size() > 12) {
                rejected.add("ссылка на непереданную строку или нет ссылок");
                continue;
            }
            String note = "";
            if (category.isDefect() && TASTE.matcher(title + " " + risk).find()) {
                rejected.add("вкусовое замечание выдано за дефект: " + clip(title));
                continue;
            }
            String cited = String.join("\n", evidence.stream().map(Line::text).toList())
                + "\n" + String.join("\n", evidence.stream().map(line -> line.file().path()).toList());
            Matcher identifier = BACKTICK.matcher(title + " " + risk + " " + basis);
            String missing = null;
            while (identifier.find()) {
                if (!cited.contains(identifier.group(1).strip())) missing = identifier.group(1);
            }
            if (missing != null) {
                rejected.add("`" + clip(missing) + "` нет в процитированных строках");
                continue;
            }
            if (category == Category.REGRESSION
                && (!target.diff() || evidence.stream().noneMatch(line -> line.file().touched(line.number())))) {
                category = Category.DEFECT;
                note = "регрессия не привязана к изменённым строкам — считается дефектом";
            }
            if (!category.isDefect() && !priority.equals("P3")) {
                priority = "P3";
                note = "приоритет понижен до P3: дублирование и сложность не являются дефектами";
            }
            String key = category + "|" + evidence.get(0).id();
            if (!seen.add(key)) {
                rejected.add("повтор замечания к той же строке");
                continue;
            }
            accepted.add(new Finding(priority, category, title, List.copyOf(evidence), risk, basis, reproduction, note));
        }
        accepted.sort(java.util.Comparator
            .comparingInt((Finding finding) -> finding.category().isDefect() ? 0 : 1)
            .thenComparing(Finding::priority)
            .thenComparing(finding -> finding.evidence().get(0).id()));
        return new Result(List.copyOf(accepted), List.copyOf(rejected), target);
    }

    static String render(Result result) {
        Target target = result.target();
        StringBuilder text = new StringBuilder("Ревью: ").append(target.label()).append(" — просмотрено строк: ")
            .append(target.lineCount()).append(" в файлах: ").append(target.files().size())
            .append(target.truncated() ? " (код сокращён по лимиту)" : "").append('\n');
        List<Finding> defects = result.accepted().stream().filter(finding -> finding.category().isDefect()).toList();
        List<Finding> maintenance = result.accepted().stream().filter(finding -> !finding.category().isDefect()).toList();
        int number = 1;
        if (!defects.isEmpty()) {
            text.append("\nДефекты и регрессии:\n");
            for (Finding finding : defects) appendFinding(text, number++, finding);
        }
        if (!maintenance.isEmpty()) {
            text.append("\nДублирование и сложность:\n");
            for (Finding finding : maintenance) appendFinding(text, number++, finding);
        }
        if (result.accepted().isEmpty()) {
            text.append("\nПодтверждённых замечаний нет.\n");
        }
        if (!result.rejected().isEmpty()) {
            text.append("\nОтклонено замечаний модели: ").append(result.rejected().size()).append('\n');
            result.rejected().stream().limit(6).forEach(reason -> text.append("- ").append(reason).append('\n'));
        }
        text.append("\nРевью охватывает только показанные строки; отсутствие замечаний не доказывает отсутствие ошибок.");
        return text.toString();
    }

    private static void appendFinding(StringBuilder text, int number, Finding finding) {
        text.append(number).append(". [").append(finding.priority()).append("] ").append(finding.category().label)
            .append(" — ").append(references(finding.evidence())).append('\n')
            .append("   ").append(finding.title()).append('\n')
            .append("   Риск: ").append(finding.risk()).append('\n')
            .append("   Основание: ").append(finding.basis()).append('\n');
        Line first = finding.evidence().get(0);
        text.append("   Код: `").append(clip(first.text().strip())).append("`\n")
            .append("   Как воспроизвести: ").append(finding.reproduction()).append('\n');
        if (!finding.note().isEmpty()) text.append("   Примечание: ").append(finding.note()).append('\n');
    }

    /** Ссылки вида {@code src/A.java:9-10, src/B.java:3}: соседние строки одного файла объединяются. */
    static String references(List<Line> evidence) {
        Map<String, TreeSet<Integer>> byFile = new LinkedHashMap<>();
        for (Line line : evidence) byFile.computeIfAbsent(line.file().path(), path -> new TreeSet<>()).add(line.number());
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, TreeSet<Integer>> entry : byFile.entrySet()) {
            Integer start = null;
            Integer previous = null;
            List<String> ranges = new ArrayList<>();
            for (int number : entry.getValue()) {
                if (start == null) start = number;
                else if (number != previous + 1) {
                    ranges.add(start.equals(previous) ? String.valueOf(start) : start + "-" + previous);
                    start = number;
                }
                previous = number;
            }
            ranges.add(start.equals(previous) ? String.valueOf(start) : start + "-" + previous);
            parts.add(entry.getKey() + ":" + String.join(",", ranges));
        }
        return String.join(", ", parts);
    }

    // ---------------------------------------------------------------- Вспомогательное

    private static Category category(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "defect", "bug", "дефект" -> Category.DEFECT;
            case "regression", "регрессия" -> Category.REGRESSION;
            case "duplication", "дублирование" -> Category.DUPLICATION;
            case "complexity", "сложность" -> Category.COMPLEXITY;
            default -> null;
        };
    }

    private static String text(Object value) {
        return value instanceof String text ? text.strip() : "";
    }

    private static String clip(String value) {
        return value.length() <= 120 ? value : value.substring(0, 119) + "…";
    }

    private static List<Path> javaFiles(Path directory) throws IOException {
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path path, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!path.equals(directory) && IGNORED.contains(path.getFileName().toString())) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && path.toString().endsWith(".java")) result.add(path);
                return result.size() >= 2000 ? java.nio.file.FileVisitResult.TERMINATE : java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        result.sort(java.util.Comparator.comparing(Path::toString));
        return result;
    }

    private static String[] read(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Файл больше 256 КБ: " + file.getFileName());
        String content = Files.readString(file);
        String[] lines = content.split("\\R", -1);
        if (lines.length > 1 && lines[lines.length - 1].isEmpty()) return java.util.Arrays.copyOf(lines, lines.length - 1);
        return lines;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /** Запускает git только для чтения (diff, ls-files); вывод ограничен 2 МБ. */
    private static String git(Path root, String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString(), "-c", "core.quotepath=off"));
        command.addAll(List.of(arguments));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException error) {
            throw new IOException("Не удалось запустить git: " + error.getMessage(), error);
        }
        WorkspaceTools.CommandOutput output = WorkspaceTools.readLimited(process.getInputStream(), 2_000_000);
        try {
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git не ответил за 30 секунд.");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Ожидание git прервано.", error);
        }
        String text = new String(output.content(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("git " + arguments[0] + " завершился с ошибкой: " + text.strip().lines().findFirst().orElse(""));
        }
        return text;
    }
}
