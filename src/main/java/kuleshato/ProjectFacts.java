package kuleshato;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Детерминированные извлекатели фактов о Java-проекте. Каждый факт хранит происхождение:
 * файл, строку, вид источника и, если факт выведен, основание вывода.
 */
final class ProjectFacts {
    enum Origin {
        CONFIG("конфигурация"),
        CODE("код"),
        DOCUMENTATION("документация"),
        FILESYSTEM("структура файлов"),
        RUNTIME("runtime");

        final String label;

        Origin(String label) {
            this.label = label;
        }
    }

    enum Category {
        BUILD_SYSTEM, ARTIFACT, JAVA_VERSION, TOOLCHAIN, MODULE, WRAPPER, MAIN_CLASS, FRAMEWORK,
        DEPENDENCY, PLUGIN, TEST_FRAMEWORK, TEST_COMMAND, BUILD_COMMAND,
        CI_WORKFLOW, CI_TRIGGER, CI_JAVA, CI_COMMAND, SETTING
    }

    /** Происхождение факта. {@code line == 0} означает «файл целиком» (например, наличие файла). */
    record Source(Origin origin, String path, int line, String basis) {
        String render() {
            return (line > 0 ? path + ":" + line : path) + " — " + origin.label
                + (basis == null || basis.isBlank() ? "" : ", " + basis);
        }
    }

    record Fact(Category category, String value, List<Source> sources) {
        Fact {
            sources = List.copyOf(sources);
            if (sources.isEmpty()) throw new IllegalArgumentException("Fact requires provenance");
        }

        Source source() {
            return sources.get(0);
        }

        String render() {
            return value + " (" + String.join("; ", sources.stream().map(Source::render).toList()) + ")";
        }
    }

    record Result(List<Fact> facts, List<String> warnings) {
        List<Fact> of(Category category) {
            return facts.stream().filter(fact -> fact.category() == category).toList();
        }

        Fact first(Category category) {
            return facts.stream().filter(fact -> fact.category() == category).findFirst().orElse(null);
        }
    }

    private static final long MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_BUILD_FILES = 24;
    private static final int MAX_FACTS_PER_FILE = 200;
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private ProjectFacts() {}

    /** Извлекает факты из уже найденных файлов проекта; {@code files} — абсолютные пути внутри {@code root}. */
    static Result extract(Path root, List<Path> files) {
        Collector collector = new Collector();
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparingInt((Path path) -> isManifest(path.getFileName().toString()) ? 0 : 1)
            .thenComparingInt(path -> root.relativize(path).getNameCount())
            .thenComparing(path -> relative(root, path)));
        Path primaryBuild = sorted.stream().filter(path -> isBuildFile(path.getFileName().toString())).findFirst().orElse(null);
        Catalog catalog = Catalog.EMPTY;
        for (Path file : sorted) {
            if (!relative(root, file).equals(CATALOG_PATH)) continue;
            try {
                catalog = Catalog.parse(read(file));
            } catch (IOException error) {
                collector.warn("Не удалось прочитать " + CATALOG_PATH + ": " + error.getMessage());
            }
        }
        int buildFiles = 0;
        for (Path file : sorted) {
            String rel = relative(root, file);
            String name = file.getFileName().toString();
            int depth = root.relativize(file).getNameCount();
            boolean primaryDirectory = primaryBuild != null && java.util.Objects.equals(file.getParent(), primaryBuild.getParent());
            try {
                if (name.equals("pom.xml")) {
                    if (++buildFiles > MAX_BUILD_FILES) {
                        collector.warn("Разобраны только первые " + MAX_BUILD_FILES + " файлов сборки.");
                        continue;
                    }
                    maven(rel, read(file), primaryDirectory, files, root, collector);
                } else if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
                    if (++buildFiles > MAX_BUILD_FILES) continue;
                    gradle(rel, read(file), primaryDirectory, files, root, catalog, collector);
                } else if (name.equals("settings.gradle") || name.equals("settings.gradle.kts")) {
                    gradleSettings(rel, read(file), collector);
                } else if (rel.equals(".mvn/wrapper/maven-wrapper.properties")) {
                    wrapperProperties(rel, read(file), "apache-maven-([\\d.]+)", "Maven Wrapper", collector);
                } else if (rel.equals("gradle/wrapper/gradle-wrapper.properties")) {
                    wrapperProperties(rel, read(file), "gradle-([\\d.]+(?:-rc-\\d+)?)-(?:bin|all)", "Gradle Wrapper", collector);
                } else if (rel.equals(".mvn/maven.config") || rel.equals(".mvn/jvm.config")) {
                    lineSettings(rel, read(file), collector);
                } else if (depth == 1 && name.equals("gradle.properties")) {
                    gradleProperties(rel, read(file), collector);
                } else if (depth == 1 && (name.equals(".java-version") || name.equals(".sdkmanrc") || name.equals(".tool-versions"))) {
                    toolchainFile(rel, name, read(file), collector);
                } else if (isApplicationConfig(rel)) {
                    applicationConfig(rel, read(file), collector);
                } else if (isCiFile(rel)) {
                    if (name.equals("Jenkinsfile")) jenkinsfile(rel, read(file), collector);
                    else ciYaml(rel, read(file), collector);
                } else if (depth == 1 && isScript(name)) {
                    script(rel, name, collector);
                } else if (depth == 1 && name.equalsIgnoreCase("README.md")) {
                    readmeCommands(rel, read(file), collector);
                }
            } catch (IOException error) {
                collector.warn("Не удалось прочитать " + rel + ": " + error.getMessage());
            }
        }
        return collector.result();
    }

    static boolean isCiFile(String rel) {
        String name = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
        return rel.startsWith(".github/workflows/") && (name.endsWith(".yml") || name.endsWith(".yaml"))
            || rel.equals(".circleci/config.yml")
            || name.equals("Jenkinsfile") || rel.equals(".gitlab-ci.yml") || rel.equals("azure-pipelines.yml")
            || rel.equals("bitbucket-pipelines.yml");
    }

    private static boolean isBuildFile(String name) {
        return name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts");
    }

    private static boolean isManifest(String name) {
        return isBuildFile(name) || name.equals("settings.gradle") || name.equals("settings.gradle.kts");
    }

    private static boolean isScript(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return (lower.startsWith("test") || lower.startsWith("build"))
            && (lower.endsWith(".ps1") || lower.endsWith(".sh") || lower.endsWith(".bat") || lower.endsWith(".cmd"));
    }

    private static boolean isApplicationConfig(String rel) {
        if (!rel.contains("src/main/resources/")) return false;
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        return name.matches("(?:application|bootstrap)(?:-[\\w.-]+)?\\.(?:properties|ya?ml)");
    }

    private static String read(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("файл больше " + (MAX_FILE_BYTES / 1024) + " КБ, пропущен");
        String content = Files.readString(file);
        if (content.indexOf('\0') >= 0) throw new IOException("файл не текстовый");
        return content;
    }

    // ---------------------------------------------------------------- Maven

    private record Value(String text, int line) {}

    private static final class PomHandler extends org.xml.sax.helpers.DefaultHandler {
        private final Deque<String> names = new ArrayDeque<>();
        private final Deque<Integer> lines = new ArrayDeque<>();
        private final StringBuilder text = new StringBuilder();
        private org.xml.sax.Locator locator;
        final Map<String, Value> fields = new LinkedHashMap<>();
        final Map<String, Value> properties = new LinkedHashMap<>();
        final List<Value> modules = new ArrayList<>();
        final List<Map<String, Value>> dependencies = new ArrayList<>();
        final List<Map<String, Value>> plugins = new ArrayList<>();
        private final String[] source;
        private Map<String, Value> current;
        int projectLine = 1;

        PomHandler(String[] source) {
            this.source = source;
        }

        /** SAX сообщает строку конца открывающего тега; ищем строку, где тег начинается. */
        private int tagStart(String qName, int reportedLine) {
            for (int line = Math.min(reportedLine, source.length); line >= 1 && line > reportedLine - 20; line--) {
                if (source[line - 1].contains("<" + qName)) return line;
            }
            return reportedLine;
        }

        @Override
        public void setDocumentLocator(org.xml.sax.Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName, org.xml.sax.Attributes attributes) {
            String name = qName.contains(":") ? qName.substring(qName.indexOf(':') + 1) : qName;
            names.addLast(name);
            int line = locator == null ? 0 : tagStart(qName, locator.getLineNumber());
            lines.addLast(line);
            text.setLength(0);
            String path = String.join("/", names);
            if (path.equals("project")) projectLine = line;
            if (path.equals("project/dependencies/dependency") || path.equals("project/build/plugins/plugin")) {
                current = new LinkedHashMap<>();
                current.put("_line", new Value("", line));
            }
        }

        @Override
        public void characters(char[] chars, int start, int length) {
            text.append(chars, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String path = String.join("/", names);
            String value = text.toString().strip();
            int line = lines.removeLast();
            names.removeLast();
            text.setLength(0);
            Value entry = new Value(value, line);
            if (path.matches("project/(groupId|artifactId|version|packaging|name)")) {
                fields.put(path.substring("project/".length()), entry);
            } else if (path.matches("project/parent/(groupId|artifactId|version)")) {
                fields.put("parent." + path.substring("project/parent/".length()), entry);
            } else if (path.startsWith("project/properties/") && path.indexOf('/', "project/properties/".length()) < 0) {
                properties.put(path.substring("project/properties/".length()), entry);
            } else if (path.equals("project/modules/module")) {
                modules.add(entry);
            } else if (current != null && path.matches("project/dependencies/dependency/(groupId|artifactId|version|scope)")) {
                current.put(path.substring(path.lastIndexOf('/') + 1), entry);
            } else if (current != null && path.matches("project/build/plugins/plugin/(groupId|artifactId|version)")) {
                current.put(path.substring(path.lastIndexOf('/') + 1), entry);
            } else if (current != null && path.matches("project/build/plugins/plugin/configuration/(release|source|target|mainClass)")) {
                current.put("config." + path.substring(path.lastIndexOf('/') + 1), entry);
            } else if (current != null && path.matches(
                "project/build/plugins/plugin/configuration/archive/manifest/mainClass")) {
                current.put("config.mainClass", entry);
            } else if (path.equals("project/dependencies/dependency")) {
                dependencies.add(current);
                current = null;
            } else if (path.equals("project/build/plugins/plugin")) {
                plugins.add(current);
                current = null;
            }
        }
    }

    private static void maven(
        String rel, String content, boolean primary, List<Path> files, Path root, Collector collector
    ) {
        PomHandler pom = new PomHandler(lines(content));
        try {
            javax.xml.parsers.SAXParserFactory factory = javax.xml.parsers.SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.newSAXParser().parse(new org.xml.sax.InputSource(new StringReader(content)), pom);
        } catch (Exception error) {
            collector.warn("Не удалось разобрать " + rel + " как XML; факты Maven из него не извлечены.");
            return;
        }
        Origin config = Origin.CONFIG;
        collector.add(Category.BUILD_SYSTEM, "Maven", new Source(config, rel, pom.projectLine, null));
        Value artifactId = pom.fields.get("artifactId");
        if (artifactId != null) {
            Value group = pom.fields.getOrDefault("groupId", pom.fields.get("parent.groupId"));
            Value version = pom.fields.getOrDefault("version", pom.fields.get("parent.version"));
            Value packaging = pom.fields.get("packaging");
            String coordinates = (group == null ? "?" : resolve(group.text(), pom)) + ":" + artifactId.text()
                + (version == null ? "" : ":" + resolve(version.text(), pom))
                + (packaging == null ? "" : " (" + packaging.text() + ")");
            collector.add(Category.ARTIFACT, coordinates, new Source(config, rel, artifactId.line(), null));
        }
        Value parentGroup = pom.fields.get("parent.groupId");
        Value parentArtifact = pom.fields.get("parent.artifactId");
        if (parentGroup != null && parentArtifact != null) {
            String application = applicationFramework(parentGroup.text(), parentArtifact.text());
            Value parentVersion = pom.fields.get("parent.version");
            if (application != null) collector.add(Category.FRAMEWORK, application, new Source(config, rel, parentArtifact.line(),
                "parent " + parentArtifact.text() + (parentVersion == null ? "" : " " + parentVersion.text())));
        }
        for (String key : List.of("maven.compiler.release", "java.version", "maven.compiler.source", "maven.compiler.target")) {
            Value value = pom.properties.get(key);
            if (value != null && !value.text().isBlank()) {
                collector.add(Category.JAVA_VERSION, normalizeJava(resolve(value.text(), pom)),
                    new Source(config, rel, value.line(), "свойство " + key));
            }
        }
        for (Value module : pom.modules) {
            collector.add(Category.MODULE, module.text(), new Source(config, rel, module.line(), null));
        }
        for (Map<String, Value> dependency : pom.dependencies) {
            Value group = dependency.get("groupId");
            Value artifact = dependency.get("artifactId");
            if (group == null || artifact == null) continue;
            Value version = dependency.get("version");
            Value scope = dependency.get("scope");
            String resolvedVersion = version == null ? null : resolve(version.text(), pom);
            String coordinates = group.text() + ":" + artifact.text()
                + (resolvedVersion == null ? "" : ":" + resolvedVersion)
                + (scope == null ? "" : " [" + scope.text() + "]");
            String basis = version != null && !resolvedVersion.equals(version.text())
                ? "версия из " + version.text() : null;
            Source source = new Source(config, rel, dependency.get("_line").line(), basis);
            collector.add(Category.DEPENDENCY, coordinates, source);
            String framework = testFramework(group.text(), artifact.text());
            if (framework != null) collector.add(Category.TEST_FRAMEWORK, framework,
                new Source(config, rel, dependency.get("_line").line(), "зависимость " + group.text() + ":" + artifact.text()));
            String application = applicationFramework(group.text(), artifact.text());
            if (application != null) collector.add(Category.FRAMEWORK, application,
                new Source(config, rel, dependency.get("_line").line(), "зависимость " + group.text() + ":" + artifact.text()));
        }
        boolean failsafe = false;
        for (Map<String, Value> plugin : pom.plugins) {
            Value artifact = plugin.get("artifactId");
            if (artifact == null) continue;
            Value version = plugin.get("version");
            int line = plugin.get("_line").line();
            collector.add(Category.PLUGIN, artifact.text() + (version == null ? "" : ":" + resolve(version.text(), pom)),
                new Source(config, rel, line, null));
            if (artifact.text().equals("maven-failsafe-plugin")) failsafe = true;
            if (artifact.text().equals("maven-compiler-plugin")) {
                for (String key : List.of("release", "source", "target")) {
                    Value value = plugin.get("config." + key);
                    if (value != null && !value.text().isBlank()) {
                        collector.add(Category.JAVA_VERSION, normalizeJava(resolve(value.text(), pom)),
                            new Source(config, rel, value.line(), "maven-compiler-plugin <" + key + ">"));
                    }
                }
            }
            Value mainClass = plugin.get("config.mainClass");
            if (mainClass != null && !mainClass.text().isBlank()) {
                collector.add(Category.MAIN_CLASS, resolve(mainClass.text(), pom),
                    new Source(config, rel, mainClass.line(), artifact.text()));
            }
        }
        if (!primary) return;
        boolean windows = isWindows();
        String directory = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/') + 1) : "";
        boolean wrapper = files.stream().map(path -> relative(root, path))
            .anyMatch(path -> path.equals(directory + "mvnw") || path.equals(directory + "mvnw.cmd"));
        if (wrapper) {
            collector.add(Category.WRAPPER, "Maven Wrapper (mvnw)",
                new Source(Origin.FILESYSTEM, directory + (windows ? "mvnw.cmd" : "mvnw"), 0, null));
        }
        String mvn = wrapper ? (windows ? ".\\mvnw.cmd" : "./mvnw") : "mvn";
        Source standard = new Source(config, rel, pom.projectLine, "стандартная фаза Maven");
        collector.add(Category.TEST_COMMAND, "`" + mvn + " test`", standard);
        collector.add(Category.BUILD_COMMAND, "`" + mvn + " package`", standard);
        if (failsafe) {
            collector.add(Category.TEST_COMMAND, "`" + mvn + " verify`",
                new Source(config, rel, pom.plugins.stream()
                    .filter(plugin -> plugin.get("artifactId") != null
                        && plugin.get("artifactId").text().equals("maven-failsafe-plugin"))
                    .findFirst().map(plugin -> plugin.get("_line").line()).orElse(pom.projectLine),
                    "maven-failsafe-plugin запускает интеграционные тесты в фазе verify"));
        }
    }

    private static String resolve(String value, PomHandler pom) {
        Matcher matcher = PROPERTY_REFERENCE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Value replacement = pom.properties.get(key);
            if (replacement == null && key.equals("project.version")) {
                replacement = pom.fields.getOrDefault("version", pom.fields.get("parent.version"));
            }
            if (replacement == null && key.equals("project.groupId")) {
                replacement = pom.fields.getOrDefault("groupId", pom.fields.get("parent.groupId"));
            }
            String text = replacement == null || replacement.text().contains("${") ? matcher.group() : replacement.text();
            matcher.appendReplacement(result, Matcher.quoteReplacement(text));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String testFramework(String group, String artifact) {
        if (group.equals("org.junit.jupiter") || artifact.equals("junit-bom") && group.equals("org.junit")) return "JUnit 5 (Jupiter)";
        if (group.equals("junit") && artifact.equals("junit")) return "JUnit 4";
        if (group.equals("org.testng")) return "TestNG";
        if (group.equals("org.spockframework")) return "Spock";
        if (group.equals("org.mockito")) return "Mockito";
        if (group.equals("org.assertj")) return "AssertJ";
        if (group.equals("org.springframework.boot") && artifact.equals("spring-boot-starter-test")) return "Spring Boot Test";
        if (group.equals("org.testcontainers")) return "Testcontainers";
        return null;
    }

    private static String normalizeJava(String value) {
        String text = value.strip();
        Matcher legacy = Pattern.compile("1\\.(\\d)").matcher(text);
        return legacy.matches() ? text : text.replaceFirst("^JavaVersion\\.VERSION_", "").replace('_', '.');
    }

    // ---------------------------------------------------------------- Gradle

    private static final Pattern GRADLE_PLUGIN_ID = Pattern.compile(
        "^\\s*id\\s*\\(?\\s*[\"']([\\w.\\-]+)[\"']\\s*\\)?(?:\\s+version\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*\\)?)?");
    private static final Pattern GRADLE_CORE_PLUGIN = Pattern.compile(
        "^\\s*`?(java|java-library|application|groovy|maven-publish|jacoco|checkstyle|pmd|war|ear|idea|eclipse)`?\\s*$");
    private static final Pattern GRADLE_KOTLIN_PLUGIN = Pattern.compile("^\\s*kotlin\\s*\\(\\s*\"([\\w.\\-]+)\"\\s*\\)");
    private static final Pattern GRADLE_APPLY_PLUGIN = Pattern.compile("apply\\s*\\(?\\s*plugin\\s*[:=]\\s*[\"']([\\w.\\-]+)[\"']");
    private static final Pattern GRADLE_DEPENDENCY = Pattern.compile(
        "^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|testCompileOnly"
            + "|annotationProcessor|testAnnotationProcessor|compile|testCompile|developmentOnly|integrationTestImplementation)"
            + "\\s*\\(?\\s*(?:(?:platform|enforcedPlatform)\\s*\\(\\s*)?(?:[\"']([^\"'\\s]+:[^\"'\\s]+)[\"']|(libs\\.[\\w.]+))");
    private static final Pattern GRADLE_JAVA_VERSION = Pattern.compile(
        "(?:(sourceCompatibility|targetCompatibility)\\s*=\\s*(?:JavaVersion\\.VERSION_)?[\"']?(1[._]\\d|\\d{1,2})\\b"
            + "|JavaLanguageVersion\\.of\\s*\\(\\s*(\\d{1,2})\\s*\\)"
            + "|jvmToolchain\\s*\\(\\s*(\\d{1,2})\\s*\\)"
            + "|options\\.release(?:\\.set\\s*\\(|\\s*=)\\s*(\\d{1,2}))");
    private static final Pattern GRADLE_COORDINATE = Pattern.compile("^\\s*(group|version)\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern GRADLE_MAIN_CLASS = Pattern.compile(
        "mainClass(?:Name)?(?:\\.set\\s*\\(|\\s*=)\\s*[\"']([\\w.$]+)[\"']");

    // ---------------------------------------------------------------- Gradle version catalog

    static final String CATALOG_PATH = "gradle/libs.versions.toml";

    private record CatalogEntry(String value, int line) {}

    /** Минимальный разбор однострочных записей {@code libs.versions.toml}: [versions], [libraries], [plugins]. */
    private record Catalog(Map<String, CatalogEntry> libraries, Map<String, CatalogEntry> plugins) {
        static final Catalog EMPTY = new Catalog(Map.of(), Map.of());
        private static final Pattern ENTRY = Pattern.compile("^\\s*([A-Za-z][\\w.-]*)\\s*=\\s*(.+?)\\s*$");

        static Catalog parse(String content) {
            String[] lines = lines(content);
            Map<String, String> versions = new LinkedHashMap<>();
            Map<String, CatalogEntry> libraries = new LinkedHashMap<>();
            Map<String, CatalogEntry> plugins = new LinkedHashMap<>();
            String section = "";
            for (int pass = 0; pass < 2; pass++) {
                section = "";
                for (int index = 0; index < lines.length; index++) {
                    String line = lines[index].replaceFirst("\\s+#.*$", "").strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("[")) {
                        section = line.replaceAll("[\\[\\]\\s]", "");
                        continue;
                    }
                    Matcher entry = ENTRY.matcher(line);
                    if (!entry.find()) continue;
                    String alias = entry.group(1);
                    String value = entry.group(2);
                    if (pass == 0) {
                        if (section.equals("versions")) versions.put(alias, firstQuoted(value));
                        continue;
                    }
                    String resolved = switch (section) {
                        case "libraries" -> library(value, versions);
                        case "plugins" -> plugin(value, versions);
                        default -> null;
                    };
                    if (resolved == null) continue;
                    (section.equals("libraries") ? libraries : plugins).put(accessor(alias), new CatalogEntry(resolved, index + 1));
                }
            }
            return new Catalog(Map.copyOf(libraries), Map.copyOf(plugins));
        }

        CatalogEntry library(String accessor) {
            return libraries.get(accessor.substring("libs.".length()));
        }

        CatalogEntry plugin(String accessor) {
            return plugins.get(accessor.substring("libs.plugins.".length()));
        }

        private static String library(String value, Map<String, String> versions) {
            if (value.startsWith("\"")) return firstQuoted(value);
            String module = attribute(value, "module");
            if (module == null) {
                String group = attribute(value, "group");
                String name = attribute(value, "name");
                if (group == null || name == null) return null;
                module = group + ":" + name;
            }
            String version = version(value, versions);
            return version == null ? module : module + ":" + version;
        }

        private static String plugin(String value, Map<String, String> versions) {
            if (value.startsWith("\"")) return firstQuoted(value);
            String id = attribute(value, "id");
            if (id == null) return null;
            String version = version(value, versions);
            return version == null ? id : id + ":" + version;
        }

        private static String version(String value, Map<String, String> versions) {
            String reference = attribute(value, "version.ref");
            if (reference != null) return versions.get(reference);
            return attribute(value, "version");
        }

        private static String attribute(String value, String name) {
            Matcher matcher = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(value);
            return matcher.find() ? matcher.group(1) : null;
        }

        private static String firstQuoted(String value) {
            Matcher matcher = Pattern.compile("\"([^\"]*)\"").matcher(value);
            return matcher.find() ? matcher.group(1) : value;
        }

        /** Gradle превращает {@code junit-jupiter} и {@code junit_jupiter} в {@code libs.junit.jupiter}. */
        private static String accessor(String alias) {
            return alias.replaceAll("[-_.]", ".").toLowerCase(Locale.ROOT);
        }
    }

    // ---------------------------------------------------------------- Application frameworks

    private static String applicationFramework(String group, String artifact) {
        if (group.equals("org.springframework.boot") && artifact.startsWith("spring-boot")) return "Spring Boot";
        if (group.startsWith("io.quarkus")) return "Quarkus";
        if (group.startsWith("io.micronaut")) return "Micronaut";
        return null;
    }

    private static String pluginFramework(String id) {
        if (id.equals("org.springframework.boot")) return "Spring Boot";
        if (id.equals("io.quarkus")) return "Quarkus";
        if (id.startsWith("io.micronaut.")) return "Micronaut";
        return null;
    }

    private static void pluginFrameworkFact(String plugin, Source source, Collector collector) {
        String framework = pluginFramework(plugin.split(":")[0]);
        if (framework != null) collector.add(Category.FRAMEWORK, framework, source);
    }

    private static final Pattern GRADLE_PLUGIN_ALIAS = Pattern.compile("^\\s*alias\\s*\\(\\s*(libs\\.plugins\\.[\\w.]+)\\s*\\)");

    private static void dependencyFacts(
        String coordinates, String suffix, List<Source> sources, Collector collector
    ) {
        String[] parts = coordinates.split(":");
        for (Source source : sources) collector.add(Category.DEPENDENCY, coordinates + suffix, source);
        if (parts.length < 2) return;
        Source primary = sources.get(0);
        String framework = testFramework(parts[0], parts[1]);
        if (framework != null) collector.add(Category.TEST_FRAMEWORK, framework,
            new Source(primary.origin(), primary.path(), primary.line(), "зависимость " + parts[0] + ":" + parts[1]));
        String application = applicationFramework(parts[0], parts[1]);
        if (application != null) collector.add(Category.FRAMEWORK, application,
            new Source(primary.origin(), primary.path(), primary.line(), "зависимость " + parts[0] + ":" + parts[1]));
    }

    private static void gradle(
        String rel, String content, boolean primary, List<Path> files, Path root, Catalog catalog, Collector collector
    ) {
        String[] lines = lines(content);
        collector.add(Category.BUILD_SYSTEM, "Gradle", new Source(Origin.CONFIG, rel, 0, null));
        int pluginsDepth = -1;
        int depth = 0;
        int added = 0;
        for (int index = 0; index < lines.length && added < MAX_FACTS_PER_FILE; index++) {
            String line = stripLineComment(lines[index]);
            int number = index + 1;
            if (pluginsDepth < 0 && line.matches("^\\s*plugins\\s*\\{.*")) pluginsDepth = depth;
            boolean inPlugins = pluginsDepth >= 0;
            Matcher matcher;
            if ((matcher = GRADLE_PLUGIN_ID.matcher(line)).find()) {
                String plugin = matcher.group(1) + (matcher.group(2) == null ? "" : ":" + matcher.group(2));
                collector.add(Category.PLUGIN, plugin, new Source(Origin.CONFIG, rel, number, null));
                pluginFrameworkFact(plugin, new Source(Origin.CONFIG, rel, number, "плагин " + plugin), collector);
                added++;
            } else if (inPlugins && (matcher = GRADLE_CORE_PLUGIN.matcher(line)).find()) {
                collector.add(Category.PLUGIN, matcher.group(1), new Source(Origin.CONFIG, rel, number, null));
                added++;
            } else if (inPlugins && (matcher = GRADLE_KOTLIN_PLUGIN.matcher(line)).find()) {
                collector.add(Category.PLUGIN, "org.jetbrains.kotlin." + matcher.group(1), new Source(Origin.CONFIG, rel, number, null));
                added++;
            }
            if ((matcher = GRADLE_APPLY_PLUGIN.matcher(line)).find()) {
                collector.add(Category.PLUGIN, matcher.group(1), new Source(Origin.CONFIG, rel, number, "apply plugin"));
                added++;
            }
            if ((matcher = GRADLE_PLUGIN_ALIAS.matcher(line)).find()) {
                String accessor = matcher.group(1);
                CatalogEntry entry = catalog.plugin(accessor);
                if (entry == null) {
                    collector.add(Category.PLUGIN, accessor,
                        new Source(Origin.CONFIG, rel, number, "не найден в " + CATALOG_PATH));
                } else {
                    collector.add(Category.PLUGIN, entry.value(), new Source(Origin.CONFIG, rel, number, "каталог версий: " + accessor));
                    collector.add(Category.PLUGIN, entry.value(), new Source(Origin.CONFIG, CATALOG_PATH, entry.line(), null));
                    pluginFrameworkFact(entry.value(), new Source(Origin.CONFIG, rel, number, "плагин " + entry.value()), collector);
                }
                added++;
            }
            if ((matcher = GRADLE_DEPENDENCY.matcher(line)).find()) {
                String suffix = " [" + matcher.group(1) + "]";
                if (matcher.group(2) != null) {
                    dependencyFacts(matcher.group(2), suffix, List.of(new Source(Origin.CONFIG, rel, number, null)), collector);
                } else {
                    String accessor = matcher.group(3);
                    CatalogEntry entry = catalog.library(accessor);
                    if (entry == null) {
                        collector.add(Category.DEPENDENCY, accessor + suffix,
                            new Source(Origin.CONFIG, rel, number, "не найден в " + CATALOG_PATH));
                    } else {
                        dependencyFacts(entry.value(), suffix, List.of(
                            new Source(Origin.CONFIG, rel, number, "каталог версий: " + accessor),
                            new Source(Origin.CONFIG, CATALOG_PATH, entry.line(), null)), collector);
                    }
                }
                added++;
            }
            if ((matcher = GRADLE_JAVA_VERSION.matcher(line)).find()) {
                String version = matcher.group(2) != null ? matcher.group(2).replace('_', '.')
                    : matcher.group(3) != null ? matcher.group(3)
                    : matcher.group(4) != null ? matcher.group(4) : matcher.group(5);
                String basis = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(3) != null ? "JavaLanguageVersion.of"
                    : matcher.group(4) != null ? "jvmToolchain" : "options.release";
                collector.add(Category.JAVA_VERSION, version, new Source(Origin.CONFIG, rel, number, basis));
                added++;
            }
            if (depth == 0 && (matcher = GRADLE_COORDINATE.matcher(line)).find()) {
                collector.add(Category.ARTIFACT, matcher.group(1) + " = " + matcher.group(2),
                    new Source(Origin.CONFIG, rel, number, null));
                added++;
            }
            if ((matcher = GRADLE_MAIN_CLASS.matcher(line)).find()) {
                collector.add(Category.MAIN_CLASS, matcher.group(1), new Source(Origin.CONFIG, rel, number, null));
                added++;
            }
            if (line.contains("useJUnitPlatform()")) {
                collector.add(Category.TEST_FRAMEWORK, "JUnit Platform",
                    new Source(Origin.CONFIG, rel, number, "useJUnitPlatform()"));
            } else if (line.contains("useTestNG()")) {
                collector.add(Category.TEST_FRAMEWORK, "TestNG", new Source(Origin.CONFIG, rel, number, "useTestNG()"));
            }
            for (char character : line.toCharArray()) {
                if (character == '{') depth++;
                else if (character == '}') depth = Math.max(0, depth - 1);
            }
            if (pluginsDepth >= 0 && depth <= pluginsDepth) pluginsDepth = -1;
        }
        if (!primary) return;
        String directory = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/') + 1) : "";
        List<String> paths = files.stream().map(path -> relative(root, path)).toList();
        boolean unixWrapper = paths.contains(directory + "gradlew");
        boolean windowsWrapper = paths.contains(directory + "gradlew.bat");
        String gradle;
        if (isWindows() && windowsWrapper) gradle = ".\\gradlew.bat";
        else if (unixWrapper) gradle = "./gradlew";
        else if (windowsWrapper) gradle = ".\\gradlew.bat";
        else gradle = "gradle";
        if (unixWrapper || windowsWrapper) {
            collector.add(Category.WRAPPER, "Gradle Wrapper (gradlew)", new Source(Origin.FILESYSTEM,
                directory + (gradle.endsWith(".bat") ? "gradlew.bat" : "gradlew"), 0, null));
        }
        Source standard = new Source(Origin.CONFIG, rel, 0, "стандартная задача Gradle");
        collector.add(Category.TEST_COMMAND, "`" + gradle + " test`", standard);
        collector.add(Category.BUILD_COMMAND, "`" + gradle + " build`", standard);
    }

    private static void gradleSettings(String rel, String content, Collector collector) {
        String[] lines = lines(content);
        Pattern name = Pattern.compile("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']");
        Pattern include = Pattern.compile("^\\s*include\\b(.*)");
        Pattern quoted = Pattern.compile("[\"']:?([^\"']+)[\"']");
        for (int index = 0; index < lines.length; index++) {
            String line = stripLineComment(lines[index]);
            Matcher matcher = name.matcher(line);
            if (matcher.find()) {
                collector.add(Category.ARTIFACT, "rootProject.name = " + matcher.group(1),
                    new Source(Origin.CONFIG, rel, index + 1, null));
            }
            matcher = include.matcher(line);
            if (matcher.find()) {
                Matcher modules = quoted.matcher(matcher.group(1));
                while (modules.find()) {
                    collector.add(Category.MODULE, modules.group(1), new Source(Origin.CONFIG, rel, index + 1, null));
                }
            }
        }
    }

    private static String stripLineComment(String line) {
        int comment = line.indexOf("//");
        while (comment >= 0) {
            String before = line.substring(0, comment);
            long quotes = before.chars().filter(character -> character == '"' || character == '\'').count();
            if (quotes % 2 == 0 && !before.endsWith(":")) return before;
            comment = line.indexOf("//", comment + 2);
        }
        return line;
    }

    // ---------------------------------------------------------------- Wrappers, settings

    private static void wrapperProperties(String rel, String content, String versionPattern, String label, Collector collector) {
        String[] lines = lines(content);
        Pattern version = Pattern.compile(versionPattern);
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].strip().startsWith("distributionUrl")) continue;
            Matcher matcher = version.matcher(lines[index]);
            if (matcher.find()) {
                collector.add(Category.WRAPPER, label + " " + matcher.group(1),
                    new Source(Origin.CONFIG, rel, index + 1, "distributionUrl"));
            }
        }
    }

    private static void lineSettings(String rel, String content, Collector collector) {
        String[] lines = lines(content);
        for (int index = 0; index < lines.length && index < 20; index++) {
            String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            collector.add(Category.SETTING, "`" + truncate(line, 120) + "`", new Source(Origin.CONFIG, rel, index + 1, null));
        }
    }

    private static void gradleProperties(String rel, String content, Collector collector) {
        String[] lines = lines(content);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int separator = indexOfSeparator(line);
            String key = separator < 0 ? line : line.substring(0, separator).strip();
            String value = separator < 0 ? "" : line.substring(separator + 1).strip();
            boolean visible = key.startsWith("org.gradle.") || key.equals("kotlin.code.style");
            collector.add(Category.SETTING, visible ? "`" + key + "=" + truncate(value, 80) + "`" : "`" + key + "` (значение скрыто)",
                new Source(Origin.CONFIG, rel, index + 1, null));
        }
    }

    private static void toolchainFile(String rel, String name, String content, Collector collector) {
        String[] lines = lines(content);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            String version = null;
            if (name.equals(".java-version") && !line.isEmpty() && !line.startsWith("#")) version = line;
            if (name.equals(".sdkmanrc") && line.startsWith("java=")) version = line.substring(5);
            if (name.equals(".tool-versions") && line.startsWith("java ")) version = line.substring(5).strip();
            if (version != null) {
                collector.add(Category.TOOLCHAIN, "JDK " + truncate(version, 60),
                    new Source(Origin.CONFIG, rel, index + 1, "версия JDK для локальных инструментов"));
                return;
            }
        }
    }

    private static void applicationConfig(String rel, String content, Collector collector) {
        String[] lines = lines(content);
        List<String> keys = new ArrayList<>();
        boolean yaml = !rel.endsWith(".properties");
        int firstLine = 0;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String key = null;
            if (yaml) {
                Matcher matcher = Pattern.compile("^([A-Za-z][\\w.-]*):").matcher(line);
                if (matcher.find()) key = matcher.group(1);
            } else {
                String stripped = line.strip();
                if (!stripped.isEmpty() && !stripped.startsWith("#") && !stripped.startsWith("!")) {
                    int separator = indexOfSeparator(stripped);
                    key = separator < 0 ? stripped : stripped.substring(0, separator).strip();
                }
            }
            if (key == null || keys.contains(key)) continue;
            if (firstLine == 0) firstLine = index + 1;
            keys.add(key);
        }
        if (keys.isEmpty()) return;
        String shown = String.join(", ", keys.stream().limit(12).toList()) + (keys.size() > 12 ? ", …" : "");
        collector.add(Category.SETTING, "Ключи настроек приложения (" + keys.size() + "): " + shown,
            new Source(Origin.CONFIG, rel, firstLine, yaml ? "ключи верхнего уровня; значения скрыты" : "значения скрыты"));
    }

    private static int indexOfSeparator(String line) {
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '\\') {
                index++;
            } else if (character == '=' || character == ':') {
                return index;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- CI

    private static final Pattern YAML_COMMAND = Pattern.compile(
        "^(\\s*)(?:-\\s+)?(run|script|before_script|after_script|bash|pwsh|powershell|sh|command)\\s*:\\s*(.*)$");
    private static final Pattern YAML_LIST_ITEM = Pattern.compile("^(\\s*)-\\s+(.+)$");

    private static void ciYaml(String rel, String content, Collector collector) {
        String[] lines = lines(content);
        boolean github = rel.startsWith(".github/workflows/");
        String label = github ? "GitHub Actions" : rel.equals(".gitlab-ci.yml") ? "GitLab CI"
            : rel.startsWith(".circleci/") ? "CircleCI" : rel.equals("azure-pipelines.yml") ? "Azure Pipelines"
            : "Bitbucket Pipelines";
        String workflowName = null;
        int nameLine = 0;
        int added = 0;
        for (int index = 0; index < lines.length && added < MAX_FACTS_PER_FILE; index++) {
            String line = lines[index];
            String stripped = line.strip();
            if (stripped.startsWith("#") || stripped.isEmpty()) continue;
            int number = index + 1;
            if (github && workflowName == null && line.startsWith("name:")) {
                workflowName = unquote(line.substring(5).strip());
                nameLine = number;
            }
            if (github && line.startsWith("on:")) {
                String inline = line.substring(3).strip();
                List<String> triggers = new ArrayList<>();
                if (!inline.isEmpty()) {
                    for (String trigger : inline.replaceAll("[\\[\\]]", "").split(",")) {
                        if (!trigger.isBlank()) triggers.add(unquote(trigger.strip()));
                    }
                } else {
                    for (int next = index + 1; next < lines.length; next++) {
                        String child = lines[next];
                        if (child.isBlank() || child.strip().startsWith("#")) continue;
                        if (!Character.isWhitespace(child.charAt(0))) break;
                        Matcher key = Pattern.compile("^ {1,4}([\\w-]+):").matcher(child);
                        if (key.find() && indentation(child) == indentation(firstNonBlank(lines, index + 1))) {
                            triggers.add(key.group(1));
                        }
                    }
                }
                if (!triggers.isEmpty()) {
                    collector.add(Category.CI_TRIGGER, label + " запускается на: " + String.join(", ", triggers),
                        new Source(Origin.CONFIG, rel, number, null));
                }
            }
            Matcher setupJava = Pattern.compile("uses:\\s*(actions/setup-java@[\\w.\\-]+)").matcher(line);
            if (setupJava.find()) {
                collector.add(Category.CI_JAVA, "Установка JDK через " + setupJava.group(1),
                    new Source(Origin.CONFIG, rel, number, null));
                added++;
            }
            Matcher javaVersion = Pattern.compile("^\\s*java-version\\s*:\\s*['\"]?([\\w.\\-]+)").matcher(line);
            if (javaVersion.find()) {
                collector.add(Category.CI_JAVA, "Java в CI: " + javaVersion.group(1), new Source(Origin.CONFIG, rel, number, null));
                added++;
            }
            Matcher command = YAML_COMMAND.matcher(line);
            if (!command.find()) continue;
            int keyIndent = indentation(line);
            String value = command.group(3).strip();
            if (value.isEmpty() || value.matches("[|>][+-]?")) {
                for (int next = index + 1; next < lines.length; next++) {
                    String child = lines[next];
                    if (child.isBlank()) continue;
                    if (indentation(child) <= keyIndent) break;
                    String text = child.strip();
                    Matcher item = YAML_LIST_ITEM.matcher(child);
                    if (value.isEmpty() && item.find()) text = item.group(2).strip();
                    else if (value.isEmpty()) break;
                    if (!text.startsWith("#")) {
                        ciCommand(label, unquote(text), new Source(Origin.CONFIG, rel, next + 1, null), collector);
                        added++;
                    }
                    index = next;
                }
            } else {
                ciCommand(label, unquote(value), new Source(Origin.CONFIG, rel, number, null), collector);
                added++;
            }
        }
        collector.add(Category.CI_WORKFLOW, label + (workflowName == null ? "" : ": " + workflowName),
            new Source(Origin.CONFIG, rel, nameLine, null));
    }

    private static void jenkinsfile(String rel, String content, Collector collector) {
        String[] lines = lines(content);
        Pattern step = Pattern.compile("\\b(?:sh|bat|powershell)\\s*\\(?\\s*(?:script\\s*:\\s*)?(?:'''|\"\"\"|['\"])(.+?)(?:'''|\"\"\"|['\"])");
        for (int index = 0; index < lines.length; index++) {
            Matcher matcher = step.matcher(lines[index]);
            if (matcher.find()) {
                ciCommand("Jenkins", matcher.group(1).strip(), new Source(Origin.CONFIG, rel, index + 1, null), collector);
            }
        }
        collector.add(Category.CI_WORKFLOW, "Jenkins", new Source(Origin.CONFIG, rel, 0, null));
    }

    private static void ciCommand(String label, String command, Source source, Collector collector) {
        if (command.isBlank()) return;
        String shown = "`" + truncate(command, 160) + "`";
        collector.add(Category.CI_COMMAND, shown, source);
        if (isTestCommand(command)) {
            collector.add(Category.TEST_COMMAND, shown, new Source(source.origin(), source.path(), source.line(), "шаг " + label));
        } else if (isBuildCommand(command)) {
            collector.add(Category.BUILD_COMMAND, shown, new Source(source.origin(), source.path(), source.line(), "шаг " + label));
        }
    }

    private static boolean isTestCommand(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\b(mvnw?(\\.cmd)?|gradlew?(\\.bat)?|gradle|mvn)\\b.*\\b(test|verify|check)\\b.*")
            || lower.matches("(.*[\\s/\\\\])?test[\\w.-]*\\.(ps1|sh|bat|cmd)\\b.*");
    }

    private static boolean isBuildCommand(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\b(mvnw?(\\.cmd)?|gradlew?(\\.bat)?|gradle|mvn)\\b.*\\b(package|install|build|assemble|jar)\\b.*")
            || lower.matches("(.*[\\s/\\\\])?build[\\w.-]*\\.(ps1|sh|bat|cmd)\\b.*");
    }

    // ---------------------------------------------------------------- Scripts and documentation

    private static void script(String rel, String name, Collector collector) {
        String lower = name.toLowerCase(Locale.ROOT);
        String command = lower.endsWith(".ps1") || lower.endsWith(".bat") || lower.endsWith(".cmd") ? ".\\" + name : "./" + name;
        Source source = new Source(Origin.FILESYSTEM, rel, 0, "по имени скрипта");
        if (lower.startsWith("test")) collector.add(Category.TEST_COMMAND, "`" + command + "`", source);
        else collector.add(Category.BUILD_COMMAND, "`" + command + "`", source);
    }

    private static void readmeCommands(String rel, String content, Collector collector) {
        String[] lines = lines(content);
        boolean fenced = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (!fenced) continue;
            String command = line.replaceFirst("^(?:\\$|>|PS>)\\s*", "");
            if (!command.matches("(?i)(?:\\.[\\\\/])?(?:mvnw?(?:\\.cmd)?|gradlew?(?:\\.bat)?|mvn|gradle|(?:test|build)[\\w.-]*\\.(?:ps1|sh|bat|cmd))\\b.*")) {
                continue;
            }
            Source source = new Source(Origin.DOCUMENTATION, rel, index + 1, "команда из документации");
            if (isTestCommand(command)) collector.add(Category.TEST_COMMAND, "`" + truncate(command, 160) + "`", source);
            else if (isBuildCommand(command)) collector.add(Category.BUILD_COMMAND, "`" + truncate(command, 160) + "`", source);
        }
    }

    // ---------------------------------------------------------------- Helpers

    private static final class Collector {
        private final Map<String, Fact> facts = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();

        void add(Category category, String value, Source source) {
            String key = category + "\u0000" + commandKey(category, value);
            Fact existing = facts.get(key);
            if (existing == null) {
                facts.put(key, new Fact(category, value, List.of(source)));
            } else if (existing.sources().size() < 4 && !existing.sources().contains(source)) {
                List<Source> sources = new ArrayList<>(existing.sources());
                sources.add(source);
                facts.put(key, new Fact(category, existing.value(), sources));
            }
        }

        void warn(String warning) {
            if (!warnings.contains(warning)) warnings.add(warning);
        }

        Result result() {
            return new Result(List.copyOf(facts.values()), List.copyOf(warnings));
        }

        private static String commandKey(Category category, String value) {
            if (category != Category.TEST_COMMAND && category != Category.BUILD_COMMAND) return value;
            return (" " + value.replace("`", "").replace(".\\", "").replace("./", "") + " ")
                .replace("mvnw.cmd ", "mvnw ").replace("gradlew.bat ", "gradlew ")
                .replaceAll("\\s(?:-B|-q|-e|--batch-mode|--quiet|--no-daemon)(?=\\s)", " ")
                .replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String firstNonBlank(String[] lines, int start) {
        for (int index = start; index < lines.length; index++) {
            if (!lines[index].isBlank() && !lines[index].strip().startsWith("#")) return lines[index];
        }
        return "";
    }

    private static int indentation(String line) {
        int count = 0;
        while (count < line.length() && (line.charAt(count) == ' ' || line.charAt(count) == '\t')) count++;
        return count;
    }

    private static String unquote(String value) {
        String text = value.strip();
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'"))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static String truncate(String value, int max) {
        String clean = value.replaceAll("[\\p{Cntrl}&&[^\t]]", "");
        return clean.length() <= max ? clean : clean.substring(0, max - 1) + "…";
    }

    private static String[] lines(String content) {
        String[] result = content.split("\\R", -1);
        if (result.length > 1 && result[result.length - 1].isEmpty()) {
            return java.util.Arrays.copyOf(result, result.length - 1);
        }
        return result;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }
}
