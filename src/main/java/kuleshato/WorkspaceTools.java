package kuleshato;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WorkspaceTools {
    interface Approval {
        boolean approve(String detail) throws IOException;

        /** Сообщает пользователю о команде проверки, запущенной без отдельного подтверждения. */
        default void autoRun(String command, String reason) {}
    }

    interface FileApproval {
        boolean approve(String path, String diff) throws IOException;
    }

    /** Что на самом деле произошло за один запрос пользователя; формируется приложением, а не моделью. */
    static final class TurnReport {
        final List<String> changedFiles = new ArrayList<>();
        final List<String> declinedFiles = new ArrayList<>();
        final List<String> declinedCommands = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        int failedChecks;
        boolean checkLimitReached;
        boolean lastCheckPassed;
        boolean checkRun;

        boolean isEmpty() {
            return changedFiles.isEmpty() && declinedFiles.isEmpty() && declinedCommands.isEmpty() && commands.isEmpty();
        }

        String render() {
            StringBuilder result = new StringBuilder("Итог выполнения (по данным приложения):");
            result.append("\n- Изменённые файлы: ").append(changedFiles.isEmpty() ? "нет" : String.join(", ", changedFiles));
            if (!declinedFiles.isEmpty()) result.append("\n- Изменения отклонены пользователем: ").append(String.join(", ", declinedFiles));
            if (!declinedCommands.isEmpty()) result.append("\n- Команды отклонены: ").append(String.join("; ", declinedCommands));
            if (!commands.isEmpty()) {
                result.append("\n- Команды:");
                commands.forEach(command -> result.append("\n  - ").append(command));
            }
            if (!changedFiles.isEmpty()) {
                if (!checkRun) result.append("\n- Проверки после изменений не запускались.");
                else if (!lastCheckPassed) result.append("\n- Последняя проверка завершилась с ошибкой.");
            }
            if (checkLimitReached) result.append("\n- Достигнут лимит циклов исправления (" + MAX_FAILED_CHECKS + ").");
            return result.toString();
        }
    }

    static final int MAX_FAILED_CHECKS = 3;
    static final int MAX_MODEL_OUTPUT_CHARS = 6_000;

    private static final long MAX_BYTES = 256 * 1024;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 4_000_000;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 1_000_000;
    private static final java.util.Set<String> SKIP = java.util.Set.of(
        ".git", ".idea", "build", "coverage", "dist", "node_modules", "out", "target", "vendor"
    );
    private final Path root;
    private final Approval commandApproval;
    private final FileApproval fileApproval;
    private final CommandPolicy policy;
    static final int DEFAULT_READ_LINES = 200;
    static final int MAX_READ_LINES = 400;
    static final int MAX_READ_CHARS = 12_000;
    /** Сколько строк read_file отдаёт по умолчанию; 0 — весь файл (прежнее поведение, для сравнения в замерах). */
    private int readWindow = DEFAULT_READ_LINES;

    WorkspaceTools withReadWindow(int lines) {
        this.readWindow = Math.max(0, lines);
        return this;
    }
    private TurnReport report = new TurnReport();

    WorkspaceTools(Path root, Approval commandApproval, FileApproval fileApproval) {
        this(root, commandApproval, fileApproval, CommandPolicy.ASK_ALWAYS);
    }

    WorkspaceTools(Path root, Approval commandApproval, FileApproval fileApproval, CommandPolicy policy) {
        this.root = root;
        this.commandApproval = commandApproval;
        this.fileApproval = fileApproval;
        this.policy = policy;
    }

    /** Начинает новый запрос пользователя: сбрасывает счётчик неудачных проверок и итог. */
    void startTurn() {
        report = new TurnReport();
    }

    TurnReport report() {
        return report;
    }

    CommandPolicy policy() {
        return policy;
    }

    String execute(String name, Map<String, Object> input) throws Exception {
        return switch (name) {
            case "list_files" -> listFiles(text(input.getOrDefault("path", ".")), integer(input.get("max_depth"), 3));
            case "read_file" -> readFragment(requiredText(input, "path"),
                integer(input.get("start_line"), 1), integer(input.get("max_lines"), readWindow));
            case "search_files" -> searchFiles(requiredText(input, "query"), text(input.getOrDefault("path", ".")));
            case "write_file" -> writeFile(requiredText(input, "path"), requiredText(input, "content"));
            case "edit_file" -> {
                // Небольшие модели иногда опускают new_text: объясняем, что он нужен и как удалить текст.
                for (String key : List.of("old_text", "new_text")) {
                    if (!(input.get(key) instanceof String)) {
                        throw new IOException("Missing or invalid text argument: " + key + ". edit_file requires three strings: "
                            + "path, old_text (exact current text) and new_text (its replacement). "
                            + (key.equals("new_text") ? "Repeat the call with new_text; to delete old_text pass new_text as an empty string \"\"."
                                : "Copy old_text exactly from read_file output."));
                    }
                }
                yield editFile(requiredText(input, "path"), requiredText(input, "old_text"), requiredText(input, "new_text"));
            }
            case "run_command" -> runCommand(requiredText(input, "command"));
            default -> throw new IOException("Unknown tool: " + name);
        };
    }

    private String listFiles(String relative, int depth) throws IOException {
        Path directory = existing(relative);
        if (!Files.isDirectory(directory)) throw new IOException("Путь не является каталогом.");
        int maxDepth = Math.max(1, Math.min(depth, 8));
        List<String> results = new ArrayList<>();
        Files.walkFileTree(directory, java.util.Set.of(), maxDepth, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path path, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!path.equals(directory) && SKIP.contains(path.getFileName().toString())) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                if (!path.equals(directory) && directory.relativize(path).getNameCount() < maxDepth) {
                    results.add(root.relativize(path) + java.io.File.separator);
                }
                return results.size() >= 500 ? java.nio.file.FileVisitResult.TERMINATE : java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override
            public java.nio.file.FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) results.add(root.relativize(path).toString());
                return results.size() >= 500 ? java.nio.file.FileVisitResult.TERMINATE : java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return results.isEmpty() ? "(no files found)" : String.join("\n", results);
    }

    /**
     * Чтение для модели «через замочную скважину»: не больше {@code maxLines} строк и {@link #MAX_READ_CHARS} символов.
     * Текст строк отдаётся без изменений (без номеров), чтобы его можно было дословно использовать в edit_file;
     * диапазон и следующий фрагмент указаны в служебной строке.
     */
    private String readFragment(String relative, int startLine, int maxLines) throws IOException {
        String content = readFile(relative);
        if (readWindow <= 0) return content;
        String[] lines = content.split("\\R", -1);
        int total = lines.length > 1 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        int start = Math.max(1, startLine);
        if (start > Math.max(total, 1)) {
            throw new IOException("start_line " + start + " больше числа строк в файле (" + total + ").");
        }
        int limit = Math.max(1, Math.min(maxLines <= 0 ? readWindow : maxLines, MAX_READ_LINES));
        StringBuilder fragment = new StringBuilder();
        int end = start - 1;
        while (end < total && end - start + 1 < limit) {
            String line = lines[end];
            if (fragment.length() > 0 && fragment.length() + line.length() + 1 > MAX_READ_CHARS) break;
            fragment.append(line).append('\n');
            end++;
        }
        if (start == 1 && end >= total) return content;
        String display = relative.replace('\\', '/');
        String next = end < total ? " Следующий фрагмент: read_file(path=\"" + display + "\", start_line=" + (end + 1) + ")." : "";
        return "[Строки " + start + "–" + end + " из " + total + " файла " + display + "." + next
            + " Чтобы найти нужное место, используй search_files — он возвращает номера строк.]\n" + fragment;
    }

    private String readFile(String relative) throws IOException {
        Path file = existing(relative);
        if (!Files.isRegularFile(file)) throw new IOException("Путь не является файлом.");
        if (Files.size(file) > MAX_BYTES) throw new IOException("Файл превышает лимит 256 КБ.");
        String content = Files.readString(file);
        if (content.indexOf('\0') >= 0) throw new IOException("Бинарные файлы не поддерживаются.");
        return content;
    }

    private String searchFiles(String query, String relative) throws IOException {
        if (query.isEmpty()) throw new IOException("Строка поиска не должна быть пустой.");
        // path может быть и каталогом, и одним файлом: walkFileTree на файле посещает только его.
        Path directory = existing(relative);
        if (!Files.isDirectory(directory) && !Files.isRegularFile(directory)) {
            throw new IOException("Путь поиска не является ни каталогом, ни файлом.");
        }
        List<String> matches = new ArrayList<>();
        int[] scanned = {0};
        String needle = query.toLowerCase(Locale.ROOT);
        Files.walkFileTree(directory, java.util.Set.of(), Integer.MAX_VALUE, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path path, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!path.equals(directory) && SKIP.contains(path.getFileName().toString())) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override
            public java.nio.file.FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || ++scanned[0] > 5000) return java.nio.file.FileVisitResult.TERMINATE;
                // Модели часто ищут файл по имени («StringUtilsTest.java»), а не по содержимому.
                String relativePath = root.relativize(path).toString();
                if (relativePath.replace('\\', '/').toLowerCase(Locale.ROOT).contains(needle) && matches.size() < 100) {
                    matches.add(relativePath + ": (имя файла совпадает с запросом)");
                }
                try {
                    if (Files.size(path) > MAX_BYTES) return java.nio.file.FileVisitResult.CONTINUE;
                    String content = Files.readString(path);
                    if (content.indexOf('\0') >= 0) return java.nio.file.FileVisitResult.CONTINUE;
                    String[] lines = content.split("\\R", -1);
                    for (int i = 0; i < lines.length && matches.size() < 100; i++) {
                        if (lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
                            matches.add(root.relativize(path) + ":" + (i + 1) + ": " + lines[i]);
                        }
                    }
                } catch (IOException error) {
                    matches.add(root.relativize(path) + ": could not read file (" + error.getMessage() + ")");
                }
                return matches.size() >= 100 || scanned[0] >= 5000
                    ? java.nio.file.FileVisitResult.TERMINATE : java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        if (matches.isEmpty()) matches.add("(no matches found)");
        if (scanned[0] >= 5000) matches.add("Search stopped at the 5000-file limit");
        return String.join("\n", matches);
    }

    private String writeFile(String relative, String content) throws IOException {
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Содержимое превышает лимит 256 КБ.");
        Path target = resolve(relative);
        Path parent = target.getParent().toRealPath();
        inside(parent);
        if (!Files.isDirectory(parent)) throw new IOException("Родительский путь не является каталогом.");
        boolean isNew = !Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        String old = isNew ? "" : readFile(relative);
        if (!isNew && old.equals(content)) return "No changes needed for " + root.relativize(target);
        String display = root.relativize(target).toString();
        String diff = formatDiff(display, old, content);
        if (!fileApproval.approve(display, diff)) {
            report.declinedFiles.add(display);
            return "File change was not approved by the user; no changes made to " + display
                + ". Do not retry the same change; ask the user what to do instead.";
        }
        if (isNew && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Файл появился до подтверждения: " + display);
        if (!isNew && !readFile(relative).equals(old)) throw new IOException("Файл изменился до подтверждения: " + display);
        Files.writeString(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        String shown = display.replace('\\', '/');
        if (!report.changedFiles.contains(shown)) report.changedFiles.add(shown);
        report.checkRun = false;
        List<String> checks = policy.checks();
        return "Wrote " + display + (checks.isEmpty() ? ""
            : ". Verify the change with one of the detected checks: " + String.join(", ", checks));
    }

    private String editFile(String relative, String oldText, String newText) throws IOException {
        if (oldText.isEmpty()) throw new IOException("old_text must be a non-empty string");
        String current = readFile(relative);
        int first = current.indexOf(oldText);
        if (first < 0) {
            // Частая причина промаха — не смысл, а форма: \n вместо \r\n или другие отступы. Такое совпадение
            // принимается, только если оно единственное; изменение всё равно показывается пользователю как diff.
            int[] span = looseMatch(current, oldText);
            if (span == null) throw new IOException(editMiss(relative, current, oldText));
            String lineBreak = current.contains("\r\n") ? "\r\n" : "\n";
            String replacement = newText.replace("\r\n", "\n").replace("\n", lineBreak);
            return writeFile(relative, current.substring(0, span[0]) + replacement + current.substring(span[1]));
        }
        if (current.indexOf(oldText, first + 1) >= 0) throw new IOException("old_text occurs more than once; edit is ambiguous");
        return writeFile(relative, current.substring(0, first) + newText + current.substring(first + oldText.length()));
    }

    /**
     * Ищет {@code oldText} построчно без учёта переводов строк и отступов по краям строк. Возвращает границы
     * [начало, конец) в исходном тексте или {@code null}, если совпадений нет или их несколько.
     */
    static int[] looseMatch(String content, String oldText) {
        List<String> wanted = new ArrayList<>(oldText.replace("\r\n", "\n").lines().map(String::strip).toList());
        while (!wanted.isEmpty() && wanted.get(0).isEmpty()) wanted.remove(0);
        while (!wanted.isEmpty() && wanted.get(wanted.size() - 1).isEmpty()) wanted.remove(wanted.size() - 1);
        if (wanted.isEmpty()) return null;
        List<int[]> lines = new ArrayList<>();
        java.util.regex.Matcher line = java.util.regex.Pattern.compile("[^\\r\\n]*(\\r\\n|\\n|\\r|$)").matcher(content);
        while (line.find() && line.start() < content.length()) lines.add(new int[]{line.start(), line.end() - line.group(1).length()});
        int[] found = null;
        for (int start = 0; start + wanted.size() <= lines.size(); start++) {
            boolean equal = true;
            for (int i = 0; i < wanted.size() && equal; i++) {
                int[] bounds = lines.get(start + i);
                equal = content.substring(bounds[0], bounds[1]).strip().equals(wanted.get(i));
            }
            if (!equal) continue;
            if (found != null) return null;
            // Отступ первой строки и хвостовые пробелы последней остаются в файле, как при дословном old_text.
            int[] firstLine = lines.get(start);
            int[] lastLine = lines.get(start + wanted.size() - 1);
            int from = firstLine[0];
            while (from < firstLine[1] && Character.isWhitespace(content.charAt(from))) from++;
            int to = lastLine[1];
            while (to > lastLine[0] && Character.isWhitespace(content.charAt(to - 1))) to--;
            found = new int[]{from, to};
        }
        return found;
    }

    /** Объяснение промаха edit_file: похожие строки файла с номерами и как перечитать нужный фрагмент. */
    private static String editMiss(String relative, String content, String oldText) {
        String[] lines = content.split("\\R", -1);
        List<String> wantedLines = oldText.lines().map(String::strip).filter(text -> !text.isEmpty()).toList();
        java.util.Set<String> words = new java.util.HashSet<>();
        for (String text : wantedLines) {
            for (String word : text.split("[^\\p{L}\\p{N}_]+")) if (word.length() > 2) words.add(word);
        }
        record Candidate(int line, long score) {}
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].strip();
            if (stripped.isEmpty()) continue;
            long score = wantedLines.contains(stripped) ? 1000
                : java.util.Arrays.stream(stripped.split("[^\\p{L}\\p{N}_]+")).filter(words::contains).distinct().count();
            if (score > 0) candidates.add(new Candidate(i + 1, score));
        }
        candidates.sort(java.util.Comparator.comparingLong(Candidate::score).reversed().thenComparingInt(Candidate::line));
        String display = relative.replace('\\', '/');
        java.util.Set<String> fileLines = java.util.Arrays.stream(lines).map(String::strip).collect(java.util.stream.Collectors.toSet());
        String absent = wantedLines.stream().filter(text -> !fileLines.contains(text)).findFirst().orElse(null);
        StringBuilder message = new StringBuilder("old_text was not found in " + display + ".");
        if (absent != null) {
            message.append(" This old_text line is not in the file: «")
                .append(absent.length() > 120 ? absent.substring(0, 120) + "…" : absent).append("».");
        } else {
            message.append(" Every old_text line exists in the file, but not consecutively in this order.");
        }
        message.append(" old_text must match the current file text exactly. Do not repeat the same old_text.");
        if (!candidates.isEmpty()) {
            message.append(" Most similar lines now in the file:");
            for (Candidate candidate : candidates.subList(0, Math.min(3, candidates.size()))) {
                String text = lines[candidate.line() - 1];
                message.append("\n").append(candidate.line()).append(": ")
                    .append(text.length() > 160 ? text.substring(0, 160) + "…" : text);
            }
            int start = Math.max(1, candidates.get(0).line() - 5);
            message.append("\nRe-read that place with read_file(path=\"").append(display).append("\", start_line=")
                .append(start).append(", max_lines=30) and copy old_text from it.");
        } else {
            message.append(" Re-read the file with read_file before editing.");
        }
        return message.toString();
    }

    private String runCommand(String requested) throws Exception {
        if (requested.isBlank()) throw new IOException("Command must not be empty");
        String command = policy.canonical(requested);
        String output = runCanonical(command);
        if (command.equals(requested)) return output;
        // Пояснение в конце, чтобы начало ответа («Command failed…») читалось как обычно.
        return output + "\n(Ran the detected check `" + command + "`; the requested text `"
            + requested.replace("\t", "<TAB>").replace("\b", "<BS>").replace("\f", "<FF>")
            + "` was a distorted spelling of it. Use exactly `" + command + "` next time.)";
    }

    private String runCanonical(String command) throws Exception {
        boolean check = policy.isCheck(command);
        if (check && report.failedChecks >= MAX_FAILED_CHECKS) {
            report.checkLimitReached = true;
            return "Check was not run: the limit of " + MAX_FAILED_CHECKS + " failed verification cycles for this request "
                + "is reached. Stop changing files, report to the user that the checks still fail, and include the last error.";
        }
        CommandPolicy.Verdict verdict = policy.judge(command);
        if (verdict.decision() == CommandPolicy.Decision.AUTO_CHECK) {
            commandApproval.autoRun(command, verdict.reason());
        } else if (!commandApproval.approve(command)) {
            report.declinedCommands.add(command);
            return "Command was not approved by the user. Do not retry it; explain what you wanted to check instead.";
        }
        CommandResult result;
        try {
            result = execute(command);
        } catch (IOException error) {
            report.commands.add("`" + command + "` — не завершилась: " + error.getMessage());
            if (check) {
                report.checkRun = true;
                report.lastCheckPassed = false;
                report.failedChecks++;
            }
            throw error;
        }
        String status = result.exitCode() == 0 ? "успешно" : "ошибка, код " + result.exitCode();
        report.commands.add("`" + command + "` — " + status);
        if (check) {
            report.checkRun = true;
            report.lastCheckPassed = result.exitCode() == 0;
            if (result.exitCode() != 0) report.failedChecks++;
        }
        String output = boundForModel(result.output());
        if (result.exitCode() != 0) {
            String attempts = check
                ? "\nFailed verification cycle " + report.failedChecks + " of " + MAX_FAILED_CHECKS
                    + (report.failedChecks >= MAX_FAILED_CHECKS
                        ? ". No further check runs are allowed for this request; report the failure to the user." : ".")
                : "";
            return "Command failed (exit " + result.exitCode() + "):\n" + output + attempts;
        }
        return output.isBlank() ? "(command completed with no output)" : output;
    }

    record CommandResult(int exitCode, String output) {}

    /**
     * Ограничивает вывод для модели: начало, строки с ошибками и конец. Полный вывод модели не нужен и
     * вытесняет контекст; номера пропущенных строк не важны.
     */
    static String boundForModel(String output) {
        if (output.length() <= MAX_MODEL_OUTPUT_CHARS) return output;
        String[] lines = output.split("\\R");
        int headChars = 1_200;
        int tailChars = 2_800;
        StringBuilder head = new StringBuilder();
        int index = 0;
        while (index < lines.length && head.length() + lines[index].length() < headChars) head.append(lines[index++]).append('\n');
        StringBuilder tail = new StringBuilder();
        int end = lines.length;
        while (end > index && tail.length() + lines[end - 1].length() < tailChars) tail.insert(0, lines[--end] + "\n");
        java.util.regex.Pattern important = java.util.regex.Pattern.compile(
            "(?i)(\\berror\\b|\\[error]|fail|exception|tests run:.*(failures|errors)|build failure|cannot find symbol|expected)");
        StringBuilder errors = new StringBuilder();
        int budget = MAX_MODEL_OUTPUT_CHARS - head.length() - tail.length() - 200;
        for (int i = index; i < end; i++) {
            if (!important.matcher(lines[i]).find()) continue;
            String line = lines[i].length() > 300 ? lines[i].substring(0, 300) + "…" : lines[i];
            if (errors.length() + line.length() + 1 > budget) break;
            errors.append(line).append('\n');
        }
        return head + "... output shortened from " + output.length() + " characters; error lines from the middle: ...\n"
            + errors + "... last lines: ...\n" + tail;
    }

    private CommandResult execute(String command) throws Exception {
        ProcessBuilder builder = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
            ? new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
            : new ProcessBuilder("/bin/sh", "-lc", command);
        builder.directory(root.toFile()).redirectErrorStream(true);
        Process process = builder.start();
        java.util.concurrent.CompletableFuture<CommandOutput> outputFuture =
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return readLimited(process.getInputStream(), MAX_COMMAND_OUTPUT_BYTES);
                } catch (IOException error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            });
        boolean finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out after 120 seconds");
        }
        CommandOutput captured;
        try {
            captured = outputFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new IOException("Could not read command output.", error.getCause());
        } catch (java.util.concurrent.TimeoutException error) {
            throw new IOException("Timed out while reading command output.", error);
        }
        String output = new String(captured.content(), StandardCharsets.UTF_8);
        boolean charTruncated = output.length() > MAX_COMMAND_OUTPUT_CHARS;
        if (charTruncated) {
            output = output.substring(0, MAX_COMMAND_OUTPUT_CHARS);
        }
        if (captured.truncated() || charTruncated) output += "\n... command output truncated ...";
        return new CommandResult(process.exitValue(), output);
    }

    record CommandOutput(byte[] content, boolean truncated) {}

    static CommandOutput readLimited(InputStream input, int maxBytes) throws IOException {
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must not be negative");
        ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        int count;
        while ((count = input.read(buffer)) != -1) {
            int remaining = maxBytes - captured.size();
            int retained = Math.min(count, remaining);
            if (retained > 0) captured.write(buffer, 0, retained);
            if (retained < count) truncated = true;
        }
        return new CommandOutput(captured.toByteArray(), truncated);
    }

    private Path existing(String relative) throws IOException {
        Path candidate = resolve(relative);
        if (!Files.exists(candidate)) throw new IOException(missingPath(relative, candidate));
        Path actual = candidate.toRealPath();
        inside(actual);
        return actual;
    }

    /**
     * Сообщение о несуществующем пути с содержимым ближайшего существующего каталога. Небольшие модели угадывают
     * раскладку Maven ({@code src/test/java}) и без подсказки повторяют один и тот же неверный путь.
     */
    private String missingPath(String relative, Path candidate) {
        Path parent = candidate.getParent();
        while (parent != null && parent.startsWith(root) && !Files.isDirectory(parent)) parent = parent.getParent();
        if (parent == null || !parent.startsWith(root)) parent = root;
        List<String> entries = new ArrayList<>();
        try (var stream = Files.list(parent)) {
            for (Path entry : stream.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (SKIP.contains(name) || name.startsWith(".")) continue;
                if (entries.size() == 30) {
                    entries.add("…");
                    break;
                }
                entries.add(Files.isDirectory(entry) ? name + "/" : name);
            }
        } catch (IOException error) {
            entries.add("(не удалось прочитать каталог)");
        }
        String where = parent.equals(root) ? "в корне проекта" : "в каталоге " + root.relativize(parent).toString().replace('\\', '/');
        return "Путь " + relative.replace('\\', '/') + " не существует. Ближайший существующий каталог — " + where + ": "
            + (entries.isEmpty() ? "(пусто)" : String.join(", ", entries))
            + ". Чтобы найти файл по имени или содержимому во всём проекте, вызови search_files без path.";
    }

    private Path resolve(String relative) throws IOException {
        if (relative.indexOf('\0') >= 0) throw new IOException("Path must be a valid string");
        Path target = root.resolve(relative).normalize();
        inside(target);
        return target;
    }

    private void inside(Path path) throws IOException {
        if (!path.startsWith(root)) throw new IOException("Path must remain inside the workspace");
    }

    static String formatDiff(String path, String before, String after) {
        String[] oldLines = before.split("\\R", -1);
        String[] newLines = after.split("\\R", -1);
        int prefix = 0;
        while (prefix < oldLines.length && prefix < newLines.length && oldLines[prefix].equals(newLines[prefix])) prefix++;
        int suffix = 0;
        while (suffix < oldLines.length - prefix && suffix < newLines.length - prefix
            && oldLines[oldLines.length - suffix - 1].equals(newLines[newLines.length - suffix - 1])) suffix++;
        List<String> lines = new ArrayList<>();
        for (int i = Math.max(0, prefix - 3); i < prefix; i++) lines.add(" " + oldLines[i]);
        for (int i = prefix; i < oldLines.length - suffix; i++) lines.add("-" + oldLines[i]);
        for (int i = prefix; i < newLines.length - suffix; i++) lines.add("+" + newLines[i]);
        for (int i = oldLines.length - suffix; i < Math.min(oldLines.length, oldLines.length - suffix + 3); i++) lines.add(" " + oldLines[i]);
        if (lines.size() > 120) {
            List<String> clipped = new ArrayList<>(lines.subList(0, 60));
            clipped.add("... diff truncated ...");
            clipped.addAll(lines.subList(lines.size() - 59, lines.size()));
            lines = clipped;
        }
        return "--- a/" + path + "\n+++ b/" + path + "\n@@ " + (prefix + 1)
            + " -" + (oldLines.length - prefix - suffix) + " +" + (newLines.length - prefix - suffix)
            + " @@\n" + String.join("\n", lines);
    }

    private static String requiredText(Map<String, Object> input, String key) throws IOException {
        Object value = input.get(key);
        if (value instanceof String text) return text;
        throw new IOException("Missing or invalid text argument: " + key);
    }

    private static String text(Object value) {
        return value instanceof String text ? text : ".";
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
