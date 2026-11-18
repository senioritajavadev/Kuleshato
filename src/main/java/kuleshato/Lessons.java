package kuleshato;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Уроки на будущее. Модель может предложить урок маркером {@code [[LESSON …]]}, но приложение лишь
 * показывает его пользователю и записывает в память только после подтверждения diff — и только если в запросе
 * есть объективный признак ошибки (упавшая или исчерпанная проверка, отклонённая правка) или пользователь
 * сам указал на ошибку. Так текст модели или файлов проекта не может незаметно стать постоянной инструкцией.
 *
 * <p>Вместе с правилом записывается его происхождение: исходный запрос, автор и доказательство ошибки (какая проверка
 * упала, какая правка отклонена, на что указал пользователь). Урок без доказательства не передаётся модели — так же,
 * как замечание ревью без ссылки на строки не показывается.
 */
final class Lessons {
    static final String PROMPT = "Уроки на будущее: если в этом запросе ты ошибся (пользователь указал на ошибку, "
        + "проверка упала или изменение было отклонено) и из этого следует устойчивое правило работы с этим проектом, "
        + "заверши ответ одной строкой [[LESSON краткое правило]]. Не больше одного урока, до 200 символов, без "
        + "секретов и без пересказа содержимого файлов. Урок записывается в память только после подтверждения пользователя.";

    record Extraction(String answer, List<String> proposed) {}

    private static final Pattern MARKER = Pattern.compile("\\[\\[\\s*LESSON\\s*:?\\s*(.*?)\\s*]]", Pattern.DOTALL);
    private static final Pattern SECRET = Pattern.compile(
        "(?iu)(password|passwd|пароль|token|токен|secret|api[_-]?key|private key|-----BEGIN|\\bsk-[a-z0-9]{8,})");
    private static final List<String> CORRECTIONS = List.of(
        "ошиб", "неправильн", "неверн", "не так", "не то ", "не работает", "сломал", "зачем ты", "ты не ",
        "wrong", "incorrect", "mistake", "broke");

    private Lessons() {}

    /** Вырезает маркеры уроков из ответа; ответ показывается пользователю уже без них. */
    static Extraction extract(String answer) {
        Matcher matcher = MARKER.matcher(answer);
        List<String> proposed = new ArrayList<>();
        while (matcher.find()) proposed.add(matcher.group(1));
        String cleaned = MARKER.matcher(answer).replaceAll("").replaceAll("\\n{3,}", "\n\n").strip();
        return new Extraction(cleaned, List.copyOf(proposed));
    }

    /** Нормализует урок или возвращает {@code null}, если он не годится для памяти. */
    static String clean(String lesson) {
        if (lesson == null) return null;
        String text = lesson.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").replaceAll("\\s+", " ").strip();
        text = text.replaceFirst("^[-*•]\\s*", "");
        if (text.length() < 10 || text.length() > 200) return null;
        if (text.contains("[[") || text.contains("]]") || text.startsWith("#")) return null;
        if (SECRET.matcher(text).find()) return null;
        return text;
    }

    /** Пользователь в этом сообщении указывает на ошибку ассистента. */
    static boolean userCorrection(String prompt) {
        String text = " " + prompt.toLowerCase(Locale.ROOT) + " ";
        return CORRECTIONS.stream().anyMatch(text::contains);
    }

    /** Приложение само зафиксировало ошибку: проверка упала или исчерпана, правка отклонена. */
    static boolean objectiveSignal(WorkspaceTools.TurnReport report) {
        return report.checkLimitReached
            || report.checkRun && !report.lastCheckPassed
            || report.failedChecks > 0
            || !report.declinedFiles.isEmpty();
    }

    enum Action { NONE, OFFER, IGNORED_WITHOUT_ERROR, REJECTED, ASK_USER }

    /** Откуда урок: запрос, автор и доказательство ошибки. Без доказательства урок не принимается. */
    record Provenance(String source, String evidence) {
        static final String SOURCE = "Источник: ";
        static final String EVIDENCE = "Доказательство: ";
    }

    record Decision(Action action, String lesson, Provenance provenance) {}

    /** Что делать с уроками после ответа; чистая функция, чтобы правило можно было проверить тестами. */
    static Decision decide(String prompt, List<String> proposed, WorkspaceTools.TurnReport report) {
        String evidence = evidence(prompt, report);
        if (!proposed.isEmpty()) {
            if (evidence == null) return new Decision(Action.IGNORED_WITHOUT_ERROR, null, null);
            String lesson = clean(proposed.get(0));
            return lesson == null ? new Decision(Action.REJECTED, null, null)
                : new Decision(Action.OFFER, lesson, new Provenance(source(prompt, "модель"), evidence));
        }
        if (report != null && unresolvedFailure(report)) {
            return new Decision(Action.ASK_USER, null, new Provenance(source(prompt, "пользователь"), evidence));
        }
        return new Decision(Action.NONE, null, null);
    }

    /** Доказательство ошибки в этом запросе или {@code null}, если ошибки не было. */
    static String evidence(String prompt, WorkspaceTools.TurnReport report) {
        List<String> parts = new ArrayList<>();
        if (report != null) {
            if (report.lastFailedCheck != null) parts.add("проверка " + report.lastFailedCheck);
            else if (report.failedChecks > 0 || report.checkRun && !report.lastCheckPassed) parts.add("проверка упала");
            if (report.checkLimitReached) parts.add("исчерпан лимит исправлений");
            if (!report.declinedFiles.isEmpty()) parts.add("отклонена правка " + String.join(", ", report.declinedFiles));
        }
        if (userCorrection(prompt)) parts.add("пользователь указал на ошибку");
        return parts.isEmpty() ? null : oneLine(String.join("; ", parts), 200);
    }

    /** Исходный запрос (сокращённый, без похожего на секреты) и автор урока. */
    static String source(String prompt, String author) {
        String request = SECRET.matcher(prompt).find() ? "[запрос скрыт: похож на секрет]" : "«" + oneLine(prompt, 120) + "»";
        return "запрос " + request + "; урок сформулировал " + author;
    }

    private static String oneLine(String text, int limit) {
        String line = text.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").replace("[[", "[").replace("]]", "]").strip();
        return line.length() <= limit ? line : line.substring(0, limit - 1) + "…";
    }

    /** Сигнал, при котором стоит спросить урок у самого пользователя: проверка так и не прошла. */
    static boolean unresolvedFailure(WorkspaceTools.TurnReport report) {
        return report.checkLimitReached || report.checkRun && !report.lastCheckPassed;
    }
}
