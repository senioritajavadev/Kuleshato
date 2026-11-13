package kuleshato;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Какие команды можно запускать без отдельного подтверждения.
 *
 * <p>Без подтверждения запускаются только команды проверки, обнаруженные в конфигурации самого проекта:
 * стандартные цели Maven/Gradle ({@code mvn test}, {@code ./gradlew build}…) и корневые скрипты с именами
 * {@code test.*}/{@code build.*}. Команда должна совпасть с обнаруженной дословно (без учёта пробелов),
 * а символы составления команд ({@code ; | & > < $ `} и перевод строки) делают её «незнакомой».
 * Команды из CI и README сами по себе в этот список не попадают: их содержимое не ограничено проверкой.
 */
final class CommandPolicy {
    enum Decision { AUTO_CHECK, ASK_USER }

    record Verdict(Decision decision, String reason) {}

    private static final Pattern SHELL_METACHARACTERS = Pattern.compile("[;|&<>$`\\r\\n(){}]");
    private static final Pattern STANDARD_GOAL = Pattern.compile(
        "(?:\\.[\\\\/])?(?:mvnw(?:\\.cmd)?|mvn|gradlew(?:\\.bat)?|gradle) (?:test|verify|check|build|package|compile|test-compile)");
    private static final Pattern ROOT_SCRIPT = Pattern.compile("(?:\\.[\\\\/])?(?:test|build)\\.(?:ps1|sh|bat|cmd)");

    static final CommandPolicy ASK_ALWAYS = new CommandPolicy(Map.of(), false);

    private final Map<String, String> checks;
    private final boolean autoRun;

    private CommandPolicy(Map<String, String> checks, boolean autoRun) {
        this.checks = Map.copyOf(checks);
        this.autoRun = autoRun;
    }

    /** Обнаруживает команды проверки по фактам сборки проекта. */
    static CommandPolicy detect(Path root, boolean autoRun) throws IOException {
        ProjectFacts.Result facts = JavaProjectBrief.scan(root, ".").facts();
        Map<String, String> checks = new LinkedHashMap<>();
        for (ProjectFacts.Category category : List.of(ProjectFacts.Category.TEST_COMMAND, ProjectFacts.Category.BUILD_COMMAND)) {
            for (ProjectFacts.Fact fact : facts.of(category)) {
                String command = fact.value().replace("`", "").strip();
                ProjectFacts.Source trusted = fact.sources().stream()
                    .filter(source -> source.origin() == ProjectFacts.Origin.CONFIG || source.origin() == ProjectFacts.Origin.FILESYSTEM)
                    .filter(source -> source.basis() != null && !source.basis().startsWith("шаг "))
                    .findFirst().orElse(null);
                if (trusted == null || !isCheckShape(command)) continue;
                checks.putIfAbsent(normalize(command), (trusted.line() > 0 ? trusted.path() + ":" + trusted.line() : trusted.path())
                    + ", " + trusted.basis());
            }
        }
        return new CommandPolicy(checks, autoRun);
    }

    Verdict judge(String command) {
        String normalized = normalize(command);
        if (!autoRun) return new Verdict(Decision.ASK_USER, "автозапуск проверок выключен настройкой autoRunChecks");
        if (SHELL_METACHARACTERS.matcher(command).find()) {
            return new Verdict(Decision.ASK_USER, "команда содержит символы составления shell-команд");
        }
        String source = checks.get(normalized);
        if (source == null) return new Verdict(Decision.ASK_USER, "команда не входит в обнаруженные команды проверки");
        return new Verdict(Decision.AUTO_CHECK, "обнаруженная команда проверки (" + source + ")");
    }

    /**
     * Приводит искажённую запись обнаруженной проверки к ней самой, иначе возвращает команду без изменений.
     * Небольшие модели пишут {@code ./test.cmd} вместо {@code .\test.cmd}, опускают {@code .\} или теряют обратную
     * косую черту в JSON: {@code "\t"} превращается в табуляцию ({@code ".<TAB>est.cmd"}), {@code "\b"} — в backspace.
     * Подставляется только команда из списка обнаруженных, поэтому на запуск чего-то нового это не влияет.
     */
    String canonical(String command) {
        if (checks.containsKey(normalize(command))) return command;
        String key = variantKey(command.replace("\t", "\\t").replace("\b", "\\b").replace("\f", "\\f"));
        for (String check : checks.keySet()) {
            if (variantKey(check).equals(key)) return check;
        }
        return command;
    }

    private static String variantKey(String command) {
        String normalized = normalize(command);
        return normalized.startsWith("./") || normalized.startsWith(".\\") ? normalized.substring(2) : normalized;
    }

    boolean isCheck(String command) {
        return checks.containsKey(normalize(command));
    }

    List<String> checks() {
        return new ArrayList<>(checks.keySet());
    }

    boolean autoRun() {
        return autoRun;
    }

    private static boolean isCheckShape(String command) {
        String normalized = normalize(command);
        return !SHELL_METACHARACTERS.matcher(command).find()
            && (STANDARD_GOAL.matcher(normalized).matches() || ROOT_SCRIPT.matcher(normalized).matches());
    }

    private static String normalize(String command) {
        return command.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
