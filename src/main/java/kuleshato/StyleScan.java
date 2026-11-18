package kuleshato;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Единообразие оформления Java-кода — детерминированно, по всем файлам и без модели.
 *
 * <p>Вопрос «проверь код на консистентность и единый стиль» небольшая модель в цикле инструментов решает плохо:
 * читает файлы по одному, тратит окно контекста и не видит проект целиком. Механические правила (отступы, переводы
 * строк, длина строк, импорты, скобки) приложение проверяет само: для каждого правила — что принято в большинстве
 * файлов (или объявлено в {@code .editorconfig}) и где отклонения с {@code файл:строка}. Смысловую согласованность
 * так не проверить; для неё — {@code /review}.
 */
final class StyleScan {
    private static final Pattern QUESTION = Pattern.compile(
        "(?iu)(стил[ья]|стилю|стилем|консистентн|единообраз|форматир|оформлени[ея] кода|code ?style|formatting|consisten|checkstyle)");
    private static final Set<String> SKIP = Set.of(".git", ".idea", ".gradle", ".mvn", "build", "out", "target", "node_modules", "dist");
    private static final Pattern WILDCARD_IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?[\\w.]+\\.\\*\\s*;");
    static final int MAX_FILES = 2000;
    private static final long MAX_FILE_BYTES = 512 * 1024;
    private static final int EXAMPLES = 3;

    private StyleScan() {}

    /** Вопрос об оформлении кода, а не о поведении. */
    static boolean isStyleQuestion(String prompt) {
        return !prompt.startsWith("/") && QUESTION.matcher(prompt).find();
    }

    /** Правило: сколько файлов следуют каждому варианту и примеры строк для отклонений. */
    private static final class Rule {
        final String title;
        final Map<String, List<String>> variants = new LinkedHashMap<>();
        String declared;

        Rule(String title) {
            this.title = title;
        }

        void add(String variant, String where) {
            variants.computeIfAbsent(variant, ignored -> new ArrayList<>()).add(where);
        }

        String expected() {
            if (declared != null) return declared;
            return variants.entrySet().stream().max(Map.Entry.comparingByValue(java.util.Comparator.comparingInt(List::size)))
                .map(Map.Entry::getKey).orElse(null);
        }

        boolean consistent() {
            String expected = expected();
            return variants.keySet().stream().allMatch(variant -> variant.equals(expected));
        }

        String render() {
            StringBuilder text = new StringBuilder(title).append(": ");
            String expected = expected();
            if (expected == null) return text.append("нет данных").toString();
            text.append(expected).append(" — ").append(count(variants.getOrDefault(expected, List.of()).size()))
                .append(declared != null ? " (объявлено в .editorconfig)" : "");
            if (consistent()) return text.append(" ✓").toString();
            for (Map.Entry<String, List<String>> variant : variants.entrySet()) {
                if (variant.getKey().equals(expected)) continue;
                text.append("\n    отклонение: ").append(variant.getKey()).append(" — ").append(count(variant.getValue().size()))
                    .append(": ").append(String.join(", ", variant.getValue().stream().limit(EXAMPLES).toList()))
                    .append(variant.getValue().size() > EXAMPLES ? " …" : "");
            }
            return text.toString();
        }
    }

    /** Нарушения правила «так быть не должно» (без выбора большинства): файлы и первые строки. */
    private static final class Issue {
        final String title;
        final List<String> places = new ArrayList<>();
        int lines;

        Issue(String title) {
            this.title = title;
        }

        String render() {
            if (places.isEmpty()) return title + ": нет ✓";
            return title + ": " + count(places.size()) + (lines > places.size() ? ", строк: " + lines : "") + " — "
                + String.join(", ", places.stream().limit(EXAMPLES).toList()) + (places.size() > EXAMPLES ? " …" : "");
        }
    }

    static String report(Path root) throws IOException {
        Path workspace = root.toRealPath();
        List<Path> files = javaFiles(workspace);
        if (files.isEmpty()) return "Java-файлы для проверки оформления не найдены.";
        Map<String, String> editorConfig = editorConfig(workspace);
        int maxLength = parseInt(editorConfig.get("max_line_length"), 120);

        Rule indent = new Rule("Отступы");
        Rule endings = new Rule("Переводы строк");
        Rule braces = new Rule("Открывающая фигурная скобка");
        Issue trailing = new Issue("Пробелы в конце строк");
        Issue longLines = new Issue("Строки длиннее " + maxLength + " символов");
        Issue wildcard = new Issue("Импорты со *");
        Issue finalNewline = new Issue("Нет перевода строки в конце файла");
        Issue bom = new Issue("BOM в начале файла");
        if ("tab".equals(editorConfig.get("indent_style"))) indent.declared = "табуляция";
        else if ("space".equals(editorConfig.get("indent_style"))) {
            indent.declared = "пробелы по " + parseInt(editorConfig.get("indent_size"), 4);
        }
        if ("lf".equals(editorConfig.get("end_of_line"))) endings.declared = "LF";
        if ("crlf".equals(editorConfig.get("end_of_line"))) endings.declared = "CRLF";

        int scanned = 0;
        for (Path file : files) {
            if (Files.size(file) > MAX_FILE_BYTES) continue;
            byte[] bytes = Files.readAllBytes(file);
            String path = workspace.relativize(file).toString().replace('\\', '/');
            scanned++;
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                bom.places.add(path);
                text = text.substring(1);
            }
            if (!text.isEmpty() && !text.endsWith("\n")) finalNewline.places.add(path);
            int crlf = count(text, "\r\n");
            int lf = count(text, "\n") - crlf;
            if (crlf > 0 && lf > 0) endings.add("смешанные", path);
            else if (crlf > 0) endings.add("CRLF", path);
            else if (lf > 0) endings.add("LF", path);

            String[] lines = text.split("\\r?\\n", -1);
            int tabs = 0, spaces = 0, by4 = 0, by2 = 0, other = 0, sameLine = 0, ownLine = 0;
            String firstTab = null, firstSpace = null, firstOddIndent = null, firstOwnLine = null, firstSameLine = null;
            boolean inBlockComment = false;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String where = path + ":" + (i + 1);
                String stripped = line.strip();
                boolean comment = inBlockComment || stripped.startsWith("*") || stripped.startsWith("/*") || stripped.startsWith("//");
                if (stripped.startsWith("/*") && !stripped.contains("*/")) inBlockComment = true;
                else if (inBlockComment && stripped.contains("*/")) inBlockComment = false;
                if (!line.isEmpty() && Character.isWhitespace(line.charAt(line.length() - 1))) {
                    if (trailing.places.isEmpty() || !trailing.places.get(trailing.places.size() - 1).startsWith(path + ":")) {
                        trailing.places.add(where);
                    }
                    trailing.lines++;
                }
                if (line.length() > maxLength && !stripped.startsWith("import ") && !stripped.startsWith("package ")) {
                    if (longLines.places.isEmpty() || !longLines.places.get(longLines.places.size() - 1).startsWith(path + ":")) {
                        longLines.places.add(where);
                    }
                    longLines.lines++;
                }
                if (WILDCARD_IMPORT.matcher(line).find()) {
                    wildcard.places.add(where);
                    wildcard.lines++;
                }
                if (stripped.isEmpty() || comment) continue;
                if (line.startsWith("\t")) {
                    tabs++;
                    if (firstTab == null) firstTab = where;
                } else if (line.startsWith(" ")) {
                    spaces++;
                    if (firstSpace == null) firstSpace = where;
                    int width = line.length() - line.stripLeading().length();
                    if (line.stripLeading().startsWith(".") || line.stripLeading().startsWith("+")
                        || line.stripLeading().startsWith("&&") || line.stripLeading().startsWith("||")) {
                        // Продолжение выражения выравнивают по-разному; ширину отступа по нему не определяем.
                    } else if (width % 4 == 0) by4++;
                    else if (width % 2 == 0) by2++;
                    else {
                        other++;
                        if (firstOddIndent == null) firstOddIndent = where;
                    }
                }
                if (stripped.equals("{")) {
                    ownLine++;
                    if (firstOwnLine == null) firstOwnLine = where;
                } else if (stripped.endsWith("{") && !stripped.startsWith("}")) {
                    sameLine++;
                    if (firstSameLine == null) firstSameLine = where;
                } else if (stripped.endsWith("{")) {
                    sameLine++;
                }
            }
            if (tabs > 0 && spaces > 0) indent.add("смешанные табуляция и пробелы", tabs > spaces ? firstSpace : firstTab);
            else if (tabs > 0) indent.add("табуляция", path);
            else if (spaces > 0) {
                int total = by4 + by2 + other;
                if (total == 0 || by4 >= 0.9 * total) indent.add("пробелы по 4", path);
                else if (by4 + by2 >= 0.9 * total) indent.add("пробелы по 2", path);
                else indent.add("пробелы разной ширины", firstOddIndent == null ? path : firstOddIndent);
            }
            if (ownLine > 0 && sameLine > 0) braces.add("и на той же, и на отдельной строке",
                ownLine < sameLine ? firstOwnLine : firstSameLine);
            else if (ownLine > 0) braces.add("на отдельной строке", path);
            else if (sameLine > 0) braces.add("на той же строке", path);
        }

        List<Rule> rules = List.of(indent, endings, braces);
        List<Issue> issues = List.of(trailing, longLines, wildcard, finalNewline, bom);
        long consistent = rules.stream().filter(Rule::consistent).count() + issues.stream().filter(issue -> issue.places.isEmpty()).count();
        StringBuilder result = new StringBuilder("Оформление Java-кода: проверено файлов — ").append(scanned)
            .append(" (приложением, по всем файлам, без модели")
            .append(editorConfig.isEmpty() ? "; .editorconfig нет — эталон — вариант большинства файлов" : "; учтён .editorconfig")
            .append(").\n");
        for (Rule rule : rules) result.append("\n- ").append(rule.render());
        for (Issue issue : issues) result.append("\n- ").append(issue.render());
        result.append("\n\nЕдинообразно: ").append(consistent).append(" из ").append(rules.size() + issues.size()).append(" правил.");
        if (files.size() > MAX_FILES) result.append(" Проверены первые ").append(MAX_FILES).append(" файлов.");
        result.append("\nЭто механическая проверка оформления. Смысловую согласованность (именование, обработку ошибок, "
            + "дублирование) так не проверить — для неё: /review <файл|каталог>. Исправить отклонения в конкретном файле "
            + "можно просьбой, например: «убери пробелы в конце строк в src/…/Main.java».");
        return result.toString();
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!dir.equals(root) && (SKIP.contains(dir.getFileName().toString()) || Files.isSymbolicLink(dir))) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")) result.add(file);
                return result.size() >= MAX_FILES ? java.nio.file.FileVisitResult.TERMINATE : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException error) {
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        result.sort(null);
        return result;
    }

    /** Ключи из разделов {@code [*]} и разделов, относящихся к *.java, корневого {@code .editorconfig}. */
    static Map<String, String> editorConfig(Path root) throws IOException {
        Path file = root.resolve(".editorconfig");
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 64 * 1024) return result;
        boolean applies = false;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                String glob = line.substring(1, line.length() - 1);
                applies = glob.equals("*") || glob.contains("java");
                continue;
            }
            int equals = line.indexOf('=');
            if (!applies || equals < 0) continue;
            result.put(line.substring(0, equals).strip().toLowerCase(Locale.ROOT), line.substring(equals + 1).strip().toLowerCase(Locale.ROOT));
        }
        result.keySet().retainAll(Set.of("indent_style", "indent_size", "end_of_line", "max_line_length"));
        return result;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) count++;
        return count;
    }

    private static String count(int files) {
        int mod10 = files % 10, mod100 = files % 100;
        String word = mod10 == 1 && mod100 != 11 ? "файл" : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? "файла" : "файлов";
        return files + " " + word;
    }
}
