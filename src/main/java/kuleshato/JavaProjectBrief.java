package kuleshato;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

final class JavaProjectBrief {
    private static final Set<String> IGNORED = Set.of(
        ".git", ".idea", ".vscode", "build", "coverage", "dist", "node_modules", "out", "target", ".gradle"
    );
    private static final Set<String> DOCS = Set.of("README.md", "CONTRIBUTING.md", "CHANGELOG.md", "LICENSE");

    private JavaProjectBrief() {}

    record Snapshot(
        Path target, int fileCount, int dirCount, List<String> javaFiles, List<String> tests, List<String> docs,
        ProjectFacts.Result facts, List<String> warnings
    ) {}

    static String create(Path workspace, String requested) throws IOException {
        return render(scan(workspace, requested));
    }

    /** Краткий обзор для контекста модели: главные факты с происхождением, умещается примерно в 750 символов. */
    static String compact(Path workspace, String requested) throws IOException {
        Snapshot snapshot = scan(workspace, requested);
        ProjectFacts.Result facts = snapshot.facts();
        StringBuilder result = new StringBuilder("# Java-проект: ").append(name(snapshot.target())).append("\n## Java\n");
        ProjectFacts.Fact version = facts.first(ProjectFacts.Category.JAVA_VERSION);
        result.append("- Версия: ").append(version == null ? "не указана в известных настройках" : brief(version)).append('\n');
        List<ProjectFacts.Fact> builds = facts.of(ProjectFacts.Category.BUILD_SYSTEM);
        result.append("- Сборка: ").append(builds.isEmpty() ? "не определена по манифестам"
            : String.join(", ", builds.stream().limit(2).map(JavaProjectBrief::brief).toList())).append('\n');
        ProjectFacts.Fact framework = facts.first(ProjectFacts.Category.FRAMEWORK);
        if (framework != null) result.append("- Фреймворк: ").append(brief(framework)).append('\n');
        List<ProjectFacts.Fact> modules = facts.of(ProjectFacts.Category.MODULE);
        if (!modules.isEmpty()) {
            result.append("- Модули: ").append(String.join(", ", modules.stream().limit(6).map(ProjectFacts.Fact::value).toList()))
                .append(" (").append(modules.get(0).source().path()).append(" — конфигурация)\n");
        }
        result.append("## Тесты\n- Java-тестов: ").append(snapshot.tests().size());
        if (!snapshot.tests().isEmpty()) result.append(" (например, ").append(snapshot.tests().get(0)).append(')');
        result.append('\n');
        ProjectFacts.Fact testCommand = facts.first(ProjectFacts.Category.TEST_COMMAND);
        if (testCommand != null) result.append("- Команда: ").append(brief(testCommand)).append('\n');
        result.append("## CI\n");
        List<ProjectFacts.Fact> workflows = facts.of(ProjectFacts.Category.CI_WORKFLOW);
        if (workflows.isEmpty()) result.append("- CI-конфигурация не найдена при сканировании.\n");
        workflows.stream().limit(2).forEach(fact -> result.append("- ").append(brief(fact)).append('\n'));
        result.append("## Документация\n- ").append(snapshot.docs().isEmpty() ? "Файлы документации не найдены."
            : String.join(", ", snapshot.docs().stream().limit(4).toList())).append('\n');
        return result.toString();
    }

    private static String brief(ProjectFacts.Fact fact) {
        ProjectFacts.Source source = fact.source();
        return fact.value() + " (" + (source.line() > 0 ? source.path() + ":" + source.line() : source.path())
            + " — " + source.origin().label + ")";
    }

    private static String name(Path target) {
        return target.getFileName() == null ? target.toString() : target.getFileName().toString();
    }

    static Snapshot scan(Path workspace, String requested) throws IOException {
        Path root = workspace.toRealPath();
        Path requestedTarget = root.resolve(requested.isBlank() ? "." : requested).normalize();
        if (!requestedTarget.startsWith(root)) throw new IOException("Путь обзора должен находиться внутри Java-проекта.");
        Path target = requestedTarget.toRealPath();
        if (!target.startsWith(root) || !Files.isDirectory(target)) throw new IOException("Путь обзора должен указывать на каталог внутри проекта.");
        List<Path> files = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean[] limitHit = {false};
        Files.walkFileTree(target, new java.nio.file.SimpleFileVisitor<>() {
            private java.nio.file.FileVisitResult maybeStop() {
                if (files.size() + dirs.size() >= 2001) {
                    limitHit[0] = true;
                    return java.nio.file.FileVisitResult.TERMINATE;
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path path, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!path.equals(target) && IGNORED.contains(path.getFileName().toString())) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                if (!path.equals(target)) dirs.add(path);
                return maybeStop();
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) files.add(path);
                return maybeStop();
            }
        });
        boolean truncated = limitHit[0] || files.size() + dirs.size() > 2000;
        if (truncated) {
            while (files.size() + dirs.size() > 2000) {
                if (!files.isEmpty()) files.remove(files.size() - 1);
                else dirs.remove(dirs.size() - 1);
            }
            warnings.add("Достигнут лимит файлов (2000); обзор неполный.");
        }
        files.sort(Comparator.comparing(path -> relative(target, path)));
        dirs.sort(Comparator.comparing(path -> relative(target, path)));
        List<String> javaFiles = files.stream().filter(path -> path.toString().endsWith(".java"))
            .map(path -> relative(target, path)).toList();
        ProjectFacts.Result facts = ProjectFacts.extract(target, files);
        warnings.addAll(facts.warnings());
        List<String> tests = javaFiles.stream().filter(path ->
            path.contains("/test/") || path.contains("/tests/")
                || path.matches(".*Test\\.java$") || path.matches(".*Tests\\.java$"))
            .toList();
        List<String> docs = files.stream().map(path -> relative(target, path))
            .filter(path -> DOCS.contains(Path.of(path).getFileName().toString())).toList();
        if (tests.isEmpty()) warnings.add("Java-тесты не найдены при сканировании.");
        if (facts.of(ProjectFacts.Category.CI_WORKFLOW).isEmpty()) warnings.add("Конфигурация CI не найдена при сканировании.");
        if (docs.stream().noneMatch(path -> Path.of(path).getFileName().toString().startsWith("README"))) {
            warnings.add("README не найден при сканировании.");
        }
        List<String> javaVersions = facts.of(ProjectFacts.Category.JAVA_VERSION).stream()
            .map(ProjectFacts.Fact::value).distinct().toList();
        if (javaVersions.size() > 1) {
            warnings.add("В настройках сборки указаны разные версии Java: " + String.join(", ", javaVersions)
                + " (например, source/target или разные модули).");
        }
        warnings.add("Обзор основан на именах файлов и манифестах; отсутствие совпадений не доказывает отсутствие функций.");
        return new Snapshot(target, files.size(), dirs.size(), javaFiles, tests, docs, facts, warnings);
    }

    private static String render(Snapshot snapshot) {
        Path target = snapshot.target();
        List<String> javaFiles = snapshot.javaFiles();
        List<String> tests = snapshot.tests();
        List<String> docs = snapshot.docs();
        ProjectFacts.Result facts = snapshot.facts();
        List<String> warnings = new ArrayList<>(snapshot.warnings());
        warnings.add("Происхождение факта указано в скобках: файл:строка — вид источника, затем основание. "
            + "Команды, выведенные из стандартных соглашений Maven/Gradle, имён скриптов или документации, "
            + "не запускались и не проверены.");

        StringBuilder javaSection = new StringBuilder("## Java и сборка\n- Файлов `.java`: ").append(javaFiles.size())
            .append(" (структура файлов)\n");
        ProjectFacts.Fact version = facts.first(ProjectFacts.Category.JAVA_VERSION);
        javaSection.append("- Версия: ").append(version == null ? "не указана в известных настройках" : version.render()).append('\n');
        List<ProjectFacts.Fact> buildSystems = facts.of(ProjectFacts.Category.BUILD_SYSTEM);
        javaSection.append(buildSystems.isEmpty() ? "- Сборка: не определена по манифестам\n"
            : renderGroup("Сборка", buildSystems, 6));
        javaSection.append(renderGroup("Фреймворк приложения", facts.of(ProjectFacts.Category.FRAMEWORK), 3));
        javaSection.append(renderGroup("Артефакт", facts.of(ProjectFacts.Category.ARTIFACT), 4));
        javaSection.append(renderGroup("Модули", facts.of(ProjectFacts.Category.MODULE), 12));
        javaSection.append(renderGroup("Обёртка сборки", facts.of(ProjectFacts.Category.WRAPPER), 4));
        javaSection.append(renderGroup("Главный класс", facts.of(ProjectFacts.Category.MAIN_CLASS), 3));
        javaSection.append(renderGroup("JDK для инструментов", facts.of(ProjectFacts.Category.TOOLCHAIN), 3));

        StringBuilder testSection = new StringBuilder("## Тесты\n");
        testSection.append(renderGroup("Фреймворк", facts.of(ProjectFacts.Category.TEST_FRAMEWORK), 6));
        int shownTests = Math.min(tests.size(), 40);
        testSection.append(tests.isEmpty() ? "- Тестовые классы не обнаружены.\n"
            : tests.stream().limit(shownTests).map(path -> "- " + path + "\n").collect(java.util.stream.Collectors.joining()));
        if (tests.size() > shownTests) testSection.append("- … и ещё ").append(tests.size() - shownTests).append('\n');

        StringBuilder ci = new StringBuilder("## CI\n");
        List<ProjectFacts.Fact> workflows = facts.of(ProjectFacts.Category.CI_WORKFLOW);
        if (workflows.isEmpty()) ci.append("- CI-конфигурация не найдена при сканировании.\n");
        ci.append(renderFacts(workflows, 8));
        ci.append(renderFacts(facts.of(ProjectFacts.Category.CI_TRIGGER), 8));
        ci.append(renderFacts(facts.of(ProjectFacts.Category.CI_JAVA), 8));
        ci.append(renderGroup("Шаги", facts.of(ProjectFacts.Category.CI_COMMAND), 12));

        return "# Java-проект: " + name(target) + "\n\n"
            + "Путь: `" + target + "`\nПросканировано: " + snapshot.fileCount() + " файлов, " + snapshot.dirCount() + " каталогов.\n\n"
            + javaSection + "\n"
            + "## Команды сборки и тестов\n"
            + section(renderGroup("Тесты", facts.of(ProjectFacts.Category.TEST_COMMAND), 8)
                + renderGroup("Сборка", facts.of(ProjectFacts.Category.BUILD_COMMAND), 8), "Команды Maven/Gradle не найдены.") + "\n"
            + testSection + "\n"
            + ci + "\n"
            + "## Зависимости и плагины\n"
            + section(renderGroup("Зависимости", facts.of(ProjectFacts.Category.DEPENDENCY), 15)
                + renderGroup("Плагины", facts.of(ProjectFacts.Category.PLUGIN), 10), "Зависимости и плагины не обнаружены в манифестах.") + "\n"
            + "## Настройки проекта\n"
            + section(renderFacts(facts.of(ProjectFacts.Category.SETTING), 12), "Дополнительные файлы настроек не найдены.") + "\n"
            + "## Документация\n" + bullet(docs, "Файлы документации не найдены.") + "\n\n"
            + "## Замечания\n" + bullet(warnings, "Замечаний нет.");
    }

    private static String renderGroup(String title, List<ProjectFacts.Fact> facts, int limit) {
        if (facts.isEmpty()) return "";
        if (facts.size() == 1) return "- " + title + ": " + facts.get(0).render() + "\n";
        StringBuilder result = new StringBuilder("- ").append(title).append(":\n");
        facts.stream().limit(limit).forEach(fact -> result.append("  - ").append(fact.render()).append('\n'));
        if (facts.size() > limit) result.append("  - … и ещё ").append(facts.size() - limit).append('\n');
        return result.toString();
    }

    private static String renderFacts(List<ProjectFacts.Fact> facts, int limit) {
        StringBuilder result = new StringBuilder();
        facts.stream().limit(limit).forEach(fact -> result.append("- ").append(fact.render()).append('\n'));
        if (facts.size() > limit) result.append("- … и ещё ").append(facts.size() - limit).append('\n');
        return result.toString();
    }

    private static String section(String content, String empty) {
        return content.isEmpty() ? "- " + empty + "\n" : content;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String bullet(List<String> values, String empty) {
        return values.isEmpty() ? "- " + empty : values.stream().map(value -> "- " + value).collect(java.util.stream.Collectors.joining("\n"));
    }
}
