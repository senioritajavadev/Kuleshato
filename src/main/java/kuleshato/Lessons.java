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

    record Decision(Action action, String lesson) {}

    /** Что делать с уроками после ответа; чистая функция, чтобы правило можно было проверить тестами. */
    static Decision decide(String prompt, List<String> proposed, WorkspaceTools.TurnReport report) {
        boolean signal = userCorrection(prompt) || report != null && objectiveSignal(report);
        if (!proposed.isEmpty()) {
            if (!signal) return new Decision(Action.IGNORED_WITHOUT_ERROR, null);
            String lesson = clean(proposed.get(0));
            return lesson == null ? new Decision(Action.REJECTED, null) : new Decision(Action.OFFER, lesson);
        }
        if (report != null && unresolvedFailure(report)) return new Decision(Action.ASK_USER, null);
        return new Decision(Action.NONE, null);
    }

    /** Сигнал, при котором стоит спросить урок у самого пользователя: проверка так и не прошла. */
    static boolean unresolvedFailure(WorkspaceTools.TurnReport report) {
        return report.checkLimitReached || report.checkRun && !report.lastCheckPassed;
    }
}
