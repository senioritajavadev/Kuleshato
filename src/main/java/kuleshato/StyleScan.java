package kuleshato;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Единообразие оформления Java-кода — детерминированно, по всем файлам и без модели.
 *
 * <p>Вопрос «проверь код на консистентность и единый стиль» небольшая модель в цикле инструментов решает плохо:
 * читает файлы по одному, тратит окно контекста и не видит проект целиком. Механические правила (отступы, переводы
 * строк, длина строк, импорты, скобки) приложение проверяет само.
 *
 * <p>Отчёт написан для начинающего: сначала то, что стоит поправить, — с объяснением, почему это важно, и примерами;
 * затем одна строка о том, что в порядке; в конце — что можно попросить. Разбивка по вариантам — в подробном режиме.
 * Переводы строк не считаются проблемой, если их при коммите выравнивает Git.
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

    /** Правило с выбором варианта: сколько файлов следуют каждому варианту и где отклонения. */
    private static final class Rule {
        final String name;
        final String technical;
        final Map<String, List<String>> variants = new LinkedHashMap<>();
        String declared;

        Rule(String name, String technical) {
            this.name = name;
            this.technical = technical;
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

        List<String> deviations() {
            List<String> result = new ArrayList<>();
            variants.forEach((variant, places) -> {
                if (!variant.equals(expected())) result.addAll(places);
            });
            return result;
        }

        /** «в большинстве файлов — 4 пробела, а в 1 файле — табуляция». */
        String plain(Function<String, String> human) {
            String expected = expected();
            StringBuilder text = new StringBuilder(declared != null
                ? "в .editorconfig указано — " + human.apply(expected)
                : "в большинстве файлов — " + human.apply(expected));
            List<String> others = new ArrayList<>();
            variants.forEach((variant, places) -> {
                if (!variant.equals(expected)) others.add("в " + inFiles(places.size()) + " — " + human.apply(variant));
            });
            return text.append(", а ").append(String.join(", ", others)).toString();
        }

        String technicalLine() {
            String expected = expected();
            if (expected == null) return technical + ": нет данных";
            StringBuilder text = new StringBuilder(technical).append(": ").append(expected).append(" — ")
                .append(files(variants.getOrDefault(expected, List.of()).size()))
                .append(declared != null ? " (объявлено в .editorconfig)" : "");
            variants.forEach((variant, places) -> {
                if (!variant.equals(expected)) {
                    text.append("; ").append(variant).append(" — ").append(files(places.size())).append(": ")
                        .append(String.join(", ", places.stream().limit(EXAMPLES).toList())).append(places.size() > EXAMPLES ? " …" : "");
                }
            });
            return text.toString();
        }
    }

    /** То, чего быть не должно: файлы, первые строки и общее число строк. */
    private static final class Issue {
        final String name;
        final String technical;
        final List<String> places = new ArrayList<>();
        int lines;

        Issue(String name, String technical) {
            this.name = name;
            this.technical = technical;
        }

        void add(String path, String where) {
            if (places.isEmpty() || !places.get(places.size() - 1).startsWith(path + ":")) places.add(where);
            lines++;
        }

        String technicalLine() {
            if (places.isEmpty()) return technical + ": нет";
            return technical + ": " + files(places.size()) + ", строк: " + lines + " — "
                + String.join(", ", places.stream().limit(EXAMPLES).toList()) + (places.size() > EXAMPLES ? " …" : "");
        }
    }

    static String report(Path root) throws IOException {
        return report(root, gitNormalizesLineEndings(root), false);
    }

    static String report(Path root, boolean verbose) throws IOException {
        return report(root, gitNormalizesLineEndings(root), verbose);
    }

    /** {@code gitNormalizes} — Git сам выравнивает переводы строк, поэтому их различие в рабочей копии не проблема. */
    static String report(Path root, boolean gitNormalizes, boolean verbose) throws IOException {
        Path workspace = root.toRealPath();
        List<Path> files = javaFiles(workspace);
        if (files.isEmpty()) return "Java-файлов для проверки оформления не нашлось.";
        Map<String, String> editorConfig = editorConfig(workspace);
        int maxLength = parseInt(editorConfig.get("max_line_length"), 120);

        Rule indent = new Rule("отступы", "Отступы");
        Rule endings = new Rule("переводы строк", "Переводы строк");
        Rule braces = new Rule("фигурные скобки", "Открывающая фигурная скобка");
        Issue trailing = new Issue("пробелы в конце строк", "Пробелы в конце строк");
        Issue longLines = new Issue("длина строк", "Строки длиннее " + maxLength + " символов");
        Issue wildcard = new Issue("импорты", "Импорты со *");
        Issue finalNewline = new Issue("конец файла", "Нет перевода строки в конце файла");
        Issue bom = new Issue("кодировка", "BOM в начале файла");
        if ("tab".equals(editorConfig.get("indent_style"))) indent.declared = "табуляция";
        else if ("space".equals(editorConfig.get("indent_style"))) {
            indent.declared = "пробелы по " + parseInt(editorConfig.get("indent_size"), 4);
        }
        if ("lf".equals(editorConfig.get("end_of_line"))) endings.declared = "LF";
        if ("crlf".equals(editorConfig.get("end_of_line"))) endings.declared = "CRLF";

        Map<String, Integer> names = new HashMap<>();
        for (Path file : files) names.merge(file.getFileName().toString(), 1, Integer::sum);
        Function<String, String> shortName = place -> {
            String path = place.contains(":") ? place.substring(0, place.lastIndexOf(':')) : place;
            String name = path.substring(path.lastIndexOf('/') + 1);
            return names.getOrDefault(name, 0) == 1 ? name + place.substring(path.length()) : place;
        };

        int scanned = 0;
        for (Path file : files) {
            if (Files.size(file) > MAX_FILE_BYTES) continue;
            byte[] bytes = Files.readAllBytes(file);
            String path = workspace.relativize(file).toString().replace('\\', '/');
            scanned++;
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                bom.add(path, path);
                text = text.substring(1);
            }
            if (!text.isEmpty() && !text.endsWith("\n")) finalNewline.add(path, path);
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
                if (!line.isEmpty() && Character.isWhitespace(line.charAt(line.length() - 1))) trailing.add(path, where);
                if (line.length() > maxLength && !stripped.startsWith("import ") && !stripped.startsWith("package ")) {
                    longLines.add(path, where);
                }
                if (WILDCARD_IMPORT.matcher(line).find()) wildcard.add(path, where);
                if (stripped.isEmpty() || comment) continue;
                if (line.startsWith("\t")) {
                    tabs++;
                    if (firstTab == null) firstTab = where;
                } else if (line.startsWith(" ")) {
                    spaces++;
                    if (firstSpace == null) firstSpace = where;
                    int width = line.length() - line.stripLeading().length();
                    String rest = line.stripLeading();
                    if (rest.startsWith(".") || rest.startsWith("+") || rest.startsWith("&&") || rest.startsWith("||")) {
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
                } else if (stripped.endsWith("{")) {
                    sameLine++;
                    if (firstSameLine == null && !stripped.startsWith("}")) firstSameLine = where;
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
            if (ownLine > 0 && sameLine > 0) braces.add("и на той же, и на отдельной строке", ownLine < sameLine ? firstOwnLine : firstSameLine);
            else if (ownLine > 0) braces.add("на отдельной строке", path);
            else if (sameLine > 0) braces.add("на той же строке", path);
        }

        List<String> fix = new ArrayList<>();
        List<String> fine = new ArrayList<>();
        if (indent.consistent()) fine.add(indent.name);
        else fix.add("Разные отступы: " + indent.plain(StyleScan::humanIndent) + ". В разных редакторах такой код съезжает."
            + examples(indent.deviations(), shortName));
        boolean endingsIgnored = !endings.consistent() && gitNormalizes && endings.declared == null;
        if (endings.consistent()) fine.add(endings.name);
        else if (endingsIgnored) fine.add("переводы строк (их выравнивает Git)");
        else fix.add("Разные переводы строк: " + endings.plain(StyleScan::humanEnding)
            + ". Git может показывать такие файлы изменёнными целиком." + examples(endings.deviations(), shortName));
        if (braces.consistent()) fine.add(braces.name);
        else fix.add("Фигурные скобки стоят по-разному: " + braces.plain(Function.identity()) + "."
            + examples(braces.deviations(), shortName));
        issue(fix, fine, trailing, "Лишние пробелы в конце строк: " + lineCount(trailing) + ". Их не видно, но они попадают "
            + "в изменения Git и мешают сравнению версий.", shortName);
        issue(fix, fine, longLines, "Длинные строки: " + lineCount(longLines) + " длиннее " + maxLength + " символов. Их неудобно "
            + "читать — приходится прокручивать вбок. Это не ошибка, но такие строки лучше переносить.", shortName);
        issue(fix, fine, wildcard, "Импорты со звёздочкой (вроде import java.util.*): " + lineCount(wildcard) + ". По ним не видно, "
            + "какие классы на самом деле используются.", shortName);
        issue(fix, fine, finalNewline, "Нет пустой строки в конце файла: " + inFilesCount(finalNewline) + ". Git отмечает это "
            + "в изменениях как «No newline at end of file».", shortName);
        issue(fix, fine, bom, "Невидимый символ BOM в начале файла: " + inFilesCount(bom) + ". Некоторые инструменты сборки "
            + "на нём спотыкаются.", shortName);

        StringBuilder result = new StringBuilder("Проверено оформление " + plural(scanned, "Java-файла", "Java-файлов", "Java-файлов") + ".\n");
        if (fix.isEmpty()) {
            result.append("\nОформление единообразное — замечаний нет.");
        } else {
            result.append("\nЧто стоит поправить:");
            for (int i = 0; i < fix.size(); i++) result.append("\n").append(i + 1).append(". ").append(fix.get(i));
        }
        if (!fine.isEmpty()) result.append("\n\nВ порядке: ").append(String.join(", ", fine)).append('.');
        if (files.size() >= MAX_FILES) result.append("\nПроверены первые ").append(MAX_FILES).append(" файлов.");
        result.append("\n\n");
        if (!fix.isEmpty()) {
            String example = firstExample(List.of(longLines, trailing, wildcard), indent, shortName);
            result.append("Что можно попросить: «").append(example).append("» — Kuleshato покажет изменения до записи. ");
        }
        result.append("Это проверка внешнего вида кода. Ошибки в логике и дублирование ищет /review <файл>.");
        if (verbose) {
            result.append("\n\nПодробно (").append(editorConfig.isEmpty()
                ? ".editorconfig нет — эталон по большинству файлов" : "учтён .editorconfig")
                .append(gitNormalizes ? "; Git выравнивает переводы строк" : "").append("):");
            for (Rule rule : List.of(indent, endings, braces)) result.append("\n- ").append(rule.technicalLine());
            for (Issue issue : List.of(trailing, longLines, wildcard, finalNewline, bom)) result.append("\n- ").append(issue.technicalLine());
        }
        return result.toString();
    }

    private static void issue(List<String> fix, List<String> fine, Issue issue, String text, Function<String, String> shortName) {
        if (issue.places.isEmpty()) fine.add(issue.name);
        else fix.add(text + examples(issue.places, shortName));
    }

    private static String examples(List<String> places, Function<String, String> shortName) {
        if (places.isEmpty()) return "";
        return "\n   Например: " + String.join(", ", places.stream().limit(EXAMPLES).map(shortName).toList())
            + (places.size() > EXAMPLES ? " и ещё " + (places.size() - EXAMPLES) : "");
    }

    private static String firstExample(List<Issue> issues, Rule indent, Function<String, String> shortName) {
        for (Issue issue : issues) {
            if (issue.places.isEmpty()) continue;
            String file = fileOf(shortName.apply(issue.places.get(0)));
            if (issue.technical.startsWith("Строки длиннее")) return "перенеси длинные строки в " + file;
            if (issue.technical.startsWith("Пробелы")) return "убери пробелы в конце строк в " + file;
            return "замени импорты со звёздочкой в " + file;
        }
        List<String> deviations = indent.deviations();
        if (!deviations.isEmpty()) return "приведи отступы в " + fileOf(shortName.apply(deviations.get(0))) + " к остальным файлам";
        return "исправь оформление в одном из файлов из списка";
    }

    private static String fileOf(String place) {
        int colon = place.lastIndexOf(':');
        return colon > 0 && place.substring(colon + 1).chars().allMatch(Character::isDigit) ? place.substring(0, colon) : place;
    }

    private static String humanIndent(String variant) {
        return switch (variant) {
            case "пробелы по 4" -> "4 пробела";
            case "пробелы по 2" -> "2 пробела";
            case "смешанные табуляция и пробелы" -> "табуляция вперемешку с пробелами";
            case "пробелы разной ширины" -> "отступы разной ширины";
            default -> variant;
        };
    }

    private static String humanEnding(String variant) {
        return switch (variant) {
            case "CRLF" -> "как в Windows (CRLF)";
            case "LF" -> "как в Linux и macOS (LF)";
            default -> "разные внутри одного файла";
        };
    }

    /** «498 строк в 24 файлах». */
    private static String lineCount(Issue issue) {
        String lines = plural(issue.lines, "строка", "строки", "строк");
        return issue.places.size() == 1 ? lines + " в одном файле" : lines + " в " + inFiles(issue.places.size());
    }

    private static String inFilesCount(Issue issue) {
        return plural(issue.places.size(), "файл", "файла", "файлов");
    }

    /** Предложный падеж: «в 1 файле», «в 24 файлах». */
    private static String inFiles(int count) {
        return count + (count % 10 == 1 && count % 100 != 11 ? " файле" : " файлах");
    }

    private static String files(int count) {
        return plural(count, "файл", "файла", "файлов");
    }

    static String plural(int count, String one, String few, String many) {
        int mod10 = count % 10, mod100 = count % 100;
        String word = mod10 == 1 && mod100 != 11 ? one : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? few : many;
        return count + " " + word;
    }

    /** Git выравнивает переводы строк при коммите: {@code .gitattributes} с {@code text=auto}/{@code eol=} или {@code core.autocrlf}. */
    static boolean gitNormalizesLineEndings(Path root) {
        try {
            Path attributes = root.resolve(".gitattributes");
            if (Files.isRegularFile(attributes) && Files.size(attributes) < 64 * 1024) {
                String text = Files.readString(attributes, StandardCharsets.UTF_8);
                if (text.contains("text=auto") || text.contains("eol=")) return true;
            }
            if (!Files.exists(root.resolve(".git"))) return false;
            Process process = new ProcessBuilder("git", "config", "--get", "core.autocrlf")
                .directory(root.toFile()).redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            String value = new String(process.getInputStream().readNBytes(64), StandardCharsets.UTF_8).strip().toLowerCase(Locale.ROOT);
            return value.equals("true") || value.equals("input");
        } catch (IOException error) {
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
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
}
