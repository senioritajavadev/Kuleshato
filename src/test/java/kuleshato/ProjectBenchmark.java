package kuleshato;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Эталонный набор вопросов о Java-проектах.
 *
 * <p>Детерминированный режим (по умолчанию, запускается в CI через {@link TestSuite}) проверяет извлечённые факты
 * и их происхождение, доставку нужных строк в контекст модели, работу валидатора на заранее заданных тезисах
 * («сценарная модель») и маршрутизацию быстрых ответов. Живой режим ({@code --live}) задаёт те же вопросы
 * локальной модели Ollama и печатает таблицу результатов; он не входит в CI.
 */
public final class ProjectBenchmark {
    private static final String RESOURCE_ROOT = "benchmark/";
    private static final int INSPECTION_CHARS = 6_000;

    /** Доля успешных проверок метрики и описания неудач. */
    static final class Metric {
        final String name;
        final String description;
        int passed;
        int total;
        final List<String> failures = new ArrayList<>();

        Metric(String name, String description) {
            this.name = name;
            this.description = description;
        }

        void record(boolean ok, String failure) {
            total++;
            if (ok) passed++;
            else failures.add(failure);
        }

        double value() {
            return total == 0 ? 1.0 : (double) passed / total;
        }
    }

    record Report(Map<String, Metric> metrics, Map<String, Double> thresholds, List<Map<String, Object>> liveResults) {
        List<String> thresholdFailures() {
            List<String> result = new ArrayList<>();
            for (Map.Entry<String, Double> threshold : thresholds.entrySet()) {
                Metric metric = metrics.get(threshold.getKey());
                if (metric == null) {
                    result.add("Неизвестная метрика в порогах: " + threshold.getKey());
                } else if (metric.value() + 1e-9 < threshold.getValue()) {
                    result.add(String.format(Locale.ROOT, "%s = %.3f < %.3f", metric.name, metric.value(), threshold.getValue()));
                }
            }
            return result;
        }

        String render() {
            StringBuilder result = new StringBuilder("Эталонный набор Kuleshato\n");
            for (Metric metric : metrics.values()) {
                result.append(String.format(Locale.ROOT, "  %-22s %6.1f%%  (%d/%d)  %s%n",
                    metric.name, metric.value() * 100, metric.passed, metric.total, metric.description));
            }
            for (Metric metric : metrics.values()) {
                for (String failure : metric.failures) result.append("  ✗ ").append(metric.name).append(": ").append(failure).append('\n');
            }
            if (!liveResults.isEmpty()) {
                result.append("\nЖивой прогон (модель Ollama):\n");
                liveResults.forEach(row -> result.append("  ").append(formatLiveRow(row)).append('\n'));
            }
            return result.toString();
        }
    }

    private ProjectBenchmark() {}

    public static void main(String[] args) throws Exception {
        String model = null;
        String url = ProjectConfig.DEFAULT.ollamaUrl();
        boolean live = false;
        Path recordFile = null;
        String hardware = System.getenv().getOrDefault("KULESHATO_HARDWARE", "не указано");
        int contextWindow = ProjectConfig.DEFAULT.contextWindow();
        String liveOnly = "";
        String reading = "keyhole";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--live" -> live = true;
                case "--model" -> model = args[++i];
                case "--ollama-url" -> url = args[++i];
                case "--record" -> recordFile = Path.of(args[++i]);
                case "--hardware" -> hardware = args[++i];
                case "--context-window" -> contextWindow = Integer.parseInt(args[++i]);
                case "--live-only" -> liveOnly = args[++i];
                case "--reading" -> reading = args[++i];
                case "--compare" -> {
                    System.out.print(compare(Path.of(args[++i])));
                    return;
                }
                default -> throw new IllegalArgumentException("Неизвестный аргумент: " + args[i]
                    + ". Допустимо: --live [--model <имя>] [--ollama-url <loopback URL>] [--record <файл.jsonl>]"
                    + " [--hardware <описание>] | --compare <файл.jsonl>");
            }
        }
        if (model == null) model = System.getenv().getOrDefault("KULESHATO_MODEL", ProjectConfig.DEFAULT.model());
        if (live) ProjectConfig.validateOllamaUrl(url);
        if (recordFile != null && !live) throw new IllegalArgumentException("--record имеет смысл только вместе с --live");
        LiveSettings settings = live ? new LiveSettings(model, url, contextWindow, liveOnly, reading) : null;
        Report report = run(settings);
        System.out.print(report.render());
        if (recordFile != null) {
            record(recordFile, settings, report, hardware);
            System.out.println("\nРезультат живого прогона дописан в " + recordFile);
        }
        List<String> failures = report.thresholdFailures();
        if (!failures.isEmpty()) {
            System.out.println("\nПороги не выполнены:");
            failures.forEach(failure -> System.out.println("  - " + failure));
            System.exit(1);
        }
        System.out.println("\nВсе пороги выполнены.");
    }

    record LiveSettings(String model, String ollamaUrl, int contextWindow, String only, String reading) {
        /** legacy — прежнее поведение: read_file целиком и прежняя формулировка разведки; для парного замера. */
        boolean legacyReading() {
            return "legacy".equals(reading);
        }

        /** Раздел живого прогона: questions, tasks, reviews или пусто — все. */
        boolean includes(String section) {
            return only == null || only.isBlank() || only.equals(section);
        }
    }

    static Report run(LiveSettings live) throws Exception {
        Map<?, ?> cases = (Map<?, ?>) Json.parse(resource("cases.json"));
        Map<String, Metric> metrics = new LinkedHashMap<>();
        for (String[] definition : new String[][] {
            {"factRecall", "ожидаемые факты найдены"},
            {"factPrecision", "нет лишних фактов в полностью размеченных категориях"},
            {"citationAccuracy", "найденные факты указывают верные файл, строку и вид источника"},
            {"leakFree", "секреты из настроек не попадают в обзор и ответы"},
            {"evidenceDelivery", "нужные строки кода доходят до контекста модели"},
            {"groundedAcceptance", "подтверждённые тезисы принимаются"},
            {"unsupportedRejection", "неподтверждённые, отрицательные и выдуманные тезисы отклоняются"},
            {"sourceKindAccuracy", "принятый тезис отнесён к коду или документации верно"},
            {"answerChecks", "маршрут и обязательное/запрещённое содержание ответов"},
            {"taskOutcome", "задача на изменение кода решена (или не решена) как ожидалось — по независимой проверке"},
            {"honestReport", "итог выполнения совпадает с реальными изменениями и результатом проверки"},
            {"sideEffectFree", "изменены только разрешённые файлы, отклонённое не применено"},
            {"lessonInjection", "урок из памяти целиком попадает в системную подсказку задачи"},
            {"reviewTargeting", "ревью получает нужные строки (файл, метод, diff) и не получает лишних"},
            {"reviewValidation", "замечания ревью приняты, понижены или отклонены как ожидалось"},
            {"reviewFormat", "замечание содержит приоритет, файл:строки, риск, основание и воспроизведение"},
        }) {
            metrics.put(definition[0], new Metric(definition[0], definition[1]));
        }
        Map<String, Double> thresholds = new LinkedHashMap<>();
        ((Map<?, ?>) cases.get("thresholds")).forEach((key, value) ->
            thresholds.put((String) key, ((Number) value).doubleValue()));
        List<Map<String, Object>> liveRows = new ArrayList<>();
        for (Object rawProject : (List<?>) cases.get("projects")) {
            Map<?, ?> project = (Map<?, ?>) rawProject;
            Path root = materialize(resource("fixtures/" + project.get("fixture")));
            try {
                evaluateProject(project, root.toRealPath(), metrics, live, liveRows);
            } finally {
                deleteTree(root);
            }
        }
        for (Map<?, ?> task : maps(cases.get("tasks"))) {
            boolean hasLesson = task.get("lesson") instanceof String;
            evaluateTask(task, metrics, null, null, false);
            if (hasLesson) evaluateTask(task, metrics, null, null, true);
            if (live != null && live.includes("tasks") && Boolean.TRUE.equals(task.get("approveChanges"))) {
                evaluateTask(task, null, live, liveRows, false);
                // Тот же запрос с уроком в памяти: сравнение показывает, помогает ли урок модели или только тратит контекст.
                if (hasLesson) evaluateTask(task, null, live, liveRows, true);
            }
        }
        for (Map<?, ?> review : maps(cases.get("reviews"))) evaluateReview(review, metrics, live, liveRows);
        return new Report(metrics, thresholds, liveRows);
    }

    // ---------------------------------------------------------------- Ревью кода

    private static void evaluateReview(
        Map<?, ?> review, Map<String, Metric> metrics, LiveSettings live, List<Map<String, Object>> liveRows
    ) throws Exception {
        String name = (String) review.get("name");
        String fixture = resource("fixtures/" + review.get("fixture"));
        Path root = materialize(fixture).toRealPath();
        try {
            Map<String, String> after = taggedFiles(fixture, "after");
            if (!after.isEmpty()) {
                gitCommitAll(root);
                for (Map.Entry<String, String> file : after.entrySet()) Files.writeString(root.resolve(file.getKey()), file.getValue());
            }
            CodeReview.Target target = Main.reviewTarget(root, (String) review.get("target"));
            Map<String, CodeReview.Line> lines = CodeReview.lines(target);
            Metric targeting = metrics.get("reviewTargeting");
            for (String included : strings(review.get("targetIncludes"))) {
                targeting.record(lines.values().stream().anyMatch(line -> line.text().contains(included)),
                    name + ": в ревью нет строки «" + included + "»");
            }
            for (String excluded : strings(review.get("targetExcludes"))) {
                targeting.record(lines.values().stream().noneMatch(line -> line.text().contains(excluded)),
                    name + ": в ревью попала лишняя строка «" + excluded + "»");
            }
            if (target.diff()) {
                targeting.record(CodeReview.context(target).contains("Удалённые строки"),
                    name + ": удалённые строки diff не переданы модели");
            }

            Metric validation = metrics.get("reviewValidation");
            Metric format = metrics.get("reviewFormat");
            for (Map<?, ?> scripted : maps(review.get("scripted"))) {
                Map<String, Object> finding = new LinkedHashMap<>();
                ((Map<?, ?>) scripted.get("finding")).forEach((key, value) -> finding.put((String) key, value));
                List<Object> ids = new ArrayList<>();
                for (Map<?, ?> evidence : maps(finding.get("evidence"))) {
                    if (evidence.containsKey("id")) {
                        ids.add(evidence.get("id"));
                        continue;
                    }
                    int occurrence = evidence.containsKey("occurrence") ? ((Number) evidence.get("occurrence")).intValue() : 1;
                    String id = lines.values().stream().filter(line -> line.text().contains((String) evidence.get("contains")))
                        .skip(occurrence - 1).map(CodeReview.Line::id).findFirst().orElse(null);
                    targeting.record(id != null, name + ": строка «" + evidence.get("contains") + "» не передана в ревью");
                    ids.add(id == null ? "R99999" : id);
                }
                finding.put("evidence", ids);
                CodeReview.Result result = CodeReview.validate(Json.stringify(Map.of("findings", List.of(finding))), target);
                String title = (String) finding.get("title");
                String expect = (String) scripted.get("expect");
                if (expect.equals("rejected")) {
                    validation.record(result.accepted().isEmpty() && result.rejected().size() == 1,
                        name + ": принято замечание «" + title + "»");
                    continue;
                }
                CodeReview.Finding accepted = result.accepted().isEmpty() ? null : result.accepted().get(0);
                validation.record(accepted != null, name + ": отклонено замечание «" + title + "»: " + result.rejected());
                if (accepted == null) continue;
                validation.record(accepted.priority().equals(scripted.get("expectPriority"))
                        && accepted.category().name().equals(scripted.get("expectCategory")),
                    name + ": «" + title + "» получило " + accepted.priority() + "/" + accepted.category()
                        + ", ожидалось " + scripted.get("expectPriority") + "/" + scripted.get("expectCategory"));
                String rendered = CodeReview.render(result);
                String reference = CodeReview.references(accepted.evidence());
                for (String part : List.of("[" + accepted.priority() + "]", reference, "Риск: ", "Основание: ", "Код: `",
                    "Как воспроизвести: ", "отсутствие замечаний не доказывает отсутствие ошибок")) {
                    format.record(rendered.contains(part), name + ": в выводе замечания нет «" + part + "»");
                }
            }
            if (live != null && live.includes("reviews")) liveRows.add(liveReview(name, review, root, target, live));
        } finally {
            deleteTree(root);
        }
    }

    private static Map<String, Object> liveReview(
        String name, Map<?, ?> review, Path root, CodeReview.Target target, LiveSettings live
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("review", name);
        try {
            WorkspaceTools tools = new WorkspaceTools(root, command -> false, (path, diff) -> false);
            ModelClient client = new ModelClient(live.ollamaUrl(), live.model(), live.contextWindow(), tools);
            long started = System.nanoTime();
            CodeReview.Result result = CodeReview.validate(client.reviewCode(CodeReview.context(target), "Review " + target.label()), target);
            row.put("seconds", Math.round((System.nanoTime() - started) / 1e8) / 10.0);
            List<String> known = strings(review.get("known"));
            List<CodeReview.Finding> defects = result.accepted().stream().filter(finding -> finding.category().isDefect()).toList();
            long found = known.stream().filter(text -> defects.stream().anyMatch(finding ->
                finding.evidence().stream().anyMatch(line -> line.text().contains(text)))).count();
            long unmatched = defects.stream().filter(finding -> known.stream().noneMatch(text ->
                finding.evidence().stream().anyMatch(line -> line.text().contains(text)))).count();
            row.put("knownFound", found);
            row.put("knownTotal", known.size());
            row.put("otherDefects", unmatched);
            row.put("maintenance", result.accepted().size() - defects.size());
            row.put("rejected", result.rejected().size());
            putUsage(row, client.backend().usage());
        } catch (Exception error) {
            row.put("error", String.valueOf(error.getMessage()));
        }
        return row;
    }

    /** Файлы с меткой {@code [tag]} — например, состояние после изменения для ревью diff. */
    static Map<String, String> taggedFiles(String fixture, String tag) {
        Map<String, String> result = new LinkedHashMap<>();
        String current = null;
        StringBuilder content = new StringBuilder();
        for (String line : (fixture + "\n=== \u0000 ===").split("\\R", -1)) {
            if (line.startsWith("=== ") && line.endsWith(" ===") && line.length() > 8) {
                if (current != null) result.put(current, content.toString());
                String header = line.substring(4, line.length() - 4).strip();
                current = header.endsWith("[" + tag + "]") ? header.substring(0, header.length() - tag.length() - 2).strip() : null;
                content.setLength(0);
            } else if (current != null) {
                content.append(line).append('\n');
            }
        }
        return result;
    }

    private static void gitCommitAll(Path root) throws Exception {
        for (List<String> command : List.of(
            List.of("git", "init", "-q"),
            List.of("git", "add", "-A"),
            List.of("git", "-c", "user.name=bench", "-c", "user.email=bench@example.invalid", "-c", "commit.gpgsign=false",
                "commit", "-q", "-m", "base"))) {
            Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("git " + command.get(1) + " не выполнен: " + output.strip());
            }
        }
    }

    // ---------------------------------------------------------------- Задачи на изменение кода

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final String CHECK_COMMAND = WINDOWS ? ".\\test.cmd" : "./test.sh";

    /**
     * Прогоняет задачу через настоящий цикл агента ({@link ModelClient#chat}) и инструменты. В детерминированном режиме
     * ответы модели берутся из эталонного решения, в живом — от Ollama. Результат проверяется независимым запуском
     * проверки и сравнением файлов, а не по словам модели.
     */
    private static void evaluateTask(
        Map<?, ?> task, Map<String, Metric> metrics, LiveSettings live, List<Map<String, Object>> liveRows, boolean withLesson
    ) throws Exception {
        String lesson = withLesson ? ((String) task.get("lesson")).replace("{check}", CHECK_COMMAND) : null;
        String name = (String) task.get("name") + (withLesson ? " +урок" : "");
        boolean approveChanges = Boolean.TRUE.equals(task.get("approveChanges"));
        List<String> allowed = strings(task.get("allowedChanges"));
        Path root = materialize(resource("fixtures/" + task.get("fixture"))).toRealPath();
        try {
            CommandPolicy policy = CommandPolicy.detect(root, true);
            Map<String, String> before = snapshot(root);
            int[] declinedCommands = {0};
            WorkspaceTools tools = new WorkspaceTools(root, command -> {
                declinedCommands[0]++;
                return false;
            }, (path, diff) -> approveChanges, policy);
            tools.startTurn();
            boolean legacy = live != null && live.legacyReading();
            if (legacy) tools.withReadWindow(0);
            int contextWindow = live == null ? 8192 : live.contextWindow();
            String system = Main.BASE_PROMPT + "\n\n" + Main.changeWorkflow(policy) + "\n\n" + Lessons.PROMPT;
            if (lesson != null) {
                system += "\n\n" + Main.memorySection(
                    ProjectMemory.withLesson("# Память проекта\n", lesson, java.time.LocalDate.of(2026, 1, 1)), contextWindow);
            }
            List<Object> messages = new ArrayList<>(List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", task.get("prompt"))));
            int[] turns = {0};
            int[] toolErrors = {0};
            ModelClient.Events events = new ModelClient.Events() {
                @Override public void status(String status) {
                    if (status.equals("thinking")) turns[0]++;
                }

                @Override public void toolResult(String toolName, String result) {
                    if (result.startsWith("Tool error:")) toolErrors[0]++;
                }
            };
            long started = System.nanoTime();
            String answer;
            String error = null;
            ModelBackend.Usage usage = ModelBackend.Usage.EMPTY;
            if (live == null) {
                try (ScriptedOllama scripted = new ScriptedOllama(referenceReplies(task))) {
                    answer = new ModelClient(scripted.url(), "bench-model", contextWindow, tools).chat(messages, false, events,
                        reply -> ChangeNudge.message((String) task.get("prompt"), tools.report(), policy));
                    if (lesson != null) {
                        String escaped = Json.stringify(lesson);
                        String first = scripted.requests.get(0);
                        metrics.get("lessonInjection").record(first.contains(escaped.substring(1, escaped.length() - 1))
                                && !first.contains("Память сокращена"),
                            name + ": урок не дошёл до системной подсказки целиком");
                    }
                }
            } else {
                ModelClient client = new ModelClient(live.ollamaUrl(), live.model(), live.contextWindow(), tools);
                try {
                    answer = client.chat(messages, false, events,
                        reply -> ChangeNudge.message((String) task.get("prompt"), tools.report(), policy));
                } catch (Exception failure) {
                    answer = "";
                    error = failure.getMessage();
                }
                usage = client.backend().usage();
            }
            double seconds = (System.nanoTime() - started) / 1e9;
            Map<String, String> after = snapshot(root);
            List<String> changed = new ArrayList<>();
            for (String path : after.keySet()) if (!after.get(path).equals(before.get(path))) changed.add(path);
            for (String path : before.keySet()) if (!after.containsKey(path)) changed.add(path);
            boolean passed = !new WorkspaceTools(root, command -> false, (path, diff) -> false, policy)
                .execute("run_command", Map.of("command", CHECK_COMMAND)).startsWith("Command failed");
            WorkspaceTools.TurnReport report = tools.report();
            String rendered = report.render();
            List<String> outside = changed.stream().filter(path -> !allowed.contains(path)).toList();

            if (metrics != null) {
                boolean expectSolved = Boolean.TRUE.equals(task.get("expectSolved"));
                metrics.get("taskOutcome").record(passed == expectSolved,
                    name + ": проверка " + (passed ? "проходит" : "падает") + ", ожидалось " + (expectSolved ? "проходит" : "падает"));
                metrics.get("honestReport").record(new java.util.TreeSet<>(report.changedFiles).equals(new java.util.TreeSet<>(changed)),
                    name + ": в итоге изменены " + report.changedFiles + ", на диске " + changed);
                if (report.checkRun) {
                    metrics.get("honestReport").record(report.lastCheckPassed == passed,
                        name + ": итог сообщает о последней проверке " + report.lastCheckPassed + ", независимая проверка " + passed);
                }
                metrics.get("honestReport").record(changed.isEmpty() == rendered.contains("Изменённые файлы: нет"),
                    name + ": текст итога не соответствует изменениям: " + rendered);
                if (!changed.isEmpty() && !passed) {
                    metrics.get("honestReport").record(rendered.contains("ошибкой") || rendered.contains("не запускались"),
                        name + ": итог не предупреждает о непройденной проверке: " + rendered);
                }
                if (!approveChanges) {
                    metrics.get("honestReport").record(!report.declinedFiles.isEmpty() && rendered.contains("отклонены пользователем"),
                        name + ": итог не сообщает об отклонённом изменении");
                    metrics.get("sideEffectFree").record(changed.isEmpty(), name + ": отклонённое изменение применено: " + changed);
                }
                metrics.get("sideEffectFree").record(outside.isEmpty(), name + ": изменены файлы вне разрешённых: " + outside);
            }
            if (liveRows != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("task", name);
                row.put("solved", passed);
                row.put("seconds", Math.round(seconds * 10) / 10.0);
                row.put("modelTurns", turns[0]);
                row.put("toolErrors", toolErrors[0]);
                putUsage(row, usage);
                row.put("failedChecks", report.failedChecks);
                row.put("changedFiles", changed);
                row.put("outsideAllowed", outside);
                row.put("declinedCommands", declinedCommands[0]);
                row.put("claimsSuccess", answer.toLowerCase(Locale.ROOT).matches("(?s).*(проходит|исправ|готово|passes|fixed).*"));
                row.put("trace", trace(messages));
                if (error != null) row.put("error", error);
                liveRows.add(row);
            }
        } finally {
            deleteTree(root);
        }
    }

    /**
     * Краткая трасса хода агента: по строке на вызов инструмента (с главными аргументами и исходом) и на текстовый
     * ответ модели. Нужна, чтобы по записи живого прогона понять, почему задача не решена.
     */
    static List<String> trace(List<Object> messages) {
        List<String> lines = new ArrayList<>();
        for (Object raw : messages) {
            if (!(raw instanceof Map<?, ?> message)) continue;
            Object role = message.get("role");
            if ("assistant".equals(role)) {
                if (message.get("tool_calls") instanceof List<?> calls && !calls.isEmpty()) {
                    for (Object call : calls) {
                        if (call instanceof Map<?, ?> callMap && callMap.get("function") instanceof Map<?, ?> function) {
                            lines.add("→ " + function.get("name") + "(" + traceArguments(function.get("arguments")) + ")");
                        }
                    }
                } else if (message.get("content") instanceof String text && !text.isBlank()) {
                    lines.add("ответ: " + shorten(text, 200));
                }
            } else if ("user".equals(role) && message.get("content") instanceof String text && text.startsWith(ChangeNudge.PREFIX)) {
                lines.add("← напоминание приложения: изменений нет");
            } else if ("tool".equals(role) && message.get("content") instanceof String result) {
                String outcome = result.startsWith("Tool error:") || result.startsWith("Command failed")
                    || result.contains("was not approved") || result.startsWith("Check was not run") ? "  ✗ " + shorten(result, 260) : "  ✓ " + shorten(result.lines().findFirst().orElse(""), 80);
                lines.add(outcome);
            }
        }
        return lines;
    }

    private static String traceArguments(Object raw) {
        Map<?, ?> arguments;
        if (raw instanceof Map<?, ?> map) arguments = map;
        else if (raw instanceof String json) {
            try {
                arguments = Json.parse(json) instanceof Map<?, ?> map ? map : Map.of();
            } catch (IllegalArgumentException error) {
                return "невалидный JSON";
            }
        } else return "";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<?, ?> entry : arguments.entrySet()) {
            String value = String.valueOf(entry.getValue());
            String key = String.valueOf(entry.getKey());
            // old_text показывается началом: по нему видно, чем модель промахнулась мимо файла.
            parts.add(key + "=" + (key.equals("old_text") ? "«" + shorten(value, 80) + "» (" + value.length() + " симв.)"
                : key.equals("new_text") || key.equals("content") ? value.length() + " симв." : shorten(value, 60)));
        }
        return String.join(", ", parts);
    }

    private static String shorten(String text, int limit) {
        String single = text.strip().replaceAll("\\s+", " ");
        return single.length() <= limit ? single : single.substring(0, limit) + "…";
    }

    private static List<Map<String, Object>> referenceReplies(Map<?, ?> task) {
        List<Map<String, Object>> replies = new ArrayList<>();
        for (Map<?, ?> step : maps(task.get("reference"))) {
            if (step.containsKey("answer")) {
                replies.add(ScriptedOllama.answer((String) step.get("answer")));
                continue;
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            ((Map<?, ?>) step.get("arguments")).forEach((key, value) ->
                arguments.put((String) key, value instanceof String text ? text.replace("{check}", CHECK_COMMAND) : value));
            replies.add(ScriptedOllama.toolCall((String) step.get("tool"), arguments));
        }
        return replies;
    }

    /** Содержимое файлов проекта, кроме результатов сборки в {@code out/}. */
    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> result = new java.util.TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith("out/")) continue;
                result.put(relative, Files.readString(path, StandardCharsets.ISO_8859_1));
            }
        }
        return result;
    }

    private static void evaluateProject(
        Map<?, ?> project, Path root, Map<String, Metric> metrics, LiveSettings live, List<Map<String, Object>> liveRows
    ) throws Exception {
        String name = (String) project.get("name");
        JavaProject.requireWorkspace(root);
        ProjectFacts.Result facts = JavaProjectBrief.scan(root, ".").facts();
        List<String> exhaustive = strings(project.get("exhaustive"));
        List<Map<?, ?>> expectedFacts = maps(project.get("facts"));
        for (Map<?, ?> expected : expectedFacts) {
            ProjectFacts.Category category = ProjectFacts.Category.valueOf((String) expected.get("category"));
            String value = placeholders((String) expected.get("value"));
            ProjectFacts.Fact actual = facts.of(category).stream().filter(fact -> fact.value().equals(value)).findFirst().orElse(null);
            metrics.get("factRecall").record(actual != null, name + ": нет факта " + category + " " + value);
            if (actual == null) continue;
            for (Map<?, ?> source : maps(expected.get("sources"))) {
                ProjectFacts.Origin origin = ProjectFacts.Origin.valueOf((String) source.get("origin"));
                String path = placeholders((String) source.get("path"));
                int line = ((Number) source.get("line")).intValue();
                boolean found = actual.sources().stream().anyMatch(candidate ->
                    candidate.origin() == origin && candidate.path().equals(path) && candidate.line() == line);
                metrics.get("citationAccuracy").record(found, name + ": " + category + " " + value + " — ожидалось "
                    + path + ":" + line + " " + origin + ", получено " + actual.sources());
            }
        }
        for (ProjectFacts.Fact fact : facts.facts()) {
            if (!exhaustive.contains(fact.category().name())) continue;
            boolean expected = expectedFacts.stream().anyMatch(candidate ->
                candidate.get("category").equals(fact.category().name())
                    && placeholders((String) candidate.get("value")).equals(fact.value()));
            metrics.get("factPrecision").record(expected, name + ": лишний факт " + fact.category() + " " + fact.render());
        }
        List<String> forbidden = strings(project.get("forbidden"));
        checkLeaks(metrics, name + " /brief", JavaProjectBrief.create(root, "."), forbidden);

        for (Map<?, ?> question : maps(project.get("questions"))) {
            evaluateQuestion(name, question, root, metrics, forbidden);
            if (live != null && live.includes("questions") && "MODEL".equals(question.get("route"))) liveRows.add(liveQuestion(name, question, root, live));
        }
    }

    private static void evaluateQuestion(
        String project, Map<?, ?> question, Path root, Map<String, Metric> metrics, List<String> forbidden
    ) throws Exception {
        String prompt = (String) question.get("prompt");
        String label = project + " «" + prompt + "»";
        Metric checks = metrics.get("answerChecks");
        List<Object> history = new ArrayList<>(maps(question.get("history")));
        boolean projectMode = ProjectInspection.isProjectQuestion(root, prompt, history);
        boolean expectProjectMode = !Boolean.FALSE.equals(question.get("expectProjectMode"));
        checks.record(projectMode == expectProjectMode, label + (expectProjectMode
            ? ": запрос не распознан как вопрос о проекте" : ": обычный вопрос ошибочно отправлен в режим инспекции"));
        if (!expectProjectMode) return;
        ProjectInspection.Inspection inspection = ProjectInspection.collect(root, prompt, INSPECTION_CHARS);
        checkLeaks(metrics, label + " контекст обзора", inspection.structuralSummary(), forbidden);

        List<Map<?, ?>> claims = maps(question.get("claims"));
        List<Object> firstAttempt = new ArrayList<>();
        List<Object> retryAttempt = new ArrayList<>();
        for (Map<?, ?> claim : claims) {
            String statement = (String) claim.get("statement");
            String expect = (String) claim.get("expect");
            List<Object> ids = new ArrayList<>();
            for (Map<?, ?> evidence : maps(claim.get("evidence"))) {
                if (evidence.containsKey("id")) {
                    ids.add(evidence.get("id"));
                    continue;
                }
                String id = evidenceId(inspection, (String) evidence.get("path"), (String) evidence.get("contains"));
                if (!expect.equals("rejected")) {
                    metrics.get("evidenceDelivery").record(id != null, label + ": строка «" + evidence.get("contains")
                        + "» из " + evidence.get("path") + " не попала в контекст модели");
                }
                ids.add(id == null ? "E99999" : id);
            }
            Map<String, Object> rendered = Map.of("statement", statement, "evidence", ids);
            firstAttempt.add(rendered);
            if (!expect.equals("rejected")) retryAttempt.add(rendered);
            String verdict = classify(ProjectInspection.renderVerifiedAnswer(
                Json.stringify(Map.of("claims", List.of(rendered))), inspection), statement);
            if (expect.equals("rejected")) {
                metrics.get("unsupportedRejection").record(verdict.equals("rejected"), label + ": принят тезис «" + statement + "»");
            } else {
                metrics.get("groundedAcceptance").record(!verdict.equals("rejected"), label + ": отклонён тезис «" + statement + "»");
                if (!verdict.equals("rejected")) {
                    metrics.get("sourceKindAccuracy").record(verdict.equals(expect),
                        label + ": тезис «" + statement + "» отнесён к " + verdict + ", ожидалось " + expect);
                }
            }
        }

        int[] calls = {0};
        ProjectAnswering.Answer answer = ProjectAnswering.answer(prompt, inspection, "bench-model", "http://127.0.0.1:11434",
            (context, asked, correction) -> {
                calls[0]++;
                return Json.stringify(Map.of("claims", correction.isEmpty() ? firstAttempt : retryAttempt));
            }, root);
        String expectedRoute = (String) question.get("route");
        checks.record(answer.route().name().equals(expectedRoute),
            label + ": маршрут " + answer.route() + ", ожидался " + expectedRoute);
        checks.record(!answer.deterministicFallback(), label + ": ответ заменён детерминированным резервным выводом");
        for (String required : strings(question.get("mustContain"))) {
            checks.record(answer.text().contains(required), label + ": в ответе нет «" + required + "»");
        }
        for (String prohibited : strings(question.get("mustNotContain"))) {
            checks.record(!answer.text().contains(prohibited), label + ": в ответе есть запрещённое «" + prohibited + "»");
        }
        checkLeaks(metrics, label + " ответ", answer.text(), forbidden);
    }

    private static Map<String, Object> liveQuestion(String project, Map<?, ?> question, Path root, LiveSettings live) {
        String prompt = (String) question.get("prompt");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("project", project);
        row.put("prompt", prompt);
        try {
            ProjectInspection.Inspection inspection = ProjectInspection.collect(root, prompt, INSPECTION_CHARS);
            WorkspaceTools tools = new WorkspaceTools(root, command -> false, (path, diff) -> false);
            ModelClient client = new ModelClient(live.ollamaUrl(), live.model(), live.contextWindow(), tools);
            long started = System.nanoTime();
            ProjectAnswering.Answer answer = ProjectAnswering.answer(prompt, inspection, live.model(), live.ollamaUrl(),
                client::answerProjectQuestion, root);
            double seconds = (System.nanoTime() - started) / 1e9;
            String claimsPart = answer.text().split("\n\nФакты автоматического обзора", 2)[0];
            String rejectedPart = claimsPart.contains("Отклонённые тезисы") ? claimsPart.substring(claimsPart.indexOf("Отклонённые тезисы")) : "";
            String acceptedPart = claimsPart.substring(0, claimsPart.length() - rejectedPart.length());
            // Резервный ответ содержит только результаты сканирования: строки «- …» там не тезисы модели.
            boolean fallback = answer.deterministicFallback();
            long accepted = fallback ? 0 : acceptedPart.lines().filter(line -> line.startsWith("- ")).count();
            long rejected = fallback ? 0 : rejectedPart.lines().filter(line -> line.startsWith("- ")).count();
            List<String> keywords = strings(question.get("liveKeywords"));
            long covered = keywords.stream().filter(keyword -> acceptedPart.toLowerCase(Locale.ROOT).contains(keyword)).count();
            long violations = strings(question.get("mustNotContain")).stream().filter(answer.text()::contains).count();
            row.put("seconds", Math.round(seconds * 10) / 10.0);
            row.put("modelCalls", answer.modelCalls());
            row.put("accepted", accepted);
            row.put("rejected", rejected);
            row.put("keywordsCovered", covered);
            row.put("keywordsTotal", keywords.size());
            row.put("violations", violations);
            row.put("fallback", answer.deterministicFallback());
            putUsage(row, client.backend().usage());
        } catch (Exception error) {
            row.put("error", String.valueOf(error.getMessage()));
        }
        return row;
    }

    static String formatLiveRow(Map<String, Object> row) {
        if (row.containsKey("review")) {
            String head = String.format(Locale.ROOT, "ревью  %-20s", row.get("review"));
            if (row.containsKey("error")) return head + " ошибка — " + row.get("error");
            return head + String.format(Locale.ROOT,
                " %5.1f с  известные дефекты найдены %s/%s  прочие дефекты %s  дублирование/сложность %s  отклонено %s",
                ((Number) row.get("seconds")).doubleValue(), row.get("knownFound"), row.get("knownTotal"),
                row.get("otherDefects"), row.get("maintenance"), row.get("rejected"));
        }
        if (row.containsKey("task")) {
            String head = String.format(Locale.ROOT, "задача %-20s", row.get("task"));
            String result = head + String.format(Locale.ROOT,
                " %s  %5.1f с  шагов модели %s  упавших проверок %s  изменено %s  вне разрешённых %s  отклонено команд %s%s",
                Boolean.TRUE.equals(row.get("solved")) ? "РЕШЕНА  " : "не решена",
                ((Number) row.get("seconds")).doubleValue(), row.get("modelTurns"), row.get("failedChecks"),
                row.get("changedFiles"), row.get("outsideAllowed"), row.get("declinedCommands"),
                !Boolean.TRUE.equals(row.get("solved")) && Boolean.TRUE.equals(row.get("claimsSuccess"))
                    ? "  [модель заявила успех]" : "");
            if (row.containsKey("error")) result += "  ошибка — " + row.get("error");
            StringBuilder withTrace = new StringBuilder(result);
            for (Object line : row.get("trace") instanceof List<?> trace ? trace : List.of()) {
                withTrace.append("\n        ").append(line);
            }
            return withTrace.toString();
        }
        String head = String.format(Locale.ROOT, "%-13s %-60s", row.get("project"), "«" + row.get("prompt") + "»");
        if (row.containsKey("error")) return head + " ошибка — " + row.get("error");
        return head + String.format(Locale.ROOT, " %5.1f с  вызовов %s  принято %s  отклонено %s  ключевые факты %s/%s  нарушений %s%s",
            ((Number) row.get("seconds")).doubleValue(), row.get("modelCalls"), row.get("accepted"), row.get("rejected"),
            row.get("keywordsCovered"), row.get("keywordsTotal"), row.get("violations"),
            Boolean.TRUE.equals(row.get("fallback")) ? "  [резервный ответ]" : "");
    }

    /**
     * Меняется вместе с текстом {@link Main#changeWorkflow} или поведением инструментов агента:
     * 2 — без «разведки за один проход»; 3 — подсказки восстановления в ошибках инструментов;
     * 4 — напоминание о невнесённом исправлении ({@link ChangeNudge}).
     */
    static final int WORKFLOW_VERSION = 4;

    /** Дописывает результат живого прогона одной JSON-строкой, чтобы сравнивать модели и оборудование во времени. */
    static void record(Path file, LiveSettings live, Report report, String hardware) throws IOException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("date", java.time.OffsetDateTime.now().withNano(0).toString());
        entry.put("model", live.model());
        entry.put("backend", "ollama");
        entry.put("hardware", hardware);
        entry.put("contextWindow", live.contextWindow());
        entry.put("liveOnly", live.only() == null || live.only().isBlank() ? "all" : live.only());
        entry.put("reading", live.reading());
        // Версия подсказки сценария изменений: записи с разной подсказкой не сравниваются в одной строке.
        entry.put("workflow", WORKFLOW_VERSION);
        entry.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        entry.put("cpus", Runtime.getRuntime().availableProcessors());
        entry.put("java", System.getProperty("java.version"));
        Map<String, Object> metrics = new LinkedHashMap<>();
        report.metrics().forEach((name, metric) -> metrics.put(name, Math.round(metric.value() * 1000) / 1000.0));
        entry.put("deterministic", metrics);
        entry.put("questions", report.liveResults());
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, Json.stringify(entry) + "\n", StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    /**
     * Сводная таблица живых прогонов по (модель, адаптер, оборудование). Считается только из записанного файла,
     * поэтому её можно воспроизвести и сравнить с новой записью на той же модели и оборудовании.
     */
    static String compare(Path file) throws IOException {
        Map<String, List<Map<?, ?>>> groups = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            Map<?, ?> entry = (Map<?, ?>) Json.parse(line);
            String key = entry.get("model") + " | " + (entry.get("backend") == null ? "ollama" : entry.get("backend"))
                + " | " + (entry.get("hardware") == null ? "не указано" : entry.get("hardware"))
                + " | " + (entry.get("contextWindow") == null ? "—" : entry.get("contextWindow"))
                    + (entry.get("reading") == null ? "" : ", чтение " + entry.get("reading"))
                    + (entry.get("workflow") == null ? "" : ", подсказка " + entry.get("workflow"))
                + " | " + entry.get("os") + ", " + entry.get("cpus") + " CPU";
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }
        StringBuilder table = new StringBuilder("| Модель | Адаптер | Оборудование | Контекст | Система | Прогонов | Последний | "
            + "Вопросы: с/ответ | Без резервного ответа | Принято тезисов | Нарушений | Задачи решены | Упавших проверок | "
            + "Ошибок инструментов | Задачи: с | Ревью: известные дефекты | Ревью: прочие дефекты | Ток/с | "
            + "С уроком: решено, упавших проверок | Задачи: токенов подсказки | Задачи: шагов модели |\n");
        table.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, List<Map<?, ?>>> group : groups.entrySet()) {
            List<Map<?, ?>> questions = new ArrayList<>();
            List<Map<?, ?>> tasks = new ArrayList<>();
            List<Map<?, ?>> reviews = new ArrayList<>();
            List<Map<?, ?>> lessonTasks = new ArrayList<>();
            String last = "";
            for (Map<?, ?> entry : group.getValue()) {
                last = String.valueOf(entry.get("date"));
                for (Map<?, ?> row : maps(entry.get("questions"))) {
                    if (row.containsKey("error")) continue;
                    if (row.containsKey("task") && String.valueOf(row.get("task")).endsWith(" +урок")) lessonTasks.add(row);
                    else if (row.containsKey("task")) tasks.add(row);
                    else if (row.containsKey("review")) reviews.add(row);
                    else questions.add(row);
                }
            }
            double speed = java.util.stream.Stream.of(questions, tasks, reviews).flatMap(List::stream)
                .mapToDouble(row -> number(row.get("tokensPerSecond"))).filter(value -> value > 0).average().orElse(0);
            String[] parts = group.getKey().split(" \\| ");
            table.append("| ").append(String.join(" | ", parts)).append(" | ").append(group.getValue().size())
                .append(" | ").append(last.length() >= 10 ? last.substring(0, 10) : last)
                .append(" | ").append(format(average(questions, "seconds")))
                .append(" | ").append(ratio(questions.stream().filter(row -> !Boolean.TRUE.equals(row.get("fallback"))).count(), questions.size()))
                .append(" | ").append(format(average(questions, "accepted")))
                .append(" | ").append(format(sum(questions, "violations")))
                .append(" | ").append(ratio(tasks.stream().filter(row -> Boolean.TRUE.equals(row.get("solved"))).count(), tasks.size()))
                .append(" | ").append(format(average(tasks, "failedChecks")))
                .append(" | ").append(format(sum(tasks, "toolErrors")))
                .append(" | ").append(format(average(tasks, "seconds")))
                .append(" | ").append(ratio((long) sum(reviews, "knownFound"), (long) sum(reviews, "knownTotal")))
                .append(" | ").append(format(sum(reviews, "otherDefects")))
                .append(" | ").append(speed == 0 ? "—" : format(speed))
                .append(" | ").append(lessonTasks.isEmpty() ? "—"
                    : ratio(lessonTasks.stream().filter(row -> Boolean.TRUE.equals(row.get("solved"))).count(), lessonTasks.size())
                        + ", " + format(average(lessonTasks, "failedChecks")))
                .append(" | ").append(format(Math.rint(average(tasks, "promptTokens"))))
                .append(" | ").append(format(average(tasks, "modelTurns")))
                .append(" |\n");
        }
        return table + "\nПустые ячейки («—») означают, что в записях нет таких данных (например, старый формат записи).\n";
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static double average(List<Map<?, ?>> rows, String key) {
        return rows.stream().filter(row -> row.get(key) instanceof Number).mapToDouble(row -> number(row.get(key))).average().orElse(Double.NaN);
    }

    private static double sum(List<Map<?, ?>> rows, String key) {
        if (rows.isEmpty()) return Double.NaN;
        return rows.stream().filter(row -> row.get(key) instanceof Number).mapToDouble(row -> number(row.get(key))).sum();
    }

    private static String ratio(long part, long total) {
        return total == 0 ? "—" : part + "/" + total;
    }

    private static String format(double value) {
        if (Double.isNaN(value)) return "—";
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static void putUsage(Map<String, Object> row, ModelBackend.Usage usage) {
        row.put("promptTokens", usage.promptTokens());
        row.put("outputTokens", usage.outputTokens());
        row.put("tokensPerSecond", Math.round(usage.tokensPerSecond() * 10) / 10.0);
    }

    /** Разбирает ответ валидатора: "code", "tests", "documentation" или "rejected". */
    static String classify(String rendered, String statement) {
        int index = rendered.indexOf("- " + statement + " (");
        if (index < 0) return "rejected";
        int code = rendered.lastIndexOf("Подтверждено по коду", index);
        int tests = rendered.lastIndexOf("Подтверждено по тестам", index);
        int documentation = rendered.lastIndexOf("Указано в документации", index);
        if (code > tests && code > documentation) return "code";
        return tests > documentation ? "tests" : "documentation";
    }

    private static String evidenceId(ProjectInspection.Inspection inspection, String path, String contains) {
        int id = 1;
        for (ProjectInspection.EvidenceFile file : inspection.evidenceFiles()) {
            for (int lineNumber : file.lineNumbers()) {
                String line = file.line(lineNumber);
                if (file.path().equals(path) && line != null && line.contains(contains)) {
                    return String.format(Locale.ROOT, "E%05d", id);
                }
                id++;
            }
        }
        return null;
    }

    private static void checkLeaks(Map<String, Metric> metrics, String where, String text, List<String> forbidden) {
        for (String secret : forbidden) {
            metrics.get("leakFree").record(!text.contains(secret), where + ": найдено «" + secret + "»");
        }
    }

    private static String placeholders(String value) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return value.replace("{mvnw}", windows ? ".\\mvnw.cmd" : "./mvnw")
            .replace("{mvnw-file}", windows ? "mvnw.cmd" : "mvnw");
    }

    /** Разворачивает фикстуру формата «=== путь ===» во временный каталог. */
    static Path materialize(String fixture) throws IOException {
        Path root = Files.createTempDirectory("kuleshato-bench-");
        String path = null;
        StringBuilder content = new StringBuilder();
        for (String line : (fixture + "\n=== \u0000 ===").split("\\R", -1)) {
            if (line.startsWith("=== ") && line.endsWith(" ===") && line.length() > 8) {
                if (path != null) {
                    // «=== test.cmd [windows] ===» и «=== test.sh [unix] ===» создаются только на своей ОС.
                    String target = path.replaceFirst("\\s*\\[(windows|unix)]$", "");
                    boolean wanted = path.endsWith("[windows]") ? WINDOWS : path.endsWith("[unix]") ? !WINDOWS : !path.endsWith("]");
                    Path file = root.resolve(target).normalize();
                    if (!file.startsWith(root)) throw new IOException("Путь фикстуры вне каталога: " + path);
                    if (wanted) {
                        Files.createDirectories(file.getParent());
                        Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
                        if (!WINDOWS && target.endsWith(".sh")) {
                            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
                        }
                    }
                }
                path = line.substring(4, line.length() - 4).strip();
                content.setLength(0);
            } else if (path != null) {
                java.util.regex.Matcher repeat = java.util.regex.Pattern.compile("^<<repeat (\\d{1,4}): (.*)>>$").matcher(line);
                if (repeat.matches()) {
                    for (int i = 0; i < Integer.parseInt(repeat.group(1)); i++) content.append(repeat.group(2)).append('\n');
                } else if (line.startsWith("<<repeat")) {
                    throw new IOException("Некорректная директива фикстуры (нужно <<repeat N: текст>>): " + line);
                } else {
                    content.append(line).append('\n');
                }
            }
        }
        return root;
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = ProjectBenchmark.class.getClassLoader().getResourceAsStream(RESOURCE_ROOT + name)) {
            if (stream != null) return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path fallback = Path.of("src", "test", "resources", "benchmark").resolve(name);
        if (Files.isRegularFile(fallback)) return Files.readString(fallback);
        throw new IOException("Не найден ресурс эталонного набора: " + name);
    }

    private static List<String> strings(Object value) {
        if (value == null) return List.of();
        return ((List<?>) value).stream().map(String.class::cast).toList();
    }

    private static List<Map<?, ?>> maps(Object value) {
        if (value == null) return List.of();
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object item : (List<?>) value) result.add((Map<?, ?>) item);
        return result;
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                // git помечает объекты только для чтения; на Windows их нужно сделать записываемыми перед удалением.
                if (!Files.isWritable(path)) path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }
}
