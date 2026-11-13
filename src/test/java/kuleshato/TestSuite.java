package kuleshato;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class TestSuite {
    private static int passed;

    public static void main(String[] args) throws Exception {
        runAll();
    }

    static void runAll() throws Exception {
        passed = 0;
        test("JSON round-trips nested values", TestSuite::jsonRoundTrip);
        test("JSON preserves integer and decimal number types", TestSuite::jsonNumberTypes);
        test("strict Java project gate accepts Java sources", TestSuite::javaProjectAccepted);
        test("Java project gate accepts standalone Java files", TestSuite::standaloneJavaAccepted);
        test("Java project gate ignores Java examples outside source roots", TestSuite::unrelatedJavaFileRejected);
        test("strict Java project gate rejects non-Java directories", TestSuite::nonJavaProjectRejected);
        test("project settings reject remote Ollama endpoints", TestSuite::remoteEndpointRejected);
        test("project settings accept loopback addresses only", TestSuite::loopbackEndpointValidation);
        test("project settings load supported values", TestSuite::projectSettingsLoad);
        test("project memory initializes only once", TestSuite::projectMemoryRoundTrip);
        test("session store restricts sessions to their workspace", TestSuite::sessionStoreWorkspace);
        test("built-in skills do not include a duplicate project brief", TestSuite::builtinSkills);
        test("skill list includes a plain-language usage example", TestSuite::skillsShowUsageExamples);
        test("workspace diff highlights only changed content", TestSuite::diffFormatting);
        test("workspace tools reject paths outside the Java project", TestSuite::workspacePathBoundary);
        test("command output is bounded while it is drained", TestSuite::boundedCommandOutput);
        test("failed assistant turns restore the full conversation", TestSuite::failedAssistantTurnCanBeRolledBack);
        test("project inquiries detect factual verification phrasing", TestSuite::projectInquiryDetection);
        test("reference project questions resolve to fast, evidence-grounded paths", TestSuite::referenceProjectQuestions);
        test("project improvement follow-ups reuse the current project context", TestSuite::projectImprovementFollowUp);
        test("project purpose questions use README evidence without model generation", TestSuite::projectPurposeAnswer);
        test("Kuleshato self-description checks return a grounded local answer", TestSuite::kuleshatoSelfDescription);
        test("project inquiries include deterministic source evidence", TestSuite::projectInspectionEvidence);
        test("project claims require exact executable source evidence", TestSuite::projectAnswerGrounding);
        test("Ollama requests enable thinking and preserve plan structure", TestSuite::ollamaContract);
        test("Java project brief reports Java and build metadata", TestSuite::javaBrief);
        test("Maven facts carry file, line and origin", TestSuite::mavenFacts);
        test("Gradle facts carry file, line and origin", TestSuite::gradleFacts);
        test("CI facts extract workflows, Java setup and commands", TestSuite::ciFacts);
        test("project settings facts hide values that may be secrets", TestSuite::settingsFacts);
        test("README commands are labelled as documentation", TestSuite::documentationCommands);
        test("malformed or unsafe build files produce warnings, not facts", TestSuite::unsafeBuildFiles);
        test("Maven facts cite the line where a multi-line tag starts", TestSuite::multiLineTagProvenance);
        test("claims naming technologies absent from the cited lines are rejected", TestSuite::technicalTokensRequireEvidence);
        test("reference benchmark meets its quality thresholds", TestSuite::referenceBenchmark);
        test("command policy auto-runs only detected checks verbatim", TestSuite::commandPolicy);
        test("declined changes and commands leave the workspace untouched", TestSuite::declinedActionsHaveNoSideEffects);
        test("failed checks are reported with bounded output and a cycle limit", TestSuite::failedCheckCycles);
        test("long command output is shortened around error lines", TestSuite::boundedModelOutput);
        test("agent loop fixes a failing check and reports the real outcome", TestSuite::agentFixLoop);
        test("agent loop reports a declined change instead of success", TestSuite::agentDeclinedChange);
        test("review finds method bodies and merges line references", TestSuite::reviewMethodSpans);
        test("tool loop and review work through any model backend", TestSuite::backendIndependence);
        test("live benchmark records compare by model and hardware", TestSuite::benchmarkComparison);
        test("project memory fits the context window budget", TestSuite::memoryBudget);
        test("read_file returns bounded verbatim fragments", TestSuite::keyholeReads);
        test("tool errors tell a small model how to recover", TestSuite::toolRecoveryHints);
        test("project overview maps code and README features without the model", TestSuite::projectOverview);
        test("lessons are appended to project memory without losing notes", TestSuite::lessonsInMemory);
        test("lessons are offered only after a real error and never silently", TestSuite::lessonDecisions);
        System.out.println("Passed " + passed + " Java tests.");
    }

    private static void jsonRoundTrip() {
        Map<String, Object> input = Map.of("name", "Kuleshato", "items", List.of(1, true, "тест"));
        Object parsed = Json.parse(Json.stringify(input));
        check(parsed instanceof Map<?, ?>);
        check(((Map<?, ?>) parsed).get("name").equals("Kuleshato"));
        check(((List<?>) ((Map<?, ?>) parsed).get("items")).size() == 3);
    }

    private static void jsonNumberTypes() {
        Object parsed = Json.parse("[0,1.0,1e3,9223372036854775808]");
        List<?> values = (List<?>) parsed;
        check(values.get(0) instanceof Long && values.get(0).equals(0L));
        check(values.get(1) instanceof java.math.BigDecimal && values.get(1).equals(new java.math.BigDecimal("1.0")));
        check(values.get(2) instanceof java.math.BigDecimal && values.get(2).equals(new java.math.BigDecimal("1e3")));
        check(values.get(3) instanceof java.math.BigInteger && values.get(3).equals(new java.math.BigInteger("9223372036854775808")));
        check(Json.stringify(parsed).equals("[0,1.0,1E+3,9223372036854775808]"));
    }

    private static void javaProjectAccepted() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-java-");
        try {
            Path source = root.resolve("src/main/java/example/App.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package example; class App {}");
            check(JavaProject.requireWorkspace(root).equals(root.toRealPath()));
        } finally { deleteTree(root); }
    }

    private static void nonJavaProjectRejected() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-non-java-");
        try {
            Files.writeString(root.resolve("package.json"), "{\"name\":\"plain-js\"}");
            boolean rejected = false;
            try { JavaProject.requireWorkspace(root); }
            catch (java.io.IOException error) { rejected = error.getMessage().contains("только с Java-проектами"); }
            check(rejected);
        } finally { deleteTree(root); }
    }

    private static void standaloneJavaAccepted() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-standalone-java-");
        try {
            Files.writeString(root.resolve("Main.java"), "class Main {}");
            check(JavaProject.containsJavaProject(root));
        } finally { deleteTree(root); }
    }

    private static void unrelatedJavaFileRejected() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-java-example-");
        try {
            Path docs = root.resolve("docs/examples");
            Files.createDirectories(docs);
            Files.writeString(docs.resolve("Hello.java"), "class Hello {}");
            check(!JavaProject.containsJavaProject(root));
            Path docsSource = root.resolve("docs/src");
            Files.createDirectories(docsSource);
            Files.writeString(docsSource.resolve("Example.java"), "class Example {}");
            check(!JavaProject.containsJavaProject(root));
            Files.writeString(root.resolve("pom.xml"), "<project/>");
            check(JavaProject.containsJavaProject(root));
        } finally { deleteTree(root); }
    }

    private static void remoteEndpointRejected() {
        boolean rejected = false;
        try { ProjectConfig.validateOllamaUrl("http://example.com:11434"); }
        catch (java.io.IOException expected) { rejected = true; }
        check(rejected);
    }

    private static void loopbackEndpointValidation() throws Exception {
        ProjectConfig.validateOllamaUrl("http://127.0.0.7:11434");
        ProjectConfig.validateOllamaUrl("http://localhost:11434");
        ProjectConfig.validateOllamaUrl("http://[::1]:11434");
        boolean rejected = false;
        try { ProjectConfig.validateOllamaUrl("http://127.0.0.1.example.org:11434"); }
        catch (java.io.IOException expected) { rejected = true; }
        check(rejected);
    }

    private static void projectSettingsLoad() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-settings-");
        try {
            Path file = root.resolve(".kuleshato/config.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"model\":\"qwen3:8b\",\"contextWindow\":8192,\"autoCompact\":false}");
            ProjectConfig settings = ProjectConfig.load(root);
            check(settings.contextWindow() == 8192);
            check(!settings.autoCompact());
            check(settings.ollamaUrl().equals("http://127.0.0.1:11434"));
        } finally { deleteTree(root); }
    }

    private static void projectMemoryRoundTrip() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-memory-");
        try {
            String initial = ProjectMemory.initialize(root);
            check(initial.contains("Java-проект"));
            check(ProjectMemory.read(root).equals(initial));
            boolean duplicateRejected = false;
            try { ProjectMemory.initialize(root); }
            catch (java.io.IOException expected) { duplicateRejected = true; }
            check(duplicateRejected);
        } finally { deleteTree(root); }
    }

    private static void sessionStoreWorkspace() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-session-");
        Path other = Files.createTempDirectory("kuleshato-other-");
        Path history = Files.createTempDirectory("kuleshato-history-");
        try {
            SessionStore store = new SessionStore(history);
            Map<String, Object> session = store.create(root, "qwen3:8b");
            session.put("messages", List.of(Map.of("role", "system", "content", "test")));
            store.save(session);
            check(store.load((String) session.get("id"), root).get("messages") instanceof List<?>);
            boolean rejected = false;
            try { store.load((String) session.get("id"), other); }
            catch (java.io.IOException expected) { rejected = true; }
            check(rejected);
        } finally {
            deleteTree(root);
            deleteTree(other);
            deleteTree(history);
        }
    }

    private static void builtinSkills() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-skills-");
        try {
            SkillRegistry.Catalog catalog = SkillRegistry.discover(root);
            List<String> names = catalog.skills().stream().map(SkillRegistry.Skill::name).toList();
            check(names.containsAll(List.of("verify", "simplify", "skillify", "remember", "java-notes")));
            check(!names.contains("project-brief"));
            SkillRegistry.Skill javaNotes = catalog.skills().stream()
                .filter(skill -> skill.name().equals("java-notes"))
                .findFirst().orElseThrow();
            check(javaNotes.instructions().contains("PreparedStatement"));
            check(javaNotes.instructions().contains("CSRF"));
            check(javaNotes.instructions().contains("BigDecimal"));
        } finally { deleteTree(root); }
    }

    private static void skillsShowUsageExamples() throws Exception {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (java.io.PrintStream capture = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(capture);
            new TerminalUI(false).showSkills(List.of(Map.of(
                "name", "remember",
                "description", "Сохраняет устойчивые правила проекта"
            )), List.of());
        } finally {
            System.setOut(original);
        }
        String text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        check(text.contains("Сохраняет устойчивые правила проекта"));
        check(text.contains("Пример:"));
        check(text.contains("какие постоянные правила проекта стоит сохранить"));
        check(text.contains("/skill enable remember"));
    }

    private static void diffFormatting() {
        String diff = WorkspaceTools.formatDiff("src/App.java", "first\nold\nlast", "first\nnew\nlast");
        check(diff.contains("-old"));
        check(diff.contains("+new"));
        check(!diff.contains("-first"));
    }

    private static void workspacePathBoundary() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-tools-");
        Path outside = Files.createTempFile("kuleshato-outside-", ".txt");
        try {
            Files.writeString(outside, "private");
            WorkspaceTools tools = new WorkspaceTools(root, command -> false, (path, diff) -> false);
            boolean rejected = false;
            try { tools.execute("read_file", Map.of("path", "../" + outside.getFileName())); }
            catch (java.io.IOException expected) { rejected = true; }
            check(rejected);
            String result = tools.execute("write_file", Map.of("path", "new.java", "content", "class New {}"));
            check(result.contains("not approved"));
            check(!Files.exists(root.resolve("new.java")));
        } finally {
            deleteTree(root);
            Files.deleteIfExists(outside);
        }
    }

    private static void boundedCommandOutput() throws Exception {
        byte[] source = new byte[100_000];
        java.util.Arrays.fill(source, (byte) 'x');
        java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(source);
        WorkspaceTools.CommandOutput output = WorkspaceTools.readLimited(input, 4096);
        check(output.content().length == 4096);
        check(output.truncated());
        check(input.available() == 0);

        WorkspaceTools.CommandOutput exact = WorkspaceTools.readLimited(
            new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}), 3);
        check(exact.content().length == 3);
        check(!exact.truncated());
    }

    private static void failedAssistantTurnCanBeRolledBack() {
        List<Object> messages = new java.util.ArrayList<>(List.of(
            Map.of("role", "system", "content", "system"),
            new java.util.LinkedHashMap<>(Map.of(
                "role", "assistant",
                "content", "earlier",
                "tool_calls", new java.util.ArrayList<>()
            ))
        ));
        List<Object> snapshot = Main.snapshotMessages(messages);
        messages.clear();
        messages.add(Map.of("role", "user", "content", "failed request"));
        messages.add(Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "partial"))));
        Main.restoreMessages(messages, snapshot);
        check(messages.equals(snapshot));
        check(messages.size() == 2);
        check(((Map<?, ?>) messages.get(1)).get("content").equals("earlier"));
    }

    private static void projectInquiryDetection() {
        String prompt = "верно ли сказать о текущем проекте так: Что он умеет сейчас: сканирует папку проекта "
            + "(стек, команды, тесты, CI, документацию) и отвечает на вопросы о репозитории "
            + "с помощью локальной модели (qwen3:8b через Ollama)";
        check(ProjectInspection.isProjectRequest(prompt));
        check(ProjectInspection.isProjectRequest("Что можно улучшить в проекте?"));
        check(ProjectInspection.isProjectRequest("о чем код?"));
        check(ProjectInspection.isProjectRequest("изучи проект, расскажи о нем"));
        check(ProjectInspection.isProjectOverviewQuestion("о чем код?"));
        check(ProjectInspection.isProjectOverviewQuestion("изучи проект, расскажи о нем"));
        check(ProjectInspection.isProjectRequest("что улучшить?"));
    }

    private static void referenceProjectQuestions() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-reference-questions-");
        try {
            Files.createDirectories(root.resolve("src/main/java/example"));
            Files.writeString(root.resolve("README.md"),
                "# Example\n\nExample is a local Java assistant for developers.");
            Files.writeString(root.resolve("pom.xml"),
                "<project><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
            Files.writeString(root.resolve("src/main/java/example/Main.java"), "class Main {}");
            for (String prompt : List.of(
                "о чем код?",
                "изучи проект, расскажи о нем",
                "изучи текущий проект и дай ответ, для чего он создан")) {
                ProjectInspection.Inspection inspection = ProjectInspection.collect(root, prompt, 6_000);
                String answer = ProjectInspection.renderProjectOverview(inspection);
                check(answer != null);
                check(answer.contains("local Java assistant"));
                check(answer.contains("README.md:3"));
                check(answer.contains("# Java-проект:"));
                check(answer.contains("не подтверждает поведение"));
            }
        } finally { deleteTree(root); }
    }

    private static void projectImprovementFollowUp() {
        String prompt = "что в нем можно улучшить?";
        List<Object> recentProjectConversation = List.of(
            Map.of("role", "system", "content", "system"),
            Map.of("role", "user", "content", "изучи текущий проект и дай ответ, для чего он создан"),
            Map.of("role", "assistant", "content", "Это Java-проект.")
        );
        check(ProjectInspection.isProjectImprovementQuestion(prompt));
        check(ProjectInspection.isProjectFollowUp(prompt, recentProjectConversation));
        check(!ProjectInspection.isProjectFollowUp(prompt, List.of(
            Map.of("role", "user", "content", "объясни try-with-resources в Java"))));
        check(!ProjectInspection.isProjectFollowUp(prompt, List.of(
            Map.of("role", "user", "content", "изучи текущий проект"),
            Map.of("role", "user", "content", "объясни try-with-resources в Java"),
            Map.of("role", "assistant", "content", "Это конструкция для ресурсов.")
        )));
        ProjectInspection.Inspection inspection = new ProjectInspection.Inspection(
            "Проверенный приложением структурный обзор:\n## Тесты\n- src/test/java/kuleshato/TestSuite.java\n"
                + "## CI\n- CI-конфигурация не найдена при сканировании.\n",
            List.of(
                new ProjectInspection.EvidenceFile(
                    "src/main/java/kuleshato/Main.java", List.of("class Main {}"), true, true),
                new ProjectInspection.EvidenceFile(
                    "src/main/java/kuleshato/ProjectInspection.java", List.of("class ProjectInspection {}"), true, true)
            ), List.of(), List.of(), true, false);
        String answer = ProjectInspection.renderProjectImprovementSuggestions(inspection);
        check(answer.contains("Добавить CI"));
        check(answer.contains("измерение покрытия"));
        check(answer.contains("эталонный набор вопросов"));
        check(answer.contains("не полноценное code review"));
        ProjectInspection.Inspection compactKuleshato = new ProjectInspection.Inspection(
            "Проверенный приложением структурный обзор:\n# Java-проект: kuleshato\n## CI\n"
                + "- CI-конфигурация не найдена при сканировании.\n",
            List.of(), List.of(), List.of(), true, false);
        check(ProjectInspection.renderProjectImprovementSuggestions(compactKuleshato)
            .contains("эталонный набор вопросов"));
    }

    private static void kuleshatoSelfDescription() {
        String prompt = "верно ли сказать о текущем проекте так: сканирует папку и отвечает через Ollama";
        ProjectInspection.Inspection inspection = new ProjectInspection.Inspection(
            "Проверенный приложением структурный обзор:\n## Java\n- Версия: 21\n## Тесты\n- TestSuite.java\n",
            List.of(
                new ProjectInspection.EvidenceFile(
                    "src/main/java/kuleshato/Main.java", List.of("ProjectInspection.collect(root, prompt, limit);"), true, true),
                new ProjectInspection.EvidenceFile(
                    "src/main/java/kuleshato/OllamaBackend.java", List.of("post(\"/api/chat\", request);"), true, true)
            ), List.of(), List.of(), true, false);
        check(ProjectInspection.isKuleshatoSelfDescriptionCheck(prompt, inspection));
        String answer = ProjectInspection.renderKuleshatoSelfDescription(
            inspection, "qwen3:8b", "http://127.0.0.1:11434");
        check(answer.contains("В целом — да"));
        check(answer.contains("Main.java:1"));
        check(answer.contains("OllamaBackend.java:1"));
        check(answer.contains("не глубокий анализ каждого файла"));
        check(answer.contains("Модель можно переопределить"));
    }

    private static void projectPurposeAnswer() throws Exception {
        check(ProjectInspection.isProjectRequest("Изучи текущий проект и дай ответ, для чего он создан"));
        check(ProjectInspection.isProjectPurposeQuestion("Изучи текущий проект и дай ответ, для чего он создан"));
        Path root = Files.createTempDirectory("kuleshato-purpose-");
        try {
            Files.createDirectories(root.resolve("src/main/java/example"));
            Files.writeString(root.resolve("README.md"),
                "# Example\n\nExample is a local Java assistant for developers.");
            Files.writeString(root.resolve("src/main/java/example/Main.java"), "class Main {}");
            ProjectInspection.Inspection inspection = ProjectInspection.collect(
                root, "Изучи текущий проект и дай ответ, для чего он создан", 6_000);
            check(inspection.filesRead().get(0).equals("README.md"));
            String answer = ProjectInspection.renderProjectPurpose(inspection);
            check(answer.contains("Судя по документации"));
            check(answer.contains("README.md:3"));
            check(answer.contains("не независимая проверка"));
        } finally { deleteTree(root); }
    }

    private static void projectInspectionEvidence() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-inspection-");
        try {
            Files.createDirectories(root.resolve("src/main/java/example"));
            Files.writeString(root.resolve("README.md"), "# Example\nA local Java application using Ollama and qwen3:8b.");
            Files.writeString(root.resolve("pom.xml"), "<project><artifactId>example</artifactId></project>");
            Files.writeString(root.resolve("src/main/java/example/Main.java"), "class Main { void run() {} }");
            List<String> clientLines = new java.util.ArrayList<>();
            clientLines.add("class OllamaClient {");
            for (int i = 0; i < 40; i++) clientLines.add("// unrelated implementation detail");
            clientLines.add("Map<String, Object> response = post(\"/api/chat\", request);");
            Files.writeString(root.resolve("src/main/java/example/OllamaClient.java"), String.join("\n", clientLines));
            int ollamaCallLine = clientLines.size();
            Files.writeString(root.resolve("src/main/java/example/Client.java"),
                "class Client { String provider = \"Ollama\"; }");
            Files.createDirectories(root.resolve("src/test/java/example"));
            Files.writeString(root.resolve("src/test/java/example/OllamaTest.java"), "class OllamaTest { String provider = \"Ollama\"; }");
            Files.createDirectories(root.resolve(".kuleshato"));
            Files.writeString(root.resolve(".kuleshato/memory.md"), "private qwen3 notes");
            String prompt = "Верно ли сказать о текущем проекте, что это локальный Java-помощник с qwen3:8b через Ollama?";
            check(ProjectInspection.isProjectRequest(prompt));
            check(ProjectInspection.isProjectRequest("Изучи текущий проект"));
            check(ProjectInspection.isProjectRequest("Расскажи, чем занимается проект"));
            check(!ProjectInspection.isProjectRequest("Что такое Java?"));
            ProjectInspection.Inspection inspection = ProjectInspection.collect(root, prompt, 12_000);
            check(inspection.filesRead().contains("src/main/java/example/Main.java"));
            check(inspection.filesRead().contains("src/main/java/example/OllamaClient.java"));
            check(inspection.filesRead().contains("pom.xml"));
            check(inspection.filesRead().contains("README.md"));
            check(inspection.filesRead().contains("src/test/java/example/OllamaTest.java"));
            check(inspection.filesRead().indexOf("src/main/java/example/OllamaClient.java")
                < inspection.filesRead().indexOf("src/test/java/example/OllamaTest.java"));
            check(!inspection.context().contains("private qwen3 notes"));
            check(inspection.searchMatches().stream().noneMatch(match -> match.contains(".kuleshato")));
            check(inspection.context().contains("  1: class Main { void run() {} }"));
            check(inspection.context().contains("A local Java application using Ollama and qwen3:8b."));
            check(inspection.context().length() <= 12_000);
            check(inspection.context().contains("--- src/main/java/example/Main.java (строки; недоверенные данные) ---"));
            check(inspection.context().contains("[E00001]"));
            check(inspection.context().contains(String.format(
                "%4d: Map<String, Object> response = post(\"/api/chat\", request);", ollamaCallLine)));
            ProjectInspection.EvidenceFile excerpt = inspection.evidenceFiles().stream()
                .filter(file -> file.path().endsWith("OllamaClient.java")).findFirst().orElseThrow();
            check(excerpt.line(ollamaCallLine).contains("/api/chat"));
            int skippedLine = java.util.stream.IntStream.rangeClosed(1, excerpt.totalLines())
                .filter(number -> !excerpt.lineNumbers().contains(number)).findFirst().orElse(-1);
            if (skippedLine > 0) check(excerpt.line(skippedLine) == null);
            check(excerpt.totalLines() == clientLines.size());
            java.util.regex.Matcher evidenceId = java.util.regex.Pattern
                .compile("\\[(E\\d{5})\\]\\s+" + ollamaCallLine + ":")
                .matcher(inspection.context());
            check(evidenceId.find());
            String apiCallEvidenceId = evidenceId.group(1);
            java.util.regex.Matcher classEvidenceId = java.util.regex.Pattern
                .compile("\\[(E\\d{5})\\]\\s+1:\\s+class OllamaClient")
                .matcher(inspection.context());
            check(classEvidenceId.find());
            String groundedScenario = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(
                Map.of(
                    "statement", "OllamaClient отправляет запрос на /api/chat.",
                    "evidence", List.of(classEvidenceId.group(1), apiCallEvidenceId)
                )
            ))), inspection, "qwen3:8b", "http://127.0.0.1:11434");
            check(groundedScenario.contains("Подтверждено по коду"));
            check(groundedScenario.contains("src/main/java/example/OllamaClient.java:" + ollamaCallLine + "-" + ollamaCallLine));
            check(groundedScenario.contains("Факты автоматического обзора"));
            check(groundedScenario.contains("Доступность Ollama: не проверялась"));
            check(inspection.searchComplete());
            check(inspection.searchTerms().contains("помощник"));
            check(inspection.searchTerms().contains("ollama"));
            check(inspection.searchTerms().contains("qwen3"));
            check(!inspection.searchTerms().contains("так"));
            check(!inspection.searchTerms().contains("через"));
            check(inspection.searchMatches().stream().anyMatch(match ->
                match.startsWith("src/main/java/example/Client.java:1:")));
            ProjectInspection.Inspection compactInspection = ProjectInspection.collect(root, prompt, 6_000);
            check(compactInspection.filesRead().contains("src/main/java/example/Main.java"));
            check(compactInspection.filesRead().contains("src/main/java/example/OllamaClient.java"));
            check(compactInspection.filesRead().contains("pom.xml"));
            ProjectInspection.EvidenceFile compactClient = compactInspection.evidenceFiles().stream()
                .filter(file -> file.path().endsWith("OllamaClient.java")).findFirst().orElseThrow();
            check(compactClient.lines().stream().anyMatch(line -> line.contains("/api/chat")));
            Files.writeString(root.resolve("many-matches.txt"), "Ollama appears here.\n".repeat(120));
            ProjectInspection.Inspection manyMatches = ProjectInspection.collect(root, "Изучи проект так через Ollama", 12_000);
            check(manyMatches.searchMatches().size() == 100);
            check(manyMatches.searchComplete());
        } finally { deleteTree(root); }
    }

    private static void projectAnswerGrounding() throws Exception {
        ProjectInspection.Inspection inspection = new ProjectInspection.Inspection(
            "", List.of(
                new ProjectInspection.EvidenceFile("README.md", List.of("# Example"), true, false),
                new ProjectInspection.EvidenceFile(
                    "src/main/java/example/OllamaClient.java",
                    List.of(
                        "    OllamaClient client = new OllamaClient(url, model, contextWindow, tools);",
                        "    Map<String, Object> response = post(\"/api/chat\", request);",
                        "    int context = 40960;"
                    ),
                    true,
                    true
                )
            ),
            List.of("ollama"), List.of("src/main/java/OllamaClient.java:12: Ollama integration"), true, false
        );
        String valid = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(
            Map.of(
                "statement", "OllamaClient отправляет запрос на /api/chat.",
                "evidence", List.of(Map.of(
                    "path", "src/main/java/example/OllamaClient.java",
                    "startLine", 1,
                    "endLine", 2,
                    "quote", "OllamaClient client = new OllamaClient(url, model, contextWindow, tools);\n"
                        + "Map<String, Object> response = post(\"/api/chat\", request);"
                ))
            )
        ))), inspection);
        check(valid.contains("OllamaClient отправляет запрос"));
        check(valid.contains("src/main/java/example/OllamaClient.java:1-2"));
        String idBasedEvidence = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(
            Map.of(
                "statement", "Клиент отправляет запрос на /api/chat.",
                "evidence", List.of("E00003")
            )
        ))), inspection);
        check(idBasedEvidence.contains("Подтверждено по коду"));
        check(idBasedEvidence.contains("src/main/java/example/OllamaClient.java:2-2"));
        String flexibleModelSchema = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of(
            "claims", List.of(Map.of(
                "statement", "Клиент отправляет запрос на /api/chat.",
                "evidence", "E00003",
                "confidence", 0.9
            )),
            "format_version", 1
        )), inspection);
        check(flexibleModelSchema.contains("Подтверждено по коду"));
        String unknownEvidenceId = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(
            Map.of(
                "statement", "OllamaClient отправляет запрос на /api/chat.",
                "evidence", List.of("E99999")
            )
        ))), inspection);
        check(unknownEvidenceId.contains("ID доказательства отсутствует"));
        String partialLineWithoutIndent = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(
            Map.of(
                "statement", "Клиент отправляет запрос.",
                "evidence", List.of(Map.of(
                    "path", "src/main/java/example/OllamaClient.java",
                    "startLine", 2,
                    "endLine", 2,
                    "quote", "response = post(\"/api/chat\", request);"
                ))
            )
        ))), inspection);
        check(partialLineWithoutIndent.contains("Подтверждено по коду"));
        String incorrectQuote = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(
            Map.of(
                "statement", "OllamaClient отправляет запрос на /api/chat.",
                "evidence", List.of(Map.of(
                    "path", "src/main/java/example/OllamaClient.java",
                    "startLine", 2,
                    "endLine", 2,
                    "quote", "response = post(\"/api/wrong\", request);"
                ))
            )
        ))), inspection);
        check(incorrectQuote.contains("цитата не совпадает с указанными строками"));
        String documentation = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(
            Map.of(
                "statement", "Документация содержит заголовок Example.",
                "evidence", List.of(Map.of(
                    "path", "README.md", "startLine", 1, "endLine", 1, "quote", "# Example"
                ))
            )
        ))), inspection);
        check(documentation.contains("Указано в документации"));
        check(!documentation.contains("Подтверждено по коду"));
        String withRuntimeConfig = ProjectInspection.renderVerifiedAnswer(
            "{\"claims\":[]}", inspection, "qwen3:8b", "http://127.0.0.1:11434");
        check(withRuntimeConfig.contains("Модель: qwen3:8b"));
        check(withRuntimeConfig.contains("Сервер Ollama: http://127.0.0.1:11434"));
        check(withRuntimeConfig.contains("не подтверждают, что сервер доступен"));
        ProjectInspection.Inspection withBrief = new ProjectInspection.Inspection(
            "Проверенный приложением структурный обзор:\nJava-файлов: 4\n\n--- Main.java ---\n",
            inspection.evidenceFiles(), inspection.searchTerms(), inspection.searchMatches(),
            inspection.searchComplete(), inspection.truncated());
        String deterministicSummary = ProjectInspection.renderVerifiedAnswer(
            "{\"claims\":[]}", withBrief, "qwen3:8b", "http://127.0.0.1:11434");
        check(deterministicSummary.contains("Факты автоматического обзора"));
        check(deterministicSummary.contains("Java-файлов: 4"));

        String wrongNumber = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(
            Map.of(
                "statement", "Контекст составляет 4096 токенов.",
                "evidence", List.of(Map.of(
                    "path", "src/main/java/example/OllamaClient.java",
                    "startLine", 3,
                    "endLine", 3,
                    "quote", "int context = 40960;"
                ))
            )
        ))), inspection);
        check(wrongNumber.contains("технология или число в тезисе отсутствует в цитате"));

        String docsOnly = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(
            Map.of(
                "statement", "OllamaClient отправляет запрос на /api/chat.",
                "evidence", List.of(Map.of(
                    "path", "README.md", "startLine", 1, "endLine", 1, "quote", "# Example"
                ))
            )
        ))), inspection);
        check(docsOnly.contains("технология или число в тезисе отсутствует в цитате"));

        String fabricatedCitation = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(
            Map.of(
                "statement", "OllamaClient отправляет запрос на /api/chat.",
                "evidence", List.of(Map.of(
                    "path", "src/main/java/Other.java", "startLine", 1, "endLine", 1, "quote", "Ollama"
                ))
            )
        ))), inspection);
        check(fabricatedCitation.contains("указанный файл не был передан на проверку"));
        check(fabricatedCitation.contains("src/main/java/Other.java:1-1"));

        String negative = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(
            Map.of(
                "statement", "В проекте нет интеграции с Ollama.",
                "evidence", List.of(Map.of(
                    "path", "src/main/java/example/OllamaClient.java",
                    "startLine", 1,
                    "endLine", 1,
                    "quote", "OllamaClient client = new OllamaClient(url, model, contextWindow, tools);"
                ))
            )
        ))), inspection);
        check(negative.contains("отрицательные утверждения не подтверждаются"));

        String fractionalLine = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(
            Map.of(
                "statement", "OllamaClient отправляет запрос на /api/chat.",
                "evidence", List.of(Map.of(
                    "path", "src/main/java/example/OllamaClient.java",
                    "startLine", new java.math.BigDecimal("1.5"),
                    "endLine", 1,
                    "quote", "OllamaClient client = new OllamaClient(url, model, contextWindow, tools);"
                ))
            )
        ))), inspection);
        check(fractionalLine.contains("диапазон строк некорректен"));
        boolean malformedJsonRejected = false;
        try {
            ProjectInspection.renderVerifiedAnswer("{not-json", inspection);
        } catch (ProjectInspection.InvalidProjectAnswerException error) {
            malformedJsonRejected = error.getMessage().contains("некорректный JSON");
        }
        check(malformedJsonRejected);
        boolean malformedSchemaRejected = false;
        try {
            ProjectInspection.renderVerifiedAnswer("{\"answer\":\"unstructured\"}", inspection);
        } catch (ProjectInspection.InvalidProjectAnswerException error) {
            malformedSchemaRejected = error.getMessage().contains("неверном формате");
        }
        check(malformedSchemaRejected);
        String deterministicFallback = ProjectInspection.renderUnavailableAnswer(
            inspection, "qwen3:8b", "http://127.0.0.1:11434", "JSON schema mismatch");
        check(deterministicFallback.contains("только результаты локального сканирования"));
        check(deterministicFallback.contains("OllamaClient.java:2"));
        check(deterministicFallback.contains("JSON schema mismatch"));
    }

    private static void ollamaContract() throws Exception {
        com.sun.net.httpserver.HttpServer server =
            com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> toolFollowupBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/api/chat", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            body.set(request);
            String response;
            if (request.contains("Project evidence")) {
                String content = Json.stringify(Map.of("claims", List.of()));
                response = Json.stringify(Map.of("message", Map.of("content", content)));
            } else if (request.contains("\"format\":\"json\"")) {
                response = "{\"message\":{\"content\":\"{\\\"title\\\":\\\"Java plan\\\",\\\"steps\\\":[\\\"Read code\\\",\\\"Run tests\\\"]}\"}}";
            } else if (request.contains("\"List files\"") && request.contains("\"tool_calls\"")) {
                toolFollowupBody.set(request);
                response = "{\"message\":{\"role\":\"assistant\",\"content\":\"Файлы перечислены\"}}";
            } else if (request.contains("\"List files\"")) {
                response = "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
                    + "{\"function\":{\"name\":\"list_files\",\"arguments\":{\"path\":\".\",\"max_depth\":1},\"index\":0}}]}}";
            } else {
                response = "{\"message\":{\"role\":\"assistant\",\"content\":\"Готово\"}}";
            }
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            Path root = Files.createTempDirectory("kuleshato-ollama-");
            try {
                WorkspaceTools tools = new WorkspaceTools(root, command -> false, (path, diff) -> false);
                ModelClient client = new ModelClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "qwen3:8b", 4096, tools);
                List<Object> messages = new java.util.ArrayList<>(List.of(
                    Map.of("role", "system", "content", "Java assistant"),
                    Map.of("role", "user", "content", "Explain this Java class")
                ));
                check(client.chat(messages, false, new ModelClient.Events() {}) .equals("Готово"));
                check(body.get().contains("\"think\":true"));
                check(body.get().contains("\"num_ctx\":4096"));
                List<Object> toolMessages = new java.util.ArrayList<>(List.of(
                    Map.of("role", "system", "content", "Java assistant"),
                    Map.of("role", "user", "content", "List files")
                ));
                check(client.chat(toolMessages, false, new ModelClient.Events() {}).equals("Файлы перечислены"));
                check(toolFollowupBody.get().contains("\"index\":0"));
                check(!toolFollowupBody.get().contains("\"index\":0.0"));
                String projectResponse = client.answerProjectQuestion("evidence", "What does this project do?");
                check(projectResponse.equals("{\"claims\":[]}"));
                check(body.get().contains("E00012"));
                check(body.get().contains("\"think\":false"));
                check(body.get().contains("\"num_ctx\":4096"));
                check(body.get().contains("\"num_predict\":192"));
                client.answerProjectQuestion("evidence", "What does this project do?", "Retry with exact source lines.");
                check(body.get().contains("Correction request"));
                check(body.get().contains("Retry with exact source lines."));
                ModelClient largeContextClient = new ModelClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "qwen3:8b", 40960, tools);
                largeContextClient.answerProjectQuestion("evidence", "What does this project do?");
                check(body.get().contains("\"num_ctx\":40960"));
                Map<String, Object> plan = client.createPlan("Review Java service");
                check(plan.get("title").equals("Java plan"));
                check(((List<?>) plan.get("steps")).size() == 2);
            } finally { deleteTree(root); }
        } finally { server.stop(0); }
    }

    private static void javaBrief() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-brief-");
        try {
            Files.createDirectories(root.resolve("src/test/java/example"));
            Files.writeString(root.resolve("pom.xml"), "<project><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
            Files.writeString(root.resolve("src/test/java/example/AppTest.java"), "class AppTest {}");
            Files.writeString(root.resolve("test.ps1"), "Write-Output tests");
            String brief = JavaProjectBrief.create(root, ".");
            check(brief.contains("Java-проект"));
            check(brief.contains("mvn test"));
            check(brief.contains(".\\test.ps1"));
            check(brief.contains("Версия: 21"));
            check(brief.contains("AppTest.java"));
        } finally { deleteTree(root); }
    }

    private static ProjectFacts.Result facts(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return ProjectFacts.extract(root.toRealPath(), paths.filter(Files::isRegularFile)
                .map(path -> {
                    try { return path.toRealPath(); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                }).toList());
        }
    }

    private static ProjectFacts.Fact fact(ProjectFacts.Result result, ProjectFacts.Category category, String value) {
        return result.of(category).stream().filter(fact -> fact.value().equals(value)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + category + " fact: " + value + " in " + result.facts()));
    }

    private static void checkSource(ProjectFacts.Fact fact, ProjectFacts.Origin origin, String path, int line) {
        ProjectFacts.Source source = fact.source();
        if (source.origin() != origin || !source.path().equals(path) || source.line() != line) {
            throw new AssertionError("Unexpected provenance for " + fact.value() + ": " + source);
        }
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void mavenFacts() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-maven-facts-");
        try {
            write(root, "pom.xml", String.join("\n",
                "<?xml version=\"1.0\"?>",
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">",
                "  <groupId>org.example</groupId>",
                "  <artifactId>shop</artifactId>",
                "  <version>2.1.0</version>",
                "  <packaging>pom</packaging>",
                "  <properties>",
                "    <maven.compiler.release>17</maven.compiler.release>",
                "    <junit.version>5.10.2</junit.version>",
                "  </properties>",
                "  <modules>",
                "    <module>api</module>",
                "  </modules>",
                "  <dependencies>",
                "    <dependency>",
                "      <groupId>org.junit.jupiter</groupId>",
                "      <artifactId>junit-jupiter</artifactId>",
                "      <version>${junit.version}</version>",
                "      <scope>test</scope>",
                "    </dependency>",
                "  </dependencies>",
                "  <build><plugins>",
                "    <plugin>",
                "      <artifactId>maven-failsafe-plugin</artifactId>",
                "      <version>3.2.5</version>",
                "    </plugin>",
                "  </plugins></build>",
                "</project>"));
            write(root, "mvnw", "#!/bin/sh");
            write(root, "mvnw.cmd", "@echo off");
            write(root, ".mvn/wrapper/maven-wrapper.properties",
                "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip");
            write(root, "api/pom.xml", "<project><artifactId>api</artifactId>"
                + "<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
                + "<configuration><release>21</release></configuration></plugin></plugins></build></project>");
            write(root, "api/src/main/java/App.java", "class App {}");
            ProjectFacts.Result result = facts(root);
            checkSource(fact(result, ProjectFacts.Category.BUILD_SYSTEM, "Maven"), ProjectFacts.Origin.CONFIG, "pom.xml", 2);
            checkSource(fact(result, ProjectFacts.Category.ARTIFACT, "org.example:shop:2.1.0 (pom)"),
                ProjectFacts.Origin.CONFIG, "pom.xml", 4);
            ProjectFacts.Fact java = result.first(ProjectFacts.Category.JAVA_VERSION);
            check(java.value().equals("17"));
            checkSource(java, ProjectFacts.Origin.CONFIG, "pom.xml", 8);
            check(java.source().basis().contains("maven.compiler.release"));
            checkSource(fact(result, ProjectFacts.Category.JAVA_VERSION, "21"), ProjectFacts.Origin.CONFIG, "api/pom.xml", 1);
            checkSource(fact(result, ProjectFacts.Category.MODULE, "api"), ProjectFacts.Origin.CONFIG, "pom.xml", 12);
            ProjectFacts.Fact junit = fact(result, ProjectFacts.Category.DEPENDENCY, "org.junit.jupiter:junit-jupiter:5.10.2 [test]");
            checkSource(junit, ProjectFacts.Origin.CONFIG, "pom.xml", 15);
            check(junit.source().basis().contains("${junit.version}"));
            checkSource(fact(result, ProjectFacts.Category.TEST_FRAMEWORK, "JUnit 5 (Jupiter)"), ProjectFacts.Origin.CONFIG, "pom.xml", 15);
            checkSource(fact(result, ProjectFacts.Category.WRAPPER, "Maven Wrapper 3.9.6"),
                ProjectFacts.Origin.CONFIG, ".mvn/wrapper/maven-wrapper.properties", 1);
            String mvn = System.getProperty("os.name").toLowerCase().contains("win") ? ".\\mvnw.cmd" : "./mvnw";
            ProjectFacts.Fact test = fact(result, ProjectFacts.Category.TEST_COMMAND, "`" + mvn + " test`");
            checkSource(test, ProjectFacts.Origin.CONFIG, "pom.xml", 2);
            check(test.source().basis().contains("стандартная фаза Maven"));
            checkSource(fact(result, ProjectFacts.Category.TEST_COMMAND, "`" + mvn + " verify`"),
                ProjectFacts.Origin.CONFIG, "pom.xml", 23);
            check(result.of(ProjectFacts.Category.TEST_COMMAND).stream().noneMatch(fact -> fact.source().path().startsWith("api/")));
            for (ProjectFacts.Fact fact : result.facts()) check(!fact.sources().isEmpty() && fact.source().origin() != null);
            String brief = JavaProjectBrief.create(root, ".");
            check(brief.contains("- Версия: 17 (pom.xml:8 — конфигурация, свойство maven.compiler.release)"));
            check(brief.contains("разные версии Java: 17, 21"));
            check(brief.contains("org.junit.jupiter:junit-jupiter:5.10.2 [test] (pom.xml:15 — конфигурация, версия из ${junit.version})"));
        } finally { deleteTree(root); }
    }

    private static void gradleFacts() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-gradle-facts-");
        try {
            write(root, "settings.gradle.kts", "rootProject.name = \"billing\"\ninclude(\"core\", \":web\")\n");
            write(root, "build.gradle.kts", String.join("\n",
                "plugins {",
                "    java",
                "    application",
                "    id(\"org.springframework.boot\") version \"3.3.0\"",
                "}",
                "group = \"com.acme\"",
                "java {",
                "    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }",
                "}",
                "dependencies {",
                "    implementation(\"com.google.guava:guava:33.0.0-jre\") // utilities",
                "    testImplementation(libs.junit.jupiter)",
                "    testImplementation(\"org.junit.jupiter:junit-jupiter:5.10.2\")",
                "}",
                "application { mainClass.set(\"com.acme.App\") }",
                "tasks.test { useJUnitPlatform() }"));
            write(root, "gradlew", "#!/bin/sh");
            write(root, "gradle/wrapper/gradle-wrapper.properties",
                "# wrapper\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-bin.zip\n");
            write(root, "src/main/java/com/acme/App.java", "class App {}");
            ProjectFacts.Result result = facts(root);
            checkSource(fact(result, ProjectFacts.Category.BUILD_SYSTEM, "Gradle"), ProjectFacts.Origin.CONFIG, "build.gradle.kts", 0);
            checkSource(fact(result, ProjectFacts.Category.PLUGIN, "java"), ProjectFacts.Origin.CONFIG, "build.gradle.kts", 2);
            checkSource(fact(result, ProjectFacts.Category.PLUGIN, "application"), ProjectFacts.Origin.CONFIG, "build.gradle.kts", 3);
            checkSource(fact(result, ProjectFacts.Category.PLUGIN, "org.springframework.boot:3.3.0"),
                ProjectFacts.Origin.CONFIG, "build.gradle.kts", 4);
            check(result.of(ProjectFacts.Category.PLUGIN).stream().noneMatch(fact -> fact.value().equals("java") && fact.source().line() == 7));
            checkSource(fact(result, ProjectFacts.Category.ARTIFACT, "group = com.acme"), ProjectFacts.Origin.CONFIG, "build.gradle.kts", 6);
            checkSource(fact(result, ProjectFacts.Category.JAVA_VERSION, "21"), ProjectFacts.Origin.CONFIG, "build.gradle.kts", 8);
            checkSource(fact(result, ProjectFacts.Category.DEPENDENCY, "com.google.guava:guava:33.0.0-jre [implementation]"),
                ProjectFacts.Origin.CONFIG, "build.gradle.kts", 11);
            check(fact(result, ProjectFacts.Category.DEPENDENCY, "libs.junit.jupiter [testImplementation]")
                .source().basis().contains("не найден в gradle/libs.versions.toml"));
            checkSource(fact(result, ProjectFacts.Category.TEST_FRAMEWORK, "JUnit 5 (Jupiter)"),
                ProjectFacts.Origin.CONFIG, "build.gradle.kts", 13);
            checkSource(fact(result, ProjectFacts.Category.TEST_FRAMEWORK, "JUnit Platform"),
                ProjectFacts.Origin.CONFIG, "build.gradle.kts", 16);
            checkSource(fact(result, ProjectFacts.Category.MAIN_CLASS, "com.acme.App"), ProjectFacts.Origin.CONFIG, "build.gradle.kts", 15);
            checkSource(fact(result, ProjectFacts.Category.ARTIFACT, "rootProject.name = billing"),
                ProjectFacts.Origin.CONFIG, "settings.gradle.kts", 1);
            checkSource(fact(result, ProjectFacts.Category.MODULE, "core"), ProjectFacts.Origin.CONFIG, "settings.gradle.kts", 2);
            checkSource(fact(result, ProjectFacts.Category.MODULE, "web"), ProjectFacts.Origin.CONFIG, "settings.gradle.kts", 2);
            checkSource(fact(result, ProjectFacts.Category.WRAPPER, "Gradle Wrapper 8.7"),
                ProjectFacts.Origin.CONFIG, "gradle/wrapper/gradle-wrapper.properties", 2);
            ProjectFacts.Fact test = fact(result, ProjectFacts.Category.TEST_COMMAND, "`./gradlew test`");
            check(test.source().basis().contains("стандартная задача Gradle"));
        } finally { deleteTree(root); }
    }

    private static void ciFacts() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-ci-facts-");
        try {
            write(root, "pom.xml", "<project><artifactId>app</artifactId></project>");
            write(root, ".github/workflows/ci.yml", String.join("\n",
                "name: Build",
                "on:",
                "  push:",
                "    branches: [main]",
                "  pull_request:",
                "jobs:",
                "  test:",
                "    runs-on: ubuntu-latest",
                "    steps:",
                "      - uses: actions/checkout@v4",
                "      - uses: actions/setup-java@v4",
                "        with:",
                "          java-version: '21'",
                "      - run: mvn -B verify",
                "      - name: Package",
                "        run: |",
                "          mvn -B package",
                "          echo done"));
            write(root, ".gitlab-ci.yml", "test:\n  script:\n    - ./gradlew check\n");
            write(root, "Jenkinsfile", "pipeline { stages { stage('t') { steps { sh 'mvn test' } } } }");
            ProjectFacts.Result result = facts(root);
            checkSource(fact(result, ProjectFacts.Category.CI_WORKFLOW, "GitHub Actions: Build"),
                ProjectFacts.Origin.CONFIG, ".github/workflows/ci.yml", 1);
            checkSource(fact(result, ProjectFacts.Category.CI_TRIGGER, "GitHub Actions запускается на: push, pull_request"),
                ProjectFacts.Origin.CONFIG, ".github/workflows/ci.yml", 2);
            checkSource(fact(result, ProjectFacts.Category.CI_JAVA, "Установка JDK через actions/setup-java@v4"),
                ProjectFacts.Origin.CONFIG, ".github/workflows/ci.yml", 11);
            checkSource(fact(result, ProjectFacts.Category.CI_JAVA, "Java в CI: 21"),
                ProjectFacts.Origin.CONFIG, ".github/workflows/ci.yml", 13);
            checkSource(fact(result, ProjectFacts.Category.CI_COMMAND, "`mvn -B verify`"),
                ProjectFacts.Origin.CONFIG, ".github/workflows/ci.yml", 14);
            checkSource(fact(result, ProjectFacts.Category.CI_COMMAND, "`mvn -B package`"),
                ProjectFacts.Origin.CONFIG, ".github/workflows/ci.yml", 17);
            checkSource(fact(result, ProjectFacts.Category.CI_COMMAND, "`echo done`"),
                ProjectFacts.Origin.CONFIG, ".github/workflows/ci.yml", 18);
            ProjectFacts.Fact verify = fact(result, ProjectFacts.Category.TEST_COMMAND, "`mvn -B verify`");
            check(verify.source().basis().contains("GitHub Actions"));
            check(result.of(ProjectFacts.Category.TEST_COMMAND).stream().noneMatch(fact -> fact.value().contains("echo")));
            checkSource(fact(result, ProjectFacts.Category.CI_COMMAND, "`./gradlew check`"),
                ProjectFacts.Origin.CONFIG, ".gitlab-ci.yml", 3);
            checkSource(fact(result, ProjectFacts.Category.CI_WORKFLOW, "Jenkins"), ProjectFacts.Origin.CONFIG, "Jenkinsfile", 0);
            ProjectFacts.Fact mavenTest = fact(result, ProjectFacts.Category.TEST_COMMAND, "`mvn test`");
            check(mavenTest.sources().size() == 2);
            check(mavenTest.sources().get(1).path().equals("Jenkinsfile"));
            String brief = JavaProjectBrief.create(root, ".");
            check(brief.contains("GitHub Actions: Build (.github/workflows/ci.yml:1 — конфигурация)"));
            check(!brief.contains("CI-конфигурация не найдена"));
        } finally { deleteTree(root); }
    }

    private static void settingsFacts() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-settings-facts-");
        try {
            write(root, "build.gradle", "plugins { id 'java' }\nsourceCompatibility = '1.8'\n");
            write(root, "gradle.properties", "org.gradle.jvmargs=-Xmx2g\nartifactoryPassword=hunter2\n");
            write(root, "src/main/resources/application.properties",
                "# comment\nspring.datasource.url=jdbc:postgresql://db/prod\nspring.datasource.password=s3cr3t\n");
            write(root, "src/main/resources/application-dev.yml", "server:\n  port: 8081\nspring:\n  profiles: dev\n");
            write(root, ".sdkmanrc", "# sdkman\njava=21.0.2-tem\n");
            write(root, ".mvn/jvm.config", "-Xmx1g\n");
            write(root, "src/main/java/App.java", "class App {}");
            ProjectFacts.Result result = facts(root);
            checkSource(fact(result, ProjectFacts.Category.JAVA_VERSION, "1.8"), ProjectFacts.Origin.CONFIG, "build.gradle", 2);
            checkSource(fact(result, ProjectFacts.Category.SETTING, "`org.gradle.jvmargs=-Xmx2g`"),
                ProjectFacts.Origin.CONFIG, "gradle.properties", 1);
            checkSource(fact(result, ProjectFacts.Category.SETTING, "`artifactoryPassword` (значение скрыто)"),
                ProjectFacts.Origin.CONFIG, "gradle.properties", 2);
            ProjectFacts.Fact application = fact(result, ProjectFacts.Category.SETTING,
                "Ключи настроек приложения (2): spring.datasource.url, spring.datasource.password");
            checkSource(application, ProjectFacts.Origin.CONFIG, "src/main/resources/application.properties", 2);
            checkSource(fact(result, ProjectFacts.Category.SETTING, "Ключи настроек приложения (2): server, spring"),
                ProjectFacts.Origin.CONFIG, "src/main/resources/application-dev.yml", 1);
            checkSource(fact(result, ProjectFacts.Category.TOOLCHAIN, "JDK 21.0.2-tem"), ProjectFacts.Origin.CONFIG, ".sdkmanrc", 2);
            checkSource(fact(result, ProjectFacts.Category.SETTING, "`-Xmx1g`"), ProjectFacts.Origin.CONFIG, ".mvn/jvm.config", 1);
            String brief = JavaProjectBrief.create(root, ".");
            check(!brief.contains("hunter2"));
            check(!brief.contains("s3cr3t"));
            check(!brief.contains("jdbc:postgresql"));
            check(brief.contains("Версия: 1.8 (build.gradle:2 — конфигурация, sourceCompatibility)"));
        } finally { deleteTree(root); }
    }

    private static void documentationCommands() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-doc-commands-");
        try {
            write(root, "README.md", "# App\n\nRun tests:\n\n```shell\n$ ./mvnw -q verify\nmvn package\nmvn test\n```\n\nmvn deploy outside a code block\n");
            write(root, "pom.xml", "<project>\n<artifactId>app</artifactId>\n</project>");
            write(root, "test.ps1", "Write-Output tests");
            write(root, "src/main/java/App.java", "class App {}");
            ProjectFacts.Result result = facts(root);
            ProjectFacts.Fact documented = fact(result, ProjectFacts.Category.TEST_COMMAND, "`./mvnw -q verify`");
            checkSource(documented, ProjectFacts.Origin.DOCUMENTATION, "README.md", 6);
            checkSource(fact(result, ProjectFacts.Category.BUILD_COMMAND, "`mvn package`"), ProjectFacts.Origin.CONFIG, "pom.xml", 1);
            ProjectFacts.Fact mvnTest = fact(result, ProjectFacts.Category.TEST_COMMAND, "`mvn test`");
            check(mvnTest.sources().stream().anyMatch(source ->
                source.origin() == ProjectFacts.Origin.DOCUMENTATION && source.path().equals("README.md") && source.line() == 8));
            check(result.facts().stream().noneMatch(fact -> fact.value().contains("deploy")));
            ProjectFacts.Fact script = fact(result, ProjectFacts.Category.TEST_COMMAND, "`.\\test.ps1`");
            checkSource(script, ProjectFacts.Origin.FILESYSTEM, "test.ps1", 0);
            check(script.render().contains("test.ps1 — структура файлов, по имени скрипта"));
        } finally { deleteTree(root); }
    }

    private static void unsafeBuildFiles() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-unsafe-build-");
        Path secret = Files.createTempFile("kuleshato-secret-", ".txt");
        try {
            Files.writeString(secret, "TOP-SECRET-VALUE");
            write(root, "pom.xml", "<?xml version=\"1.0\"?>\n<!DOCTYPE project [<!ENTITY x SYSTEM \""
                + secret.toUri() + "\">]>\n<project><artifactId>&x;</artifactId></project>");
            write(root, "broken/pom.xml", "<project><artifactId>broken");
            write(root, "src/main/java/App.java", "class App {}");
            ProjectFacts.Result result = facts(root);
            check(result.facts().stream().noneMatch(fact -> fact.value().contains("TOP-SECRET")));
            check(result.of(ProjectFacts.Category.BUILD_SYSTEM).isEmpty());
            check(result.warnings().stream().anyMatch(warning -> warning.contains("pom.xml") && warning.contains("XML")));
            check(result.warnings().stream().anyMatch(warning -> warning.contains("broken/pom.xml")));
            String brief = JavaProjectBrief.create(root, ".");
            check(brief.contains("Сборка: не определена по манифестам"));
            check(!brief.contains("TOP-SECRET"));
        } finally {
            deleteTree(root);
            Files.deleteIfExists(secret);
        }
    }

    private static void multiLineTagProvenance() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-multiline-pom-");
        try {
            write(root, "pom.xml", String.join("\n",
                "<?xml version=\"1.0\"?>",
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"",
                "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
                "    xsi:schemaLocation=\"x y\">",
                "  <artifactId>app</artifactId>",
                "</project>"));
            ProjectFacts.Result result = facts(root);
            checkSource(fact(result, ProjectFacts.Category.BUILD_SYSTEM, "Maven"), ProjectFacts.Origin.CONFIG, "pom.xml", 2);
            checkSource(fact(result, ProjectFacts.Category.ARTIFACT, "?:app"), ProjectFacts.Origin.CONFIG, "pom.xml", 5);
        } finally { deleteTree(root); }
    }

    private static void technicalTokensRequireEvidence() throws Exception {
        check(ProjectInspection.technicalTokens("После сохранения заказ публикуется в Kafka.").equals(List.of("kafka")));
        check(ProjectInspection.technicalTokens("Метод Calc.add складывает числа.").equals(List.of("calc", "add")));
        check(ProjectInspection.technicalTokens("Клиент отправляет запрос на /api/chat.").equals(List.of("chat")));
        check(ProjectInspection.technicalTokens("Проект написан на Java и описан в README.").isEmpty());
        check(ProjectInspection.technicalTokens("The client sends a request.").isEmpty());
        check(ProjectInspection.technicalTokens("The client stores data in Redis.").equals(List.of("redis")));
        ProjectInspection.Inspection inspection = new ProjectInspection.Inspection("", List.of(
            new ProjectInspection.EvidenceFile("src/main/java/shop/OrderRepository.java",
                List.of("this.url = \"jdbc:postgresql://localhost/orders\";", "insert.executeUpdate();"), true, true)),
            List.of(), List.of(), true, false);
        String grounded = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(Map.of(
            "statement", "OrderRepository подключается к PostgreSQL через JDBC.", "evidence", List.of("E00001"))))), inspection);
        check(grounded.contains("Подтверждено по коду"));
        String invented = ProjectInspection.renderVerifiedAnswer(Json.stringify(Map.of("claims", List.of(Map.of(
            "statement", "Заказ публикуется в Kafka.", "evidence", List.of("E00002"))))), inspection);
        check(invented.contains("технология или число в тезисе отсутствует в цитате"));
        check(!invented.contains("Kafka"));
    }

    private static void referenceBenchmark() throws Exception {
        ProjectBenchmark.Report report = ProjectBenchmark.run(null);
        List<String> failures = report.thresholdFailures();
        if (!failures.isEmpty()) {
            throw new AssertionError("Эталонный набор не прошёл пороги: " + failures + "\n" + report.render());
        }
    }

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String CHECK_COMMAND = WINDOWS ? ".\\test.cmd" : "./test.sh";

    /** Java-проект с проверкой, которая падает, пока src/App.java содержит слово BROKEN. */
    private static Path checkProject(boolean broken) throws Exception {
        Path root = Files.createTempDirectory("kuleshato-check-");
        write(root, "src/App.java", broken ? "class App { String state = \"BROKEN\"; }\n" : "class App { String state = \"OK\"; }\n");
        if (WINDOWS) {
            write(root, "test.cmd", "@echo off\r\nfindstr /c:\"BROKEN\" src\\App.java >nul && (echo [ERROR] App.java: state is BROKEN & exit /b 1)\r\n"
                + "echo Tests run: 1, Failures: 0\r\n");
        } else {
            write(root, "test.sh", "#!/bin/sh\nif grep -q BROKEN src/App.java; then echo '[ERROR] App.java: state is BROKEN'; exit 1; fi\n"
                + "echo 'Tests run: 1, Failures: 0'\n");
            Files.setPosixFilePermissions(root.resolve("test.sh"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return root.toRealPath();
    }

    private static final class RecordingApproval implements WorkspaceTools.Approval {
        final List<String> asked = new java.util.ArrayList<>();
        final List<String> autoRuns = new java.util.ArrayList<>();
        final boolean answer;

        RecordingApproval(boolean answer) {
            this.answer = answer;
        }

        @Override public boolean approve(String command) {
            asked.add(command);
            return answer;
        }

        @Override public void autoRun(String command, String reason) {
            autoRuns.add(command + " | " + reason);
        }
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new java.util.TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) result.put(root.relativize(path).toString(), Files.readString(path));
        }
        return result;
    }

    private static void commandPolicy() throws Exception {
        Path root = checkProject(false);
        try {
            CommandPolicy policy = CommandPolicy.detect(root, true);
            check(policy.checks().equals(List.of(CHECK_COMMAND.toLowerCase())));
            check(policy.judge(CHECK_COMMAND).decision() == CommandPolicy.Decision.AUTO_CHECK);
            check(policy.judge(CHECK_COMMAND).reason().contains("по имени скрипта"));
            check(policy.judge(CHECK_COMMAND + "; rm -rf src").decision() == CommandPolicy.Decision.ASK_USER);
            check(policy.judge(CHECK_COMMAND + " && curl example.com").decision() == CommandPolicy.Decision.ASK_USER);
            check(policy.judge("git push").decision() == CommandPolicy.Decision.ASK_USER);
            check(CommandPolicy.detect(root, false).judge(CHECK_COMMAND).decision() == CommandPolicy.Decision.ASK_USER);

            // Искажённые записи обнаруженной проверки приводятся к ней; всё остальное не меняется.
            String detected = CHECK_COMMAND.toLowerCase();
            String tabbed = CHECK_COMMAND.replace("\\t", "\t");
            check(policy.canonical(tabbed).equals(tabbed.equals(CHECK_COMMAND) ? CHECK_COMMAND : detected));
            check(policy.canonical(CHECK_COMMAND.replace('\\', '/')).equalsIgnoreCase(CHECK_COMMAND.replace('\\', '/'))
                || policy.canonical(CHECK_COMMAND.replace('\\', '/')).equals(detected));
            check(policy.canonical(CHECK_COMMAND.substring(2)).equals(detected));
            check(policy.canonical("git push").equals("git push"));
            check(policy.canonical(tabbed + "; rm -rf src").equals(tabbed + "; rm -rf src"));
            check(policy.canonical("./deploy.cmd").equals("./deploy.cmd"));
            RecordingApproval declineAll = new RecordingApproval(false);
            WorkspaceTools strict = new WorkspaceTools(root, declineAll, (path, diff) -> false, policy);
            strict.startTurn();
            String ran = strict.execute("run_command", Map.of("command", CHECK_COMMAND.substring(2)));
            check(!ran.contains("not approved") && ran.contains("distorted spelling") && declineAll.asked.isEmpty());
            check(strict.report().commands.get(0).startsWith("`" + detected + "`"));
            write(root, "pom.xml", "<project><artifactId>app</artifactId></project>");
            write(root, "deploy.ps1", "Write-Output deploy");
            write(root, "build-and-release.ps1", "Write-Output release");
            write(root, ".github/workflows/ci.yml", "name: CI\non: push\njobs:\n  b:\n    steps:\n      - run: mvn test -Dskip=true\n");
            write(root, "README.md", "# App\n\n```\nmvn verify\n```\n");
            CommandPolicy maven = CommandPolicy.detect(root, true);
            check(maven.judge("mvn test").decision() == CommandPolicy.Decision.AUTO_CHECK);
            check(maven.judge("mvn  package").decision() == CommandPolicy.Decision.AUTO_CHECK);
            check(maven.judge("mvn deploy").decision() == CommandPolicy.Decision.ASK_USER);
            check(maven.judge("mvn verify").decision() == CommandPolicy.Decision.ASK_USER);
            check(maven.judge("mvn test -Dskip=true").decision() == CommandPolicy.Decision.ASK_USER);
            check(maven.checks().stream().noneMatch(command -> command.contains("deploy") || command.contains("release")));
            Path config = root.resolve(".kuleshato/config.json");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "{\"autoRunChecks\":false}");
            check(!ProjectConfig.load(root).autoRunChecks());
            Files.writeString(config, "{\"autoRunChecks\":\"yes\"}");
            boolean rejected = false;
            try { ProjectConfig.load(root); } catch (java.io.IOException expected) { rejected = expected.getMessage().contains("autoRunChecks"); }
            check(rejected);
        } finally { deleteTree(root); }
    }

    private static void declinedActionsHaveNoSideEffects() throws Exception {
        Path root = checkProject(true);
        try {
            Map<String, String> before = snapshot(root);
            RecordingApproval commands = new RecordingApproval(false);
            WorkspaceTools tools = new WorkspaceTools(root, commands, (path, diff) -> false, CommandPolicy.detect(root, true));
            tools.startTurn();
            String edit = tools.execute("edit_file", Map.of("path", "src/App.java", "old_text", "BROKEN", "new_text", "OK"));
            check(edit.contains("not approved by the user") && edit.contains("Do not retry"));
            String created = tools.execute("write_file", Map.of("path", "src/New.java", "content", "class New {}"));
            check(created.contains("not approved by the user"));
            String command = tools.execute("run_command", Map.of("command", "echo touched > touched.txt"));
            check(command.contains("not approved by the user"));
            check(commands.asked.equals(List.of("echo touched > touched.txt")));
            check(snapshot(root).equals(before));
            WorkspaceTools.TurnReport report = tools.report();
            check(report.changedFiles.isEmpty());
            check(report.declinedFiles.size() == 2);
            check(report.declinedCommands.equals(List.of("echo touched > touched.txt")));
            String rendered = report.render();
            check(rendered.contains("Изменённые файлы: нет"));
            check(rendered.contains("Изменения отклонены пользователем"));
            check(rendered.contains("Команды отклонены: echo touched > touched.txt"));
        } finally { deleteTree(root); }
    }

    private static void failedCheckCycles() throws Exception {
        Path root = checkProject(true);
        try {
            RecordingApproval commands = new RecordingApproval(false);
            WorkspaceTools tools = new WorkspaceTools(root, commands, (path, diff) -> true, CommandPolicy.detect(root, true));
            tools.startTurn();
            check(tools.execute("edit_file", Map.of("path", "src/App.java", "old_text", "class App", "new_text", "final class App"))
                .contains("Verify the change with one of the detected checks"));
            check(tools.report().render().contains("Проверки после изменений не запускались"));
            for (int attempt = 1; attempt <= WorkspaceTools.MAX_FAILED_CHECKS; attempt++) {
                String result = tools.execute("run_command", Map.of("command", CHECK_COMMAND));
                check(result.startsWith("Command failed (exit 1)"));
                check(result.contains("[ERROR] App.java: state is BROKEN"));
                check(result.contains("Failed verification cycle " + attempt + " of " + WorkspaceTools.MAX_FAILED_CHECKS));
            }
            check(commands.asked.isEmpty());
            check(commands.autoRuns.size() == WorkspaceTools.MAX_FAILED_CHECKS);
            String blocked = tools.execute("run_command", Map.of("command", CHECK_COMMAND));
            check(blocked.startsWith("Check was not run"));
            WorkspaceTools.TurnReport report = tools.report();
            check(report.checkLimitReached);
            check(report.commands.size() == WorkspaceTools.MAX_FAILED_CHECKS);
            String rendered = report.render();
            check(rendered.contains("Изменённые файлы: src/App.java"));
            check(rendered.contains("ошибка, код 1"));
            check(rendered.contains("Последняя проверка завершилась с ошибкой"));
            check(rendered.contains("лимит циклов исправления"));
            tools.startTurn();
            tools.execute("edit_file", Map.of("path", "src/App.java", "old_text", "BROKEN", "new_text", "OK"));
            check(!tools.execute("run_command", Map.of("command", CHECK_COMMAND)).startsWith("Command failed"));
            rendered = tools.report().render();
            check(rendered.contains("`" + CHECK_COMMAND + "` — успешно"));
            check(!rendered.contains("ошибкой") && !rendered.contains("не запускались"));
        } finally { deleteTree(root); }
    }

    private static void boundedModelOutput() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 3000; i++) output.append("[INFO] compiling module part ").append(i).append('\n');
        output.insert(output.length() / 2, "[ERROR] OrderService.java:[42,17] cannot find symbol\n");
        String bounded = WorkspaceTools.boundForModel(output.toString());
        check(bounded.length() <= WorkspaceTools.MAX_MODEL_OUTPUT_CHARS + 200);
        check(bounded.contains("[INFO] compiling module part 0"));
        check(bounded.contains("cannot find symbol"));
        check(bounded.contains("[INFO] compiling module part 2999"));
        check(bounded.contains("output shortened from"));
        check(WorkspaceTools.boundForModel("short").equals("short"));
    }

    private static void agentFixLoop() throws Exception {
        Path root = checkProject(true);
        List<Map<String, Object>> replies = List.of(
            ScriptedOllama.toolCall("read_file", Map.of("path", "src/App.java")),
            ScriptedOllama.toolCall("run_command", Map.of("command", CHECK_COMMAND)),
            ScriptedOllama.toolCall("edit_file", Map.of("path", "src/App.java", "old_text", "BROKEN", "new_text", "OK")),
            ScriptedOllama.toolCall("run_command", Map.of("command", CHECK_COMMAND)),
            Map.of("role", "assistant", "content", "Исправил src/App.java, проверка проходит."));
        try (ScriptedOllama ollama = new ScriptedOllama(replies)) {
            RecordingApproval commands = new RecordingApproval(false);
            List<String> diffs = new java.util.ArrayList<>();
            WorkspaceTools tools = new WorkspaceTools(root, commands, (path, diff) -> diffs.add(diff), CommandPolicy.detect(root, true));
            tools.startTurn();
            ModelClient client = new ModelClient(ollama.url(), "qwen3:8b", 8192, tools);
            List<Object> messages = new java.util.ArrayList<>(List.of(
                Map.of("role", "system", "content", Main.changeWorkflow(tools.policy())),
                Map.of("role", "user", "content", "Почини проверку")));
            String answer = client.chat(messages, false, new ModelClient.Events() {});
            check(answer.contains("проверка проходит"));
            check(Files.readString(root.resolve("src/App.java")).contains("\"OK\""));
            check(diffs.size() == 1 && diffs.get(0).contains("-class App { String state = \"BROKEN\"; }"));
            check(commands.asked.isEmpty() && commands.autoRuns.size() == 2);
            check(ollama.requests.get(0).contains(CHECK_COMMAND.toLowerCase().replace("\\", "\\\\")));
            check(ollama.requests.get(2).contains("Failed verification cycle 1 of 3"));
            check(ollama.requests.get(2).contains("state is BROKEN"));
            check(ollama.requests.get(4).contains("Tests run: 1, Failures: 0"));
            String report = tools.report().render();
            check(report.contains("Изменённые файлы: src/App.java"));
            check(report.contains("ошибка, код 1"));
            check(report.contains("`" + CHECK_COMMAND + "` — успешно"));
            check(!report.contains("Последняя проверка завершилась с ошибкой"));
        } finally { deleteTree(root); }
    }

    private static void agentDeclinedChange() throws Exception {
        Path root = checkProject(true);
        List<Map<String, Object>> replies = List.of(
            ScriptedOllama.toolCall("edit_file", Map.of("path", "src/App.java", "old_text", "BROKEN", "new_text", "OK")),
            Map.of("role", "assistant", "content", "Готово, всё исправлено."));
        try (ScriptedOllama ollama = new ScriptedOllama(replies)) {
            Map<String, String> before = snapshot(root);
            WorkspaceTools tools = new WorkspaceTools(root, new RecordingApproval(false), (path, diff) -> false,
                CommandPolicy.detect(root, true));
            tools.startTurn();
            ModelClient client = new ModelClient(ollama.url(), "qwen3:8b", 8192, tools);
            List<Object> messages = new java.util.ArrayList<>(List.of(
                Map.of("role", "system", "content", "Java assistant"), Map.of("role", "user", "content", "Почини App")));
            String answer = client.chat(messages, false, new ModelClient.Events() {});
            check(answer.contains("Готово"));
            check(ollama.requests.get(1).contains("not approved by the user"));
            check(snapshot(root).equals(before));
            String report = tools.report().render();
            check(report.contains("Изменённые файлы: нет"));
            check(report.contains("Изменения отклонены пользователем: src"));
        } finally { deleteTree(root); }
    }

    private static void projectOverview() throws Exception {
        check(ProjectOverview.isOverviewRequest("Изучи проект и расскажи мне о нем"));
        check(ProjectOverview.isOverviewRequest("Расскажи о приложении"));
        check(ProjectOverview.isOverviewRequest("Что это за проект?"));
        check(ProjectOverview.isOverviewRequest("Tell me about this project"));
        check(!ProjectOverview.isOverviewRequest("Расскажи, как работает ShopUser"));
        check(!ProjectOverview.isOverviewRequest("Почему падает тест?"));
        check(ProjectInspection.isProjectOverviewQuestion("Изучи проект и расскажи мне о нем"));

        Path root = Files.createTempDirectory("kuleshato-overview-");
        try {
            write(root, "pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>shop</artifactId>"
                + "<version>1</version></project>");
            write(root, "README.md", "# Shop\n\nShop — это приложение для покупок\n\n## Особенности\n\n- Регистрация\n- Отзывы\n\n## Требования\n\n- Java 21\n");
            write(root, "src/main/java/app/Main.java", String.join("\n",
                "package app;", "import java.sql.Connection;", "import java.util.Scanner;",
                "public class Main {",
                "    private static final String SQL = \"\"\"",
                "    INSERT INTO users (name) VALUES (?)",
                "    \"\"\";",
                "    public static void main(String[] args) {",
                "        new Scanner(System.in);",
                "    }",
                "    void register(String name) throws java.sql.SQLException {",
                "    throw new IllegalStateException(\"x\");",
                "    }",
                "}", ""));
            write(root, "src/main/java/app/Store.java", "package app;\npublic interface Store {\n    void save(String value);\n}\n");
            write(root, "src/test/java/app/MainTest.java", "package app;\nclass MainTest {\n    void hidden() {}\n}\n");

            String features = ProjectOverview.readmeFeatures(root);
            check(features.contains("README.md:7-8") && features.contains("- Регистрация") && !features.contains("Java 21"));
            String map = ProjectOverview.codeMap(root, 4000);
            check(map.contains("Точка входа: app.Main (src/main/java/app/Main.java:8)"));
            check(map.contains("JDBC") && map.contains("консольный ввод") && map.contains("Пакетов: 1, классов: 2."));
            check(map.contains("Main — main, register") && map.contains("Store [интерфейс] — save"));
            check(!map.contains("users") && !map.contains("IllegalStateException") && !map.contains("MainTest"));

            ProjectInspection.Inspection inspection = ProjectInspection.collect(root, "расскажи о проекте", 6000);
            ProjectAnswering.Answer answer = ProjectAnswering.answer("расскажи о проекте", inspection, "m", "u",
                (context, question, correction) -> { throw new AssertionError("модель не нужна"); }, root);
            check(answer.route() == ProjectAnswering.Route.OVERVIEW && answer.modelCalls() == 0);
            check(answer.text().contains("README.md:3") && answer.text().contains("Регистрация") && answer.text().contains("Store"));
        } finally { deleteTree(root); }
    }

    private static void toolRecoveryHints() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-recovery-");
        try {
            write(root, "src/StringUtils.java", "public final class StringUtils {\r\n    static boolean isBlank(String value) {\r\n"
                + "        return value.trim().isEmpty();\r\n    }\r\n}\r\n");
            write(root, "test/StringUtilsTest.java", "class StringUtilsTest {}\n");
            WorkspaceTools tools = new WorkspaceTools(root, command -> false, (path, diff) -> true);

            // 1. Несуществующий каталог: видно, что есть рядом, и как искать по всему проекту.
            String missing = failure(() -> tools.execute("search_files", Map.of("query", "StringUtilsTest", "path", "src/test/java")));
            check(missing.contains("src/test/java не существует") && missing.contains("в каталоге src")
                && missing.contains("StringUtils.java") && missing.contains("search_files без path"));
            String missingRoot = failure(() -> tools.execute("read_file", Map.of("path", "lib/Missing.java")));
            check(missingRoot.contains("в корне проекта") && missingRoot.contains("src/") && missingRoot.contains("test/"));

            // 2. Поиск по одному файлу и по имени файла.
            String inFile = tools.execute("search_files", Map.of("query", "trim", "path", "src/StringUtils.java"));
            check(inFile.equals("src" + java.io.File.separator + "StringUtils.java:3:         return value.trim().isEmpty();"));
            String byName = tools.execute("search_files", Map.of("query", "StringUtilsTest.java"));
            check(byName.contains("StringUtilsTest.java: (имя файла совпадает с запросом)"));

            // 4. Промах edit_file: \n вместо \r\n и другие отступы принимаются, если совпадение единственное.
            check(tools.execute("edit_file", Map.of("path", "src/StringUtils.java",
                "old_text", "static boolean isBlank(String value) {\n  return value.trim().isEmpty();",
                "new_text", "static boolean isBlank(String value) {\n        return value == null || value.trim().isEmpty();"))
                .startsWith("Wrote"));
            String edited = Files.readString(root.resolve("src/StringUtils.java"));
            check(edited.contains("    static boolean isBlank(String value) {\r\n        return value == null || value.trim().isEmpty();\r\n    }"));
            check(!edited.replace("\r\n", "").contains("\n"));
            String miss = failure(() -> tools.execute("edit_file", Map.of("path", "src/StringUtils.java",
                "old_text", "return value.isBlank();", "new_text", "return false;")));
            check(miss.contains("Most similar lines") && miss.contains("3:         return value == null")
                && miss.contains("start_line=1, max_lines=30"));
            check(WorkspaceTools.looseMatch("a\n  x\nb\nx\n", "x") == null);
        } finally { deleteTree(root); }
    }

    private interface ToolCall { String run() throws Exception; }

    private static String failure(ToolCall call) {
        try {
            call.run();
        } catch (Exception expected) {
            return expected.getMessage();
        }
        throw new AssertionError("ожидалась ошибка инструмента");
    }

    private static void keyholeReads() throws Exception {
        Path root = Files.createTempDirectory("kuleshato-keyhole-");
        try {
            write(root, "src/Small.java", "class Small {\n    int x;\n}\n");
            StringBuilder big = new StringBuilder();
            for (int i = 1; i <= 450; i++) big.append("    int field").append(i).append(" = ").append(i).append(";\n");
            write(root, "src/Big.java", big.toString());
            StringBuilder wide = new StringBuilder();
            for (int i = 1; i <= 300; i++) wide.append("// ").append("x".repeat(97)).append('\n');
            write(root, "src/Wide.java", wide.toString());
            WorkspaceTools tools = new WorkspaceTools(root, command -> false, (path, diff) -> true);

            check(tools.execute("read_file", Map.of("path", "src/Small.java")).equals("class Small {\n    int x;\n}\n"));

            String first = tools.execute("read_file", Map.of("path", "src/Big.java"));
            check(first.startsWith("[Строки 1–200 из 450 файла src/Big.java."));
            check(first.contains("read_file(path=\"src/Big.java\", start_line=201)"));
            check(first.contains("\n    int field1 = 1;\n") && first.contains("    int field200 = 200;\n"));
            check(!first.contains("field201 "));

            String tail = tools.execute("read_file", Map.of("path", "src/Big.java", "start_line", 449, "max_lines", 5));
            check(tail.startsWith("[Строки 449–450 из 450"));
            check(!tail.contains("Следующий фрагмент"));
            check(tail.endsWith("    int field449 = 449;\n    int field450 = 450;\n"));

            String capped = tools.execute("read_file", Map.of("path", "src/Big.java", "max_lines", 1000));
            check(capped.startsWith("[Строки 1–" + WorkspaceTools.MAX_READ_LINES + " из 450"));

            String charCapped = tools.execute("read_file", Map.of("path", "src/Wide.java", "max_lines", 400));
            check(charCapped.length() < WorkspaceTools.MAX_READ_CHARS + 300);
            check(charCapped.startsWith("[Строки 1–118 из 300")); // 118 строк по 101 символу помещаются в 12 000

            boolean beyond = false;
            try { tools.execute("read_file", Map.of("path", "src/Big.java", "start_line", 451)); }
            catch (java.io.IOException expected) { beyond = expected.getMessage().contains("больше числа строк"); }
            check(beyond);

            String fragmentLine = tail.lines().filter(line -> line.contains("field450")).findFirst().orElseThrow();
            check(tools.execute("edit_file", Map.of("path", "src/Big.java", "old_text", fragmentLine,
                "new_text", "    int field450 = 451;")).startsWith("Wrote"));
            check(Files.readString(root.resolve("src/Big.java")).contains("int field450 = 451;"));

            WorkspaceTools legacy = new WorkspaceTools(root, command -> false, (path, diff) -> true).withReadWindow(0);
            check(legacy.execute("read_file", Map.of("path", "src/Big.java")).equals(Files.readString(root.resolve("src/Big.java"))));

            CommandPolicy policy = CommandPolicy.detect(root, true);
            check(!Main.changeWorkflow(policy).contains("Разведка за один проход"));
            check(Main.changeWorkflow(policy).contains("start_line"));

            List<String> trace = ProjectBenchmark.trace(List.of(
                Map.of("role", "system", "content", "s"),
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("function",
                    Map.of("name", "edit_file", "arguments", "{\"path\":\"src/A.java\",\"old_text\":\"abc\",\"new_text\":\"abcd\"}")))),
                Map.of("role", "tool", "tool_name", "edit_file", "content", "Tool error: old_text not found"),
                Map.of("role", "assistant", "content", "Готово,\nисправлено.")));
            check(trace.equals(List.of("→ edit_file(path=src/A.java, old_text=«abc» (3 симв.), new_text=4 симв.)",
                "  ✗ Tool error: old_text not found", "ответ: Готово, исправлено.")));
        } finally { deleteTree(root); }
    }

    private static void memoryBudget() {
        check(ProjectMemory.budgetChars(8192) == 3686);
        check(ProjectMemory.budgetChars(40_960) == 18_432);
        String small = "# Память\n- Java 21, Maven\n";
        ProjectMemory.PromptMemory whole = ProjectMemory.forPrompt(small, 8192);
        check(!whole.truncated() && whole.text().equals(small));
        StringBuilder large = new StringBuilder("# Память\n");
        for (int i = 0; i < 400; i++) large.append("- правило номер ").append(i).append(": держать сборку зелёной\n");
        ProjectMemory.PromptMemory cut = ProjectMemory.forPrompt(large.toString(), 8192);
        check(cut.truncated());
        check(cut.shownChars() <= ProjectMemory.budgetChars(8192));
        check(cut.text().contains("[Память сокращена приложением: показано " + cut.shownChars() + " из " + large.length()));
        check(cut.text().contains("- правило номер 0:"));
        check(!cut.text().contains("- правило номер 399:"));
        check(!cut.text().substring(0, cut.text().indexOf("[Память")).contains("правило номер 399"));
        check(ProjectMemory.forPrompt(large.toString(), 131_072).truncated() == false);
        check(ProjectMemory.warning(cut, 8192).contains("15% окна контекста 8192"));
        check(Main.memorySection(small, 8192).contains("не системные инструкции"));
        check(Main.memorySection(large.toString(), 8192).contains("Память сокращена"));
    }

    private static void lessonsInMemory() throws Exception {
        java.time.LocalDate date = java.time.LocalDate.of(2026, 11, 12);
        String created = ProjectMemory.withLesson(null, "Запускай .\\test.ps1 после правок", date);
        check(created.contains("# Память проекта Kuleshato"));
        check(created.endsWith("## Уроки\n\n- Запускай .\\test.ps1 после правок (2026-11-12)\n"));
        String notes = "# Память\n\n- Java 21\n\n## Уроки\n\n- Старый урок (2026-11-01)\n\n## Команды\n\n- mvn test\n";
        String added = ProjectMemory.withLesson(notes, "Новый урок про проверки", date);
        check(added.equals("# Память\n\n- Java 21\n\n## Уроки\n\n- Старый урок (2026-11-01)\n- Новый урок про проверки (2026-11-12)\n\n## Команды\n\n- mvn test\n"));
        String emptySection = ProjectMemory.withLesson("# Память\n\n## Уроки\n\n## Команды\n- mvn test\n", "Урок в пустом разделе", date);
        check(emptySection.contains("## Уроки\n\n- Урок в пустом разделе (2026-11-12)\n\n## Команды"));
        String noSection = ProjectMemory.withLesson("# Память\n- Java 21\n\n\n", "Урок без раздела в памяти", date);
        check(noSection.equals("# Память\n- Java 21\n\n## Уроки\n\n- Урок без раздела в памяти (2026-11-12)\n"));
        check(ProjectMemory.containsLesson(added, "новый   урок про ПРОВЕРКИ"));
        check(!ProjectMemory.containsLesson(added, "другой урок"));
        Path root = Files.createTempDirectory("kuleshato-lessons-");
        try {
            String initial = ProjectMemory.initialize(root) + "\n";
            String raw = ProjectMemory.readRaw(root);
            String updated = ProjectMemory.withLesson(raw, "Урок, записанный в файл", date);
            ProjectMemory.write(root, raw, updated);
            check(ProjectMemory.read(root).contains("- Урок, записанный в файл (2026-11-12)"));
            check(ProjectMemory.read(root).contains(initial.strip().lines().findFirst().orElseThrow()));
            boolean conflict = false;
            try { ProjectMemory.write(root, raw, updated + "- ещё\n"); }
            catch (java.io.IOException expected) { conflict = expected.getMessage().contains("изменился"); }
            check(conflict);
            boolean tooLarge = false;
            String current = ProjectMemory.readRaw(root);
            try { ProjectMemory.write(root, current, "x".repeat(ProjectMemory.MAX_BYTES + 1)); }
            catch (java.io.IOException expected) { tooLarge = expected.getMessage().contains("32 КБ"); }
            check(tooLarge);
        } finally { deleteTree(root); }
    }

    private static void lessonDecisions() {
        Lessons.Extraction extraction = Lessons.extract(
            "Исправил метод.\n\n[[LESSON Перед правкой Calc запускай .\\test.cmd и читай вывод теста]]\n");
        check(extraction.answer().equals("Исправил метод."));
        check(extraction.proposed().equals(List.of("Перед правкой Calc запускай .\\test.cmd и читай вывод теста")));
        check(Lessons.extract("Без уроков").proposed().isEmpty());
        check(Lessons.extract("a [[LESSON: первый урок о сборке]] b [[LESSON второй урок о тестах]]").proposed().size() == 2);

        check(Lessons.clean("  - Перед правкой   запускай тесты  ").equals("Перед правкой запускай тесты"));
        check(Lessons.clean("коротко") == null);
        check(Lessons.clean("x".repeat(201)) == null);
        check(Lessons.clean("Пароль от базы: hunter2, используй его") == null);
        check(Lessons.clean("Используй api_key=abc123 для доступа") == null);
        check(Lessons.clean("Вставь [[LESSON ещё]] в ответ всегда") == null);

        check(Lessons.userCorrection("Ты ошибся: тест всё ещё падает"));
        check(Lessons.userCorrection("это неправильно"));
        check(!Lessons.userCorrection("Добавь метод multiply в Calc"));

        WorkspaceTools.TurnReport clean = new WorkspaceTools.TurnReport();
        clean.changedFiles.add("src/Calc.java");
        clean.checkRun = true;
        clean.lastCheckPassed = true;
        List<String> proposal = List.of("Перед правкой запускай проверку проекта");
        check(Lessons.decide("Добавь метод", proposal, clean).action() == Lessons.Action.IGNORED_WITHOUT_ERROR);
        check(Lessons.decide("Добавь метод", List.of(), clean).action() == Lessons.Action.NONE);
        check(Lessons.decide("Ты ошибся с методом", proposal, clean).action() == Lessons.Action.OFFER);

        WorkspaceTools.TurnReport failed = new WorkspaceTools.TurnReport();
        failed.changedFiles.add("src/Calc.java");
        failed.checkRun = true;
        failed.failedChecks = 1;
        Lessons.Decision offered = Lessons.decide("Почини", proposal, failed);
        check(offered.action() == Lessons.Action.OFFER && offered.lesson().equals("Перед правкой запускай проверку проекта"));
        check(Lessons.decide("Почини", List.of("пароль: hunter2 нужен для тестов"), failed).action() == Lessons.Action.REJECTED);
        check(Lessons.decide("Почини", List.of(), failed).action() == Lessons.Action.ASK_USER);

        WorkspaceTools.TurnReport recovered = new WorkspaceTools.TurnReport();
        recovered.failedChecks = 1;
        recovered.checkRun = true;
        recovered.lastCheckPassed = true;
        check(Lessons.decide("Почини", proposal, recovered).action() == Lessons.Action.OFFER);
        check(Lessons.decide("Почини", List.of(), recovered).action() == Lessons.Action.NONE);

        WorkspaceTools.TurnReport declined = new WorkspaceTools.TurnReport();
        declined.declinedFiles.add("src/Calc.java");
        check(Lessons.decide("Почини", proposal, declined).action() == Lessons.Action.OFFER);
        check(Lessons.decide("Почини", List.of(), declined).action() == Lessons.Action.NONE);
        check(Lessons.decide("Что делает Calc?", proposal, null).action() == Lessons.Action.IGNORED_WITHOUT_ERROR);
    }

    private static void benchmarkComparison() throws Exception {
        Path file = Files.createTempFile("kuleshato-live-", ".jsonl");
        try {
            String first = Json.stringify(Map.of("date", "2026-11-01T10:00:00Z", "model", "qwen3:8b", "backend", "ollama",
                "hardware", "RTX 4070", "contextWindow", 12288, "os", "Windows 11 amd64", "cpus", 16, "questions", List.of(
                    Map.of("project", "a", "prompt", "q", "seconds", 2.0, "accepted", 1, "violations", 0, "fallback", false,
                        "tokensPerSecond", 40.0),
                    Map.of("task", "t1", "solved", true, "failedChecks", 1, "toolErrors", 0, "seconds", 30.0, "tokensPerSecond", 42.0,
                        "promptTokens", 9000, "modelTurns", 4),
                    Map.of("task", "t1 +урок", "solved", true, "failedChecks", 0, "toolErrors", 0, "seconds", 20.0),
                    Map.of("review", "r1", "knownFound", 2, "knownTotal", 3, "otherDefects", 1, "seconds", 9.0))));
            String second = Json.stringify(Map.of("date", "2026-11-02T10:00:00Z", "model", "qwen3:8b", "backend", "ollama",
                "hardware", "RTX 4070", "contextWindow", 12288, "os", "Windows 11 amd64", "cpus", 16, "questions", List.of(
                    Map.of("project", "a", "prompt", "q", "seconds", 4.0, "accepted", 0, "violations", 1, "fallback", true),
                    Map.of("task", "t1", "solved", false, "failedChecks", 3, "toolErrors", 2, "seconds", 50.0, "promptTokens", 12002, "modelTurns", 7))));
            String legacy = Json.stringify(Map.of("date", "2026-10-01T10:00:00Z", "model", "old:1b", "os", "Linux amd64", "cpus", 8,
                "questions", List.of(Map.of("project", "a", "prompt", "q", "seconds", 1.0, "accepted", 1, "violations", 0, "fallback", false))));
            Files.writeString(file, first + "\n" + second + "\n" + legacy + "\n");
            String table = ProjectBenchmark.compare(file);
            String qwen = table.lines().filter(line -> line.startsWith("| qwen3:8b")).findFirst().orElseThrow();
            check(qwen.contains("| ollama | RTX 4070 | 12288 | Windows 11 amd64, 16 CPU | 2 | 2026-11-02 |"));
            check(qwen.contains("| 3 | 1/2 | 0.5 | 1 | 1/2 | 2 | 2 | 40 | 2/3 | 1 | 41 |"));
            check(qwen.endsWith("| 41 | 1/1, 0 | 10501 | 5.5 |"));
            String old = table.lines().filter(line -> line.startsWith("| old:1b")).findFirst().orElseThrow();
            check(old.contains("| ollama | не указано | — |"));
            check(old.contains("| — | — | — | — |"));
        } finally { Files.deleteIfExists(file); }
    }

    /** Адаптер в памяти: доказывает, что цикл инструментов и проверки не зависят от HTTP-протокола Ollama. */
    private static final class FakeBackend implements ModelBackend {
        final List<Request> requests = new java.util.ArrayList<>();
        final java.util.ArrayDeque<Map<String, Object>> replies;

        FakeBackend(List<Map<String, Object>> replies) {
            this.replies = new java.util.ArrayDeque<>(replies);
        }

        @Override public String name() { return "fake"; }
        @Override public String model() { return "fake-model"; }
        @Override public Map<String, Object> complete(Request request) {
            requests.add(request);
            return replies.size() > 1 ? replies.poll() : replies.peek();
        }
    }

    /** Напоминание об невнесённом исправлении: ровно один раз и только когда оно уместно. Файл src/App.java уже исправлен. */
    private static void changeNudge(Path root) throws Exception {
        Files.writeString(root.resolve("src/App.java"), Files.readString(root.resolve("src/App.java")).replace("OK", "BROKEN"));
        CommandPolicy policy = CommandPolicy.detect(root, true);
        String prompt = "Проверка падает, исправь ошибку";

        // Модель отвечает планом без правки → одно напоминание → правка и проверка.
        FakeBackend planner = new FakeBackend(List.of(
            ScriptedOllama.answer("План: заменить BROKEN на OK."),
            ScriptedOllama.toolCall("edit_file", Map.of("path", "src/App.java", "old_text", "BROKEN", "new_text", "OK")),
            ScriptedOllama.toolCall("run_command", Map.of("command", CHECK_COMMAND)),
            ScriptedOllama.answer("Исправлено.")));
        WorkspaceTools tools = new WorkspaceTools(root, new RecordingApproval(false), (path, diff) -> true, policy);
        tools.startTurn();
        List<Object> messages = new java.util.ArrayList<>(List.of(
            Map.of("role", "system", "content", "Java assistant"), Map.of("role", "user", "content", prompt)));
        String answer = new ModelClient(planner, 8192, tools).chat(messages, false, new ModelClient.Events() {},
            reply -> ChangeNudge.message(prompt, tools.report(), policy));
        check(answer.equals("Исправлено.") && tools.report().lastCheckPassed);
        check(messages.stream().filter(message -> message instanceof Map<?, ?> map && String.valueOf(map.get("content"))
            .startsWith(ChangeNudge.PREFIX)).count() == 1);

        // Модель упорно отвечает текстом: напоминание только одно, затем ход заканчивается.
        FakeBackend stubborn = new FakeBackend(List.of(ScriptedOllama.answer("Нужны подробности.")));
        WorkspaceTools idle = new WorkspaceTools(root, new RecordingApproval(false), (path, diff) -> true, policy);
        idle.startTurn();
        new ModelClient(stubborn, 8192, idle).chat(new java.util.ArrayList<>(messages.subList(0, 2)), false,
            new ModelClient.Events() {}, reply -> ChangeNudge.message(prompt, idle.report(), policy));
        check(stubborn.requests.size() == 2);

        // Не напоминаем: вопрос, а не просьба изменить; отклонённая правка; прошедшая проверка.
        check(ChangeNudge.message("Что делает класс App?", idle.report(), policy) == null);
        WorkspaceTools declined = new WorkspaceTools(root, new RecordingApproval(false), (path, diff) -> false, policy);
        declined.startTurn();
        check(declined.execute("edit_file", Map.of("path", "src/App.java", "old_text", "OK", "new_text", "X")).contains("not approved"));
        check(ChangeNudge.message(prompt, declined.report(), policy) == null);
        WorkspaceTools passed = new WorkspaceTools(root, new RecordingApproval(false), (path, diff) -> true, policy);
        passed.startTurn();
        passed.execute("run_command", Map.of("command", CHECK_COMMAND));
        check(passed.report().lastCheckPassed && ChangeNudge.message(prompt, passed.report(), policy) == null);
        check(ChangeNudge.message(prompt, idle.report(), policy).contains(policy.checks().get(0)));

        // Пропущенный new_text объясняется, а промах old_text называет отсутствующую строку.
        String missingNew = failure(() -> idle.execute("edit_file", Map.of("path", "src/App.java", "old_text", "OK")));
        check(missingNew.contains("new_text") && missingNew.contains("empty string"));
        String absent = failure(() -> idle.execute("edit_file", Map.of("path", "src/App.java", "old_text", "NOPE_LINE", "new_text", "x")));
        check(absent.contains("This old_text line is not in the file: «NOPE_LINE»"));
    }

    private static void backendIndependence() throws Exception {
        Path root = checkProject(true);
        try {
            FakeBackend backend = new FakeBackend(List.of(
                ScriptedOllama.toolCall("edit_file", Map.of("path", "src/App.java", "old_text", "BROKEN", "new_text", "OK")),
                ScriptedOllama.toolCall("run_command", Map.of("command", CHECK_COMMAND)),
                ScriptedOllama.answer("Исправлено.")));
            WorkspaceTools tools = new WorkspaceTools(root, new RecordingApproval(false), (path, diff) -> true, CommandPolicy.detect(root, true));
            tools.startTurn();
            ModelClient client = new ModelClient(backend, 8192, tools);
            List<Object> messages = new java.util.ArrayList<>(List.of(
                Map.of("role", "system", "content", "Java assistant"), Map.of("role", "user", "content", "Почини")));
            check(client.chat(messages, false, new ModelClient.Events() {}).equals("Исправлено."));
            check(backend.requests.size() == 3);
            check(backend.requests.get(0).tools() != null && backend.requests.get(0).think() && !backend.requests.get(0).json());
            check(tools.report().lastCheckPassed && tools.report().changedFiles.equals(List.of("src/App.java")));
            changeNudge(root);

            FakeBackend reviewer = new FakeBackend(List.of(ScriptedOllama.answer("{\"findings\":[]}")));
            String review = new ModelClient(reviewer, 40_960, tools).reviewCode("code", "Review App");
            check(review.equals("{\"findings\":[]}"));
            ModelBackend.Request reviewRequest = reviewer.requests.get(0);
            check(reviewRequest.json() && !reviewRequest.think() && reviewRequest.tools() == null);
            check(reviewRequest.contextTokens() == 40_960);

            check(ModelBackend.create("ollama", "http://127.0.0.1:11434", "qwen3:8b").name().equals("ollama"));
            boolean unsupported = false;
            try { ModelBackend.create("openai", "http://127.0.0.1:1", "x"); }
            catch (java.io.IOException expected) { unsupported = expected.getMessage().contains("не поддерживается"); }
            check(unsupported);
            Path config = root.resolve(".kuleshato/config.json");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "{\"backend\":\"ollama\"}");
            check(ProjectConfig.load(root).backend().equals("ollama"));
            Files.writeString(config, "{\"backend\":\"remote-api\"}");
            boolean rejected = false;
            try { ProjectConfig.load(root); } catch (java.io.IOException expected) { rejected = expected.getMessage().contains("remote-api"); }
            check(rejected);
        } finally { deleteTree(root); }
    }

    private static void reviewMethodSpans() throws Exception {
        String[] lines = String.join("\n",
            "interface Store {",                                   // 1
            "    void save(String value);",                        // 2
            "}",                                                   // 3
            "class Repo implements Store {",                       // 4
            "    /** Saves one value. */",                         // 5
            "    @Override",                                       // 6
            "    public void save(String value) {",                // 7
            "        String text = \"} not a brace {\";",          // 8
            "        if (value != null) { log(text); }",           // 9
            "    }",                                               // 10
            "    void save(String value, int times) {",            // 11
            "        for (int i = 0; i < times; i++) save(value);",// 12
            "    }",                                               // 13
            "    void log(String text) {}",                        // 14
            "}").split("\n");
        List<int[]> spans = CodeReview.methodSpans(lines, "save");
        check(spans.size() == 3);
        check(spans.get(0)[0] == 2 && spans.get(0)[1] == 2);
        check(spans.get(1)[0] == 5 && spans.get(1)[1] == 10);
        check(spans.get(2)[0] == 11 && spans.get(2)[1] == 13);
        check(CodeReview.methodSpans(lines, "missing").isEmpty());
        Path root = Files.createTempDirectory("kuleshato-review-");
        try {
            write(root, "src/Repo.java", String.join("\n", lines));
            CodeReview.Target target = CodeReview.forMethod(root, "Repo#save");
            Map<String, CodeReview.Line> numbered = CodeReview.lines(target);
            check(numbered.values().stream().noneMatch(line -> line.text().contains("void log")));
            List<CodeReview.Line> evidence = numbered.values().stream()
                .filter(line -> line.number() == 7 || line.number() == 8 || line.number() == 12).toList();
            check(CodeReview.references(evidence).equals("src/Repo.java:7-8,12"));
            boolean missing = false;
            try { CodeReview.forMethod(root, "Nope#save"); } catch (java.io.IOException expected) { missing = expected.getMessage().contains("не найден"); }
            check(missing);
            boolean outside = false;
            try { CodeReview.forPath(root, "../"); } catch (java.io.IOException expected) { outside = true; }
            check(outside);
        } finally { deleteTree(root); }
    }

    private static void test(String name, Checked action) throws Exception {
        action.run();
        passed++;
        System.out.println("✓ " + name);
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Java test assertion failed");
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
