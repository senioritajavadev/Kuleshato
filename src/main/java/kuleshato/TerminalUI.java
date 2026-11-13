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

    TerminalUI(boolean color) {
        this.color = color && System.getenv("NO_COLOR") == null && !"dumb".equals(System.getenv("TERM"));
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
        line("  " + dim("Java-режим: активен · /help — команды · /exit — выход"));
        showSkills(skills, enabled);
    }

    void showSkills(List<Map<String, Object>> skills, List<String> enabled) {
        if (skills.isEmpty()) {
            line("  Навыки не найдены.");
            return;
        }
        line("\nДоступные навыки:");
        for (Map<String, Object> skill : skills) {
            String name = String.valueOf(skill.get("name"));
            line("  " + name + " — " + skill.get("description"));
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
    void stopThinking() {
        if (color) System.err.print("\r\u001b[2K");
        else System.err.println();
    }

    void showBrief(String content) {
        line("\n" + cyan("Обзор Java-проекта"));
        line(content + "\n");
    }

    void projectEvidence(ProjectInspection.Inspection inspection) {
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

    void showDiff(String diff) {
        line("\n" + yellow("Изменения:"));
        for (String line : diff.split("\n")) {
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
