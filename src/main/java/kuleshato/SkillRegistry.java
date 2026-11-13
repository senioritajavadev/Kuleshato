package kuleshato;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SkillRegistry {
    record Skill(String name, String description, String source, Path directory, String instructions) {}
    record Catalog(List<Skill> skills, List<String> warnings) {}

    private static final int MAX_BYTES = 64 * 1024;
    private static final List<String> BUILTIN_NAMES = List.of(
        "verify", "simplify", "skillify", "remember", "java-notes"
    );
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$", Pattern.CASE_INSENSITIVE);

    private SkillRegistry() {}

    static Catalog discover(Path workspace) throws IOException {
        Map<String, Skill> byName = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Path home = Path.of(System.getProperty("user.home"));
        loadDirectory(workspace.resolve(".kuleshato/skills"), "проектный", workspace, byName, warnings);
        loadDirectory(home.resolve(".kuleshato/skills"), "пользовательский", null, byName, warnings);
        loadDirectory(home.resolve("skills"), "пользовательский", null, byName, warnings);
        for (String name : BUILTIN_NAMES) {
            String resource = "/skills/" + name + "/SKILL.md";
            try (InputStream stream = SkillRegistry.class.getResourceAsStream(resource)) {
                if (stream == null) {
                    warnings.add("Не найден встроенный навык " + name + ".");
                    continue;
                }
                Skill skill = parse(stream.readAllBytes(), "встроенный", null, resource);
                add(skill, byName, warnings);
            }
        }
        List<Skill> result = new ArrayList<>(byName.values());
        result.sort(Comparator.comparing(Skill::name));
        if (result.size() > 100) result = result.subList(0, 100);
        return new Catalog(List.copyOf(result), List.copyOf(warnings));
    }

    private static void loadDirectory(Path root, String source, Path boundary,
                                      Map<String, Skill> byName, List<String> warnings) throws IOException {
        if (!Files.isDirectory(root)) return;
        Path actualRoot = root.toRealPath();
        if (boundary != null && !actualRoot.startsWith(boundary.toRealPath())) {
            warnings.add("Каталог навыков " + source + " находится за пределами проекта.");
            return;
        }
        try (var entries = Files.list(actualRoot)) {
            for (Path dir : entries.sorted().toList()) {
                if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir)) continue;
                Path file = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) continue;
                try {
                    if (Files.size(file) > MAX_BYTES) {
                        warnings.add(source + " навык " + dir.getFileName() + " превышает лимит 64 КБ.");
                        continue;
                    }
                    Skill skill = parse(Files.readAllBytes(file), source, actualRoot, file.toString());
                    add(skill, byName, warnings);
                } catch (IllegalArgumentException error) {
                    warnings.add("Не удалось загрузить навык " + dir.getFileName() + ": " + error.getMessage());
                }
            }
        }
    }

    private static void add(Skill skill, Map<String, Skill> byName, List<String> warnings) {
        if (byName.containsKey(skill.name())) {
            warnings.add("Навык " + skill.name() + " из источника «" + skill.source()
                + "» пропущен: имя уже занято источником с более высоким приоритетом.");
        } else byName.put(skill.name(), skill);
    }

    private static Skill parse(byte[] bytes, String source, Path root, String path) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("файл превышает лимит 64 КБ");
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
        String[] lines = text.split("\\R", -1);
        if (lines.length < 4 || !lines[0].trim().equals("---")) {
            throw new IllegalArgumentException("отсутствует YAML frontmatter в " + path);
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) if (lines[i].trim().equals("---")) { end = i; break; }
        if (end < 0) throw new IllegalArgumentException("не закрыт YAML frontmatter в " + path);
        String name = "";
        String description = "";
        for (int i = 1; i < end; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 0) continue;
            String key = lines[i].substring(0, colon).trim();
            String value = lines[i].substring(colon + 1).trim().replaceAll("^(\"|')(.*)\\1$", "$2");
            if (key.equals("name")) name = value;
            if (key.equals("description")) description = value;
        }
        String instructions = String.join("\n", java.util.Arrays.copyOfRange(lines, end + 1, lines.length)).trim();
        if (!NAME.matcher(name).matches() || description.isBlank() || description.length() > 500 || instructions.isBlank()) {
            throw new IllegalArgumentException("некорректные поля SKILL.md в " + path);
        }
        return new Skill(name, description, source, root, instructions);
    }
}
