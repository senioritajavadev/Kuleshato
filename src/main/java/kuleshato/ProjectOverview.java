package kuleshato;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Обзор проекта, который приложение составляет само, без модели: заявленные в README возможности и карта кода
 * (пакеты, классы, их методы, точка входа и технологии со ссылками {@code файл:строка}).
 *
 * <p>Небольшая модель на вопрос «расскажи о проекте» получает в окно в основном импорты нескольких файлов и
 * не может подтвердить общих утверждений. Карта кода строится по всем исходникам и не зависит от окна модели;
 * в ней нет выводов о поведении — только то, что объявлено в коде.
 */
final class ProjectOverview {
    private static final Set<String> SKIP = Set.of(".git", ".idea", ".gradle", ".kuleshato", "build", "node_modules", "out", "target");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile(
        "^(?:public\\s+|protected\\s+|private\\s+)?(?:(?:abstract|final|sealed|non-sealed|static|strictfp)\\s+)*"
            + "(class|interface|record|enum|@interface)\\s+(\\w+)");
    /** Метод верхнего класса: отступ в один уровень, тип результата, имя и открывающая скобка. */
    private static final Pattern METHOD = Pattern.compile(
        "^(?: {4}|\\t)(?:@\\w+\\s+)*(?:(?:public|protected|private|static|final|abstract|synchronized|default|native)\\s+)*"
            + "(?:<[^>]+>\\s+)?[\\w.<>\\[\\],? ]+?\\s+(\\w+)\\s*\\(");
    private static final Set<String> NOT_METHODS = Set.of("if", "for", "while", "switch", "catch", "return", "new", "throw", "else", "try", "synchronized");
    private static final Pattern MAIN = Pattern.compile("\\bstatic\\s+void\\s+main\\s*\\(");
    private static final Pattern FEATURE_HEADING = Pattern.compile(
        "(?iu)^#{1,4}\\s*.*(особенност|возможност|функци|что умеет|умеет|features|capabilities|functionality).*$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.+)$");

    /** Признак технологии в коде: шаблон строки и подпись. Первое совпадение даёт ссылку на файл и строку. */
    private record Signal(Pattern pattern, String label) {}

    private static final List<Signal> SIGNALS = List.of(
        new Signal(Pattern.compile("^\\s*import\\s+org\\.springframework"), "Spring"),
        new Signal(Pattern.compile("@(?:Rest)?Controller\\b"), "веб-контроллеры Spring"),
        new Signal(Pattern.compile("^\\s*import\\s+(?:jakarta|javax)\\.persistence"), "JPA (сущности базы данных)"),
        new Signal(Pattern.compile("^\\s*import\\s+java\\.sql\\."), "JDBC (работа с базой данных через java.sql)"),
        new Signal(Pattern.compile("DriverManager\\.getConnection"), "подключение к базе данных через DriverManager"),
        new Signal(Pattern.compile("new\\s+Scanner\\s*\\(\\s*System\\.in"), "консольный ввод (Scanner из System.in)"),
        new Signal(Pattern.compile("^\\s*import\\s+org\\.apache\\.kafka"), "Kafka"),
        new Signal(Pattern.compile("^\\s*import\\s+java\\.net\\.http\\."), "HTTP-клиент java.net.http"),
        new Signal(Pattern.compile("^\\s*import\\s+com\\.sun\\.net\\.httpserver"), "встроенный HTTP-сервер"),
        new Signal(Pattern.compile("^\\s*import\\s+(?:javax\\.swing|javafx)\\."), "графический интерфейс (Swing/JavaFX)"),
        new Signal(Pattern.compile("^\\s*import\\s+(?:org\\.slf4j|org\\.apache\\.logging\\.log4j|java\\.util\\.logging)"), "журналирование"),
        new Signal(Pattern.compile("^\\s*import\\s+java\\.util\\.concurrent\\."), "многопоточность (java.util.concurrent)"));

    private ProjectOverview() {
    }

    /** Возможности из раздела README вроде «Особенности»/«Features» с номерами строк. */
    static String readmeFeatures(Path root) {
        Path readme = root.resolve("README.md");
        if (!Files.isRegularFile(readme)) return "";
        List<String> lines;
        try {
            lines = Files.readAllLines(readme, StandardCharsets.UTF_8);
        } catch (IOException | java.io.UncheckedIOException error) {
            return "";
        }
        List<String> features = new ArrayList<>();
        int first = -1;
        int last = -1;
        boolean inside = false;
        for (int i = 0; i < lines.size() && features.size() < 10; i++) {
            String line = lines.get(i);
            if (line.stripLeading().startsWith("#")) {
                if (inside && !features.isEmpty()) break;
                inside = FEATURE_HEADING.matcher(line.strip()).matches();
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            if (inside && bullet.matches()) {
                features.add(bullet.group(1).strip());
                if (first < 0) first = i + 1;
                last = i + 1;
            }
        }
        if (features.isEmpty()) return "";
        StringBuilder result = new StringBuilder("Заявленные в README возможности (README.md:" + first
            + (last > first ? "-" + last : "") + "; это описание, а не проверка реализации):");
        for (String feature : features) result.append("\n- ").append(feature);
        return result.toString();
    }

    /** Карта основного кода проекта в пределах {@code maxChars}. Пустая строка, если Java-исходников нет. */
    static String codeMap(Path root, int maxChars) throws IOException {
        Map<String, List<String>> packages = new TreeMap<>();
        Map<String, String> signals = new LinkedHashMap<>();
        List<String> entryPoints = new ArrayList<>();
        int classes = 0;
        for (Path file : javaSources(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException | java.io.UncheckedIOException error) {
                continue;
            }
            String packageName = "(без пакета)";
            String typeName = null;
            String typeKind = null;
            List<String> methods = new ArrayList<>();
            boolean textBlock = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                // Текстовые блоки (SQL, JSON) похожи на объявления; их строки пропускаем.
                int quotes = line.split("\"\"\"", -1).length - 1;
                if (textBlock || quotes > 0) {
                    if (quotes % 2 == 1) textBlock = !textBlock;
                    continue;
                }
                Matcher matcher;
                if ((matcher = PACKAGE.matcher(line)).find()) packageName = matcher.group(1);
                if (typeName == null && (matcher = TYPE.matcher(line)).find()) {
                    typeKind = matcher.group(1);
                    typeName = matcher.group(2);
                }
                if (typeName != null && isDeclarationLine(line) && (matcher = METHOD.matcher(line)).find()) {
                    String name = matcher.group(1);
                    if (!NOT_METHODS.contains(name) && !name.equals(typeName) && !methods.contains(name)
                        && Character.isLowerCase(name.charAt(0))) {
                        methods.add(name);
                    }
                }
                if (MAIN.matcher(line).find()) entryPoints.add((packageName.startsWith("(") ? "" : packageName + ".")
                    + (typeName == null ? file.getFileName().toString().replace(".java", "") : typeName)
                    + " (" + relative + ":" + (i + 1) + ")");
                for (Signal signal : SIGNALS) {
                    if (!signals.containsKey(signal.label()) && signal.pattern().matcher(line).find()) {
                        signals.put(signal.label(), relative + ":" + (i + 1));
                    }
                }
            }
            if (typeName == null) continue;
            classes++;
            String kind = switch (typeKind) {
                case "interface" -> " [интерфейс]";
                case "record" -> " [record]";
                case "enum" -> " [enum]";
                default -> "";
            };
            String shown = methods.size() > 6 ? String.join(", ", methods.subList(0, 6)) + ", … ещё " + (methods.size() - 6)
                : String.join(", ", methods);
            packages.computeIfAbsent(packageName, ignored -> new ArrayList<>())
                .add(typeName + kind + (shown.isEmpty() ? "" : " — " + shown));
        }
        if (classes == 0) return "";
        StringBuilder result = new StringBuilder("Устройство кода (по объявлениям в исходниках: пакеты, классы и их методы; "
            + "поведение методов не анализировалось):");
        if (!entryPoints.isEmpty()) {
            result.append("\n- Точка входа: ").append(String.join("; ", entryPoints.subList(0, Math.min(3, entryPoints.size()))));
        }
        if (!signals.isEmpty()) {
            result.append("\n- В коде используются:");
            signals.forEach((label, where) -> result.append(" ").append(label).append(" (").append(where).append(");"));
            result.setLength(result.length() - 1);
        }
        result.append("\n- Пакетов: ").append(packages.size()).append(", классов: ").append(classes).append('.');
        StringBuilder body = new StringBuilder();
        int omitted = 0;
        for (Map.Entry<String, List<String>> entry : packages.entrySet()) {
            StringBuilder block = new StringBuilder("\n- ").append(entry.getKey()).append(':');
            List<String> types = entry.getValue();
            for (int i = 0; i < types.size(); i++) {
                if (i == 8) {
                    block.append("\n  - … ещё классов: ").append(types.size() - 8);
                    break;
                }
                block.append("\n  - ").append(types.get(i));
            }
            if (result.length() + body.length() + block.length() > maxChars) {
                omitted++;
                continue;
            }
            body.append(block);
        }
        result.append(body);
        if (omitted > 0) result.append("\n- … ещё пакетов не показано: ").append(omitted).append(" (ограничение длины обзора)");
        return result.toString();
    }

    /** Строка-объявление, а не вызов: без строк, присваиваний, {@code new}, {@code throw}, {@code return}. */
    private static boolean isDeclarationLine(String line) {
        String stripped = line.strip();
        if (stripped.contains("\"") || stripped.contains("=") && !stripped.contains("==") && stripped.indexOf('=') < stripped.indexOf('(')) {
            return false;
        }
        if (stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*")) return false;
        String first = stripped.split("[\\s(]", 2)[0];
        if (Set.of("new", "throw", "return", "else", "case", "yield", "assert", "this", "super").contains(first)) return false;
        return !stripped.contains(" new ") && !stripped.contains("->");
    }

    /** Исходники основного кода: без тестов, сборочных каталогов и скрытых каталогов. */
    private static List<Path> javaSources(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, java.util.Set.of(), 30, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (dir.equals(root)) return java.nio.file.FileVisitResult.CONTINUE;
                String name = dir.getFileName().toString();
                if (SKIP.contains(name) || name.startsWith(".")) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                String relative = root.relativize(dir).toString().replace('\\', '/');
                if (relative.matches("(?:.*/)?src/test(?:/.*)?")) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (attrs.isRegularFile() && name.endsWith(".java") && !name.endsWith("Test.java") && !name.endsWith("Tests.java")
                    && attrs.size() <= 512 * 1024) {
                    files.add(file);
                }
                return files.size() >= 2000 ? java.nio.file.FileVisitResult.TERMINATE : java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        files.sort(null);
        return files;
    }

    /** Похож ли запрос на просьбу рассказать о проекте в целом (а не о конкретном классе или методе). */
    static boolean isOverviewRequest(String prompt) {
        String text = prompt.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (Pattern.compile("[A-Z][a-z]+[A-Z]\\w*|\\w+\\.java\\b|#\\w").matcher(prompt).find()) return false;
        boolean verb = Pattern.compile("(?U)(расскаж|опиши|описание|обзор|познаком|что\\s+(?:это|за)\\b|о\\s+чем|про\\s+что|"
            + "\\btell me about|\\bdescribe|\\boverview|\\bwhat is this)").matcher(text).find();
        boolean subject = Pattern.compile("(?U)(проект|код|приложени|программ|репозитор|\\bо\\s+не[мй]\\b|\\bпро\\s+него\\b|"
            + "\\bproject|\\bcode|\\bapp|\\brepo|\\bit\\b)").matcher(text).find();
        return verb && subject;
    }
}
