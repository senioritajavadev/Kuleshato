package kuleshato;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Сводка для новичка: что выполнит проверка ({@code mvn test}, {@code gradle test}) из файлов сборки — плагины,
 * сторонние репозитории, запуск команд. По ней проще решить, разрешать ли автозапуск, чем по одному имени файла.
 */
final class BuildSummary {
    /** Плагины, которые запускают любые команды или скрипты на компьютере. */
    private static final Set<String> RUNS_COMMANDS = Set.of(
        "exec-maven-plugin", "maven-antrun-plugin", "gmavenplus-plugin", "groovy-maven-plugin", "frontend-maven-plugin",
        "maven-invoker-plugin", "properties-maven-plugin", "com.github.node-gradle.node", "com.moowork.node",
        "com.github.eirnym.js2p", "org.hidetake.ssh");
    private static final Set<String> STANDARD_HOSTS = Set.of(
        "repo.maven.apache.org", "repo1.maven.org", "plugins.gradle.org", "maven.google.com", "dl.google.com",
        "central.sonatype.com", "oss.sonatype.org", "s01.oss.sonatype.org");
    /** Встроенные плагины Gradle: идут вместе с Gradle, ничего не скачивают. */
    private static final Set<String> GRADLE_CORE = Set.of(
        "java", "java-library", "application", "groovy", "scala", "jacoco", "checkstyle", "pmd", "idea", "eclipse",
        "maven-publish", "signing", "war", "ear", "distribution", "base", "java-platform", "java-test-fixtures",
        "jvm-test-suite", "version-catalog", "java-gradle-plugin", "antlr", "codenarc", "ivy-publish", "project-report");
    private static final Pattern GRADLE_ID = Pattern.compile("\\bid\\s*\\(?\\s*[\"']([^\"']+)[\"']");
    private static final Pattern GRADLE_KOTLIN = Pattern.compile("\\bkotlin\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern GRADLE_APPLY = Pattern.compile("apply\\s*\\(?\\s*plugin\\s*[:=]\\s*[\"']([^\"']+)[\"']");
    private static final Pattern GRADLE_BARE = Pattern.compile("(?m)^\\s*`?([a-z][a-z-]*)`?\\s*$");
    private static final Pattern GRADLE_URL = Pattern.compile("\\burl\\s*[=(]?\\s*(?:uri\\s*\\(\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern GRADLE_COMMANDS = Pattern.compile(
        "\\bcommandLine\\b|\\bexec\\s*\\{|\\btype\\s*:\\s*Exec\\b|<Exec>|\\(Exec\\)|\\.execute\\(\\s*\\)|ProcessBuilder|Runtime\\.getRuntime");

    private BuildSummary() {}

    /** Сводка по найденным файлам сборки или пустая строка, если среди {@code paths} их нет. */
    static String summarize(Path root, List<String> paths) {
        StringBuilder text = new StringBuilder();
        boolean warned = false;
        for (String path : paths) {
            if (!WorkspaceTrust.isBuildFile(path)) continue;
            Path file = root.resolve(path);
            List<Item> items;
            try {
                if (!Files.isRegularFile(file) || Files.size(file) > 1_000_000) continue;
                String content = Files.readString(file);
                items = path.endsWith(".xml") ? maven(content) : gradle(content);
            } catch (IOException | RuntimeException error) {
                text.append("  ").append(path).append(": не удалось разобрать — посмотрите файл сами.\n");
                warned = true;
                continue;
            }
            text.append("  ").append(path).append(":\n");
            if (items.isEmpty()) text.append("    ничего, кроме стандартной сборки и тестов\n");
            for (Item item : items) {
                text.append("    ").append(item.warning() ? "⚠ " : "• ").append(item.text()).append('\n');
                warned |= item.warning();
            }
        }
        if (text.isEmpty()) return "";
        return "Что выполнится при проверке:\n" + text
            + (warned
                ? "Пункты с ⚠ могут запускать команды на вашем компьютере или скачивать код с непривычных адресов — "
                    + "разрешайте, только если знаете, зачем они в проекте."
                : "Плагинов, которые запускают команды при проверке, и сторонних репозиториев не найдено.");
    }

    record Item(String text, boolean warning) {}

    static List<Item> maven(String pom) throws IOException {
        Element project;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // без «[Fatal Error]» в консоли
            project = builder.parse(new InputSource(new StringReader(pom))).getDocumentElement();
        } catch (Exception error) {
            throw new IOException("pom.xml не разбирается", error);
        }
        List<Item> items = new ArrayList<>();
        Set<String> standard = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        NodeList plugins = project.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            // pluginManagement только задаёт версии; выполняются плагины из build/plugins.
            if (insidePluginManagement(plugin)) continue;
            String artifact = child(plugin, "artifactId");
            if (artifact.isEmpty() || !seen.add(artifact)) continue;
            String group = child(plugin, "groupId");
            if (RUNS_COMMANDS.contains(artifact)) {
                // Без <executions> плагин не привязан к фазе и при mvn test не выполняется — только по явной команде.
                if (plugin.getElementsByTagName("execution").getLength() == 0) {
                    items.add(new Item("плагин " + artifact + " — запускает команды, но только если вызвать его вручную "
                        + "(mvn " + artifact.replaceFirst("^maven-(.+)-plugin$", "$1").replaceFirst("-maven-plugin$", "") + ":…); при проверке не выполняется", false));
                } else {
                    items.add(new Item("плагин " + artifact + " — при сборке запускает " + mavenCommand(plugin, properties(project)), true));
                }
            } else if (group.isEmpty() || group.equals("org.apache.maven.plugins") || group.equals("org.codehaus.mojo")) {
                standard.add(artifact);
            } else {
                items.add(new Item("сторонний плагин " + group + ":" + artifact + " — выполняется при сборке", false));
            }
        }
        if (!standard.isEmpty()) items.add(0, new Item("стандартные плагины Maven: " + String.join(", ", standard), false));
        for (String tag : List.of("repository", "pluginRepository")) {
            NodeList repositories = project.getElementsByTagName(tag);
            for (int i = 0; i < repositories.getLength(); i++) {
                String url = child((Element) repositories.item(i), "url");
                if (!url.isEmpty()) repository(items, url, tag.equals("pluginRepository"));
            }
        }
        return items;
    }

    static List<Item> gradle(String script) {
        String code = script.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)(?<!:)//.*$", ""); // «https://» — не комментарий
        List<Item> items = new ArrayList<>();
        Set<String> plugins = new LinkedHashSet<>();
        for (Pattern pattern : List.of(GRADLE_ID, GRADLE_APPLY)) {
            Matcher matcher = pattern.matcher(code);
            while (matcher.find()) plugins.add(matcher.group(1));
        }
        Matcher kotlin = GRADLE_KOTLIN.matcher(code);
        while (kotlin.find()) plugins.add("org.jetbrains.kotlin." + kotlin.group(1));
        String block = block(code, "plugins");
        Matcher bare = GRADLE_BARE.matcher(block);
        while (bare.find()) plugins.add(bare.group(1));
        Set<String> core = new LinkedHashSet<>();
        for (String plugin : plugins) {
            if (GRADLE_CORE.contains(plugin)) core.add(plugin);
            else if (RUNS_COMMANDS.contains(plugin)) items.add(new Item("плагин " + plugin + " — запускает команды или скрипты", true));
            else items.add(new Item("сторонний плагин " + plugin + " — выполняется при сборке", false));
        }
        if (!core.isEmpty()) items.add(0, new Item("встроенные плагины Gradle: " + String.join(", ", core), false));
        if (GRADLE_COMMANDS.matcher(code).find()) {
            items.add(new Item("скрипт сборки сам запускает команды (exec, commandLine) — посмотрите, какие", true));
        }
        Matcher url = GRADLE_URL.matcher(code);
        Set<String> urls = new LinkedHashSet<>();
        while (url.find()) urls.add(url.group(1));
        for (String address : urls) repository(items, address, false);
        if (code.contains("mavenLocal()")) {
            items.add(new Item("локальный репозиторий ~/.m2 — берёт библиотеки, собранные на этом компьютере", false));
        }
        return items;
    }

    private static void repository(List<Item> items, String url, boolean plugins) {
        String host = host(url);
        String what = plugins ? "репозиторий плагинов " : "репозиторий ";
        if (url.toLowerCase(Locale.ROOT).startsWith("http://")) {
            items.add(new Item(what + url + " — без шифрования (http): скачанный код могут подменить по дороге", true));
        } else if (!STANDARD_HOSTS.contains(host)) {
            items.add(new Item("сторонний " + what + url + " — оттуда скачиваются библиотеки и плагины", true));
        }
    }

    private static String host(String url) {
        String rest = url.replaceFirst("^[a-zA-Z]+://", "");
        int end = rest.indexOf('/');
        String host = end < 0 ? rest : rest.substring(0, end);
        int at = host.indexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        return (colon < 0 ? host : host.substring(0, colon)).toLowerCase(Locale.ROOT);
    }

    private static String mavenCommand(Element plugin, java.util.Map<String, String> properties) {
        String executable = resolve(descendant(plugin, "executable"), properties);
        if (!executable.isEmpty()) return "команду " + executable;
        String mainClass = resolve(descendant(plugin, "mainClass"), properties);
        if (!mainClass.isEmpty()) return "Java-класс " + mainClass;
        return "команды или скрипты";
    }

    /** {@code ${main.class}} → значение из {@code <properties>}; неизвестное свойство — пустая строка. */
    private static String resolve(String value, java.util.Map<String, String> properties) {
        Matcher property = Pattern.compile("\\$\\{([^}]+)}").matcher(value);
        StringBuilder result = new StringBuilder();
        while (property.find()) {
            String known = properties.get(property.group(1));
            if (known == null) return "";
            property.appendReplacement(result, Matcher.quoteReplacement(known));
        }
        return property.appendTail(result).toString().strip();
    }

    private static java.util.Map<String, String> properties(Element project) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        for (Node node = project.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element) || !element.getTagName().equals("properties")) continue;
            for (Node item = element.getFirstChild(); item != null; item = item.getNextSibling()) {
                if (item instanceof Element property) result.put(property.getTagName(), property.getTextContent().strip());
            }
        }
        return result;
    }

    private static boolean insidePluginManagement(Node node) {
        for (Node parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if ("pluginManagement".equals(parent.getNodeName())) return true;
        }
        return false;
    }

    private static String child(Element element, String name) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && child.getTagName().equals(name)) return child.getTextContent().strip();
        }
        return "";
    }

    private static String descendant(Element element, String name) {
        NodeList nodes = element.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }

    /** Содержимое блока {@code name { … }} верхнего уровня или пустая строка. */
    private static String block(String code, String name) {
        Matcher start = Pattern.compile("(?m)^\\s*" + name + "\\s*\\{").matcher(code);
        if (!start.find()) return "";
        int depth = 1;
        int from = start.end();
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return code.substring(from, i);
        }
        return "";
    }
}
