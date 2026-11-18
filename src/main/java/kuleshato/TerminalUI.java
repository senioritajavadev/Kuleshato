package kuleshato;

import java.util.List;
import java.util.Map;

final class TerminalUI {
    private static final String RESET = "\u001b[0m";
    private static final String[] CAT = {
        "⠀⠀ ⠀ ⠀⡼⠉⠑⠢⣠⣀⣀⣀",
        "⠀⠀⠀⠀⣸⠁⢳⡀⣠⠈⠁⠀⠈⠉⠉⠉⠙⠒⠲⠴⣶⠶⠖⠒⢻",
        "⠀⠀⠀⠀⡏⠀⣸⠟⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣠⣸⣶⠲⠂⡸",
        "⠀⠀⠀⠀⢧⡰⠃⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠘⣶⠃⠀⡰⠃",
        "⠀⠀⠀⠀⢸⠃⢀⣀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⣠⠞⠁",
        "⠀⠀⠀⣏⠛⠠⡟⠉⠧⠀⠀⠀⠀⠀⠀⣠⠞⠳⣦⠀⠀⠀⢀⣧⡀",
        "⠀⠀⠀⢱⠀⠀⠀⠀⠀⢠⣙⣟⣰⠀⠀⠁⠀⠀⠉⠀⠀⠀⠀⠠⣯⠀",
        "⠀⠀⠀⠀⠑⢤⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣠⠞⠁",
        "⠀⠀⠀⠀⠀⠀⠉⠒⠤⣄⣀⠀⠀⠀⠀⠀⣠⠖⢒⡦⠒⠋⠁",
        "⠀⠀⠀⠀⠀⠀⠀⢀⠔⢉⠀⠀⠀⠀⠀:⠠⢔⠊",
        "⠀⠀⠀⠀⠀⠀⠀⠘⠒⢻⡀⠀⠀⠀⠀⠀⠀⠈⠓⠒⢦⠀",
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢳⡀⠀⠀⢀⣠⠴⠚⠓⠛⠁",
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠳⣄⣹⠉"
    };

    private final boolean color;
    /** Подробный режим ({@code --verbose}, {@code /details}): технические детали для тех, кому они нужны. */
    private boolean verbose;

    TerminalUI(boolean color) {
        this.color = color && System.getenv("NO_COLOR") == null && !"dumb".equals(System.getenv("TERM"));
    }

    boolean verbose() { return verbose; }

    void verbose(boolean value) { verbose = value; }

    /** Техническая подробность: печатается только в подробном режиме. */
    void detail(String content) {
        if (verbose) System.err.println("    " + dim("· " + content));
    }

    /** Шаг работы простыми словами: «Читаю Main.java». */
    void step(String content) {
        System.err.println("  " + dim("· " + content));
    }

    void welcome(String model, String workspace, List<Map<String, Object>> skills, List<String> enabled) {
        line("  " + rgb(103, 239, 218, "✦") + " " + rgb(151, 111, 250, "✧") + " " + rgb(103, 239, 218, "✦"));
        int[][] gradient = {
            {73, 226, 211}, {68, 198, 237}, {78, 162, 255}, {113, 133, 255},
            {151, 111, 250}, {184, 99, 235}, {103, 239, 218}, {176, 105, 235},
            {145, 111, 247}, {113, 127, 255}, {88, 153, 255}, {72, 188, 240}, {73, 226, 211}
        };
        for (int i = 0; i < CAT.length; i++) line(rgb(gradient[i][0], gradient[i][1], gradient[i][2], "  " + CAT[i]));
        line("  " + rgb(151, 111, 250, "✧") + " " + rgb(103, 239, 218, "✦") + " " + rgb(151, 111, 250, "✧"));
        line("  " + bold(rgb(103, 239, 218, "KULESHATO")) + "  " + rgb(176, 105, 235, "Java · локальный помощник для разработки"));
        line("  " + dim("Модель: " + model));
        line("  " + dim(workspace));
        line("  " + dim("/help — что умеет Kuleshato · /exit — выход"));
        line("  " + dim("Навыки: " + (enabled.isEmpty() ? "не включены" : "включены " + String.join(", ", enabled))
            + " · доступно " + skills.size() + " — /skills"));
    }

    void showSkills(List<Map<String, Object>> skills, List<String> enabled) {
        if (skills.isEmpty()) {
            line("  Навыки не найдены.");
            return;
        }
        line("\nДоступные навыки:");
        for (Map<String, Object> skill : skills) {
            String name = String.valueOf(skill.get("name"));
            Object source = skill.get("source");
            line("  " + name + " — " + skill.get("description")
                + (source == null || SkillRegistry.BUILTIN.equals(source) ? "" : " [" + source + "]"));
            line("    Пример: " + skillExample(name));
            line("    " + (enabled.contains(name) ? "Уже включён" : "Включить: /skill enable " + name));
        }
    }

    private static String skillExample(String name) {
        return switch (name) {
            case "java-notes" -> "«Проверь этот класс по моим Java-предпочтениям и объясни улучшения»";
            case "verify" -> "«Проверь, как собрать и протестировать этот Java-проект»";
            case "simplify" -> "«Упрости этот метод, сохранив его поведение»";
            case "skillify" -> "«Создай навык для проверки Java-кода перед code review»";
            case "remember" -> "«Предложи, какие постоянные правила проекта стоит сохранить в памяти»";
            default -> "«Помоги выполнить описанный в навыке процесс: " + name + "»";
        };
    }

    void text(String content) { System.out.print(content); }
    void answer(String content) { System.out.print("\n  " + cyan("Kuleshato › ") + content + "\n\n"); }
    void error(String content) { System.err.println("\n  " + red("Ошибка") + " " + content); }
    void progress(String content) { line("  " + green("✓") + " " + content); }
    void thinking(String status) {
        if (!color) return;
        if (status.equals("thinking")) System.err.print(dim("Kuleshato думает…"));
        else if (status.equals("compacting")) System.err.print(dim("Kuleshato сжимает историю…"));
        else if (status.equals("planning")) System.err.print(dim("Kuleshato планирует задачу…"));
    }
    /**
     * Строка состояния во время генерации: этап, прошедшее время и число токенов. По ней видно, что модель жива,
     * и сколько она уже рассуждает; без цветного терминала не печатается, чтобы не засорять вывод.
     */
    void generation(ModelBackend.Phase phase, int thinkingTokens, int answerTokens, java.time.Duration elapsed) {
        if (!color) return;
        String time = String.format(java.util.Locale.ROOT, "%d:%02d", elapsed.toMinutes(), elapsed.toSecondsPart());
        String stage = switch (phase) {
            case PROMPT -> "читает вопрос";
            case THINKING -> "думает";
            case ANSWER -> "пишет ответ";
        };
        String details = "";
        if (verbose) {
            long seconds = Math.max(1, elapsed.toSeconds());
            int tokens = thinkingTokens + answerTokens;
            details = " · рассуждение " + thinkingTokens + " ток., ответ " + answerTokens + " ток."
                + (tokens > 0 ? String.format(java.util.Locale.ROOT, " · ~%.1f ток/с", (double) tokens / seconds) : "");
        }
        System.err.print("\r\u001b[2K" + dim("Kuleshato " + stage + "… " + time + details));
    }

    void warning(String content) { System.err.println("  " + yellow("!") + " " + content); }

    void stopThinking() {
        if (color) System.err.print("\r\u001b[2K");
        else System.err.println();
    }

    void showBrief(String content) {
        line("\n" + cyan("Обзор Java-проекта"));
        line(content + "\n");
    }

    void projectEvidence(ProjectInspection.Inspection inspection) {
        if (!verbose) {
            int count = inspection.filesRead().size();
            step(count == 0 ? "Подходящих файлов в проекте не нашлось" : "Посмотрел " + StyleScan.plural(count, "файл", "файла", "файлов")
                + " проекта" + (inspection.truncated() ? " (большие — частично)" : ""));
            return;
        }
        line("\nПроверка проекта приложением:");
        List<String> files = inspection.filesRead();
        if (files.isEmpty()) line("  Исходники и конфигурационные файлы для чтения не найдены.");
        else {
            line("  Прочитаны файлы (" + files.size() + "):");
            for (String file : files) line("    " + file);
        }
        line("  Поиск: " + (inspection.searchComplete() ? "завершён" : "неполный")
            + (inspection.searchTerms().isEmpty() ? "" : " · слова: " + String.join(", ", inspection.searchTerms())));
        for (String match : inspection.searchMatches().stream().limit(8).toList()) line("    совпадение: " + match);
        if (inspection.truncated()) line("  Контекст ограничен; часть файлов могла быть пропущена или сокращена.");
    }

    private static final java.util.regex.Pattern HUNK = java.util.regex.Pattern.compile("^@@ \\d+ -\\d+ \\+\\d+ @@$");

    void showDiff(String diff) {
        String[] lines = diff.split("\n");
        boolean headers = lines.length >= 2 && lines[0].startsWith("--- a/") && lines[1].startsWith("+++ b/");
        if (verbose || !headers) line("\n" + yellow("Изменения:"));
        else line("\n" + yellow("Изменения в " + lines[1].substring(6) + ":"));
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!verbose && (headers && i < 2 || HUNK.matcher(line).matches())) continue;
            if (line.startsWith("+")) System.out.println(green(line));
            else if (line.startsWith("-")) System.out.println(red(line));
            else System.out.println(dim(line));
        }
    }

    void showMemory(String content) {
        line(content == null ? "Память проекта не создана. Выполните /memory init." : content);
    }

    void showSettings(ProjectConfig config, List<String> overrides) {
        line("Настройки проекта:");
        line("  Модель: " + config.model());
        line("  Адаптер модели: " + config.backend());
        line("  Ollama: " + config.ollamaUrl());
        line("  Контекст: " + config.contextWindow());
        line("  Автосжатие: " + (config.autoCompact() ? "включено" : "выключено"));
        line("  Проверки без подтверждения: " + (config.autoRunChecks()
            ? "обнаруженные команды тестов и сборки" : "выключены, каждая команда подтверждается"));
        if (!overrides.isEmpty()) line("  Переопределения запуска: " + String.join(", ", overrides));
    }

    void showPlan(Map<String, Object> plan) {
        line("\nПлан: " + plan.get("title"));
        line("Цель: " + plan.get("goal"));
        Object rawSteps = plan.get("steps");
        if (rawSteps instanceof List<?> steps) {
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i) instanceof Map<?, ?> step) {
                    line(String.format("  [%s] %d. %s",
                        Boolean.TRUE.equals(step.get("completed")) ? "✓" : " ",
                        i + 1, step.get("description")));
                }
            }
        }
    }

    void showSessions(List<Map<String, Object>> sessions) {
        if (sessions.isEmpty()) { line("Сохранённых сессий нет."); return; }
        for (Map<String, Object> item : sessions) {
            line(item.get("id") + "  " + item.get("messages") + " сообщений · " + item.get("updatedAt"));
        }
    }

    String prompt() { return green("  ты ❯ "); }
    String commandApproval() { return "Разрешить выполнить команду? [y/N] "; }
    String fileApproval() { return "Применить это изменение? [y/N] "; }

    private void line(String text) { System.out.println(text); }
    private String rgb(int red, int green, int blue, String text) {
        return color ? "\u001b[38;2;" + red + ";" + green + ";" + blue + "m" + text + RESET : text;
    }
    private String cyan(String text) { return color ? "\u001b[36m" + text + RESET : text; }
    private String green(String text) { return color ? "\u001b[32m" + text + RESET : text; }
    private String red(String text) { return color ? "\u001b[31m" + text + RESET : text; }
    private String yellow(String text) { return color ? "\u001b[33m" + text + RESET : text; }
    private String dim(String text) { return color ? "\u001b[2m" + text + RESET : text; }
    private String bold(String text) { return color ? "\u001b[1m" + text + RESET : text; }
}
