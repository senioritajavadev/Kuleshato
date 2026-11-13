package kuleshato;

import java.util.regex.Pattern;

/**
 * Одно напоминание модели, которая описала исправление, но не внесла его.
 *
 * <p>В живом замере на qwen3:8b основная причина нерешённых задач — модель находит ошибку, пишет план и
 * заканчивает ход без единой правки. Приложение в этом случае один раз отвечает ей и даёт продолжить ход.
 * Напоминание не срабатывает, если пользователь просил не изменение, если файлы уже изменены, если изменение
 * или команда отклонены пользователем (тогда остановка правильна), если исчерпан лимит проверок или если
 * проверка в этом ходе прошла (менять нечего).
 */
final class ChangeNudge {
    private static final Pattern CHANGE_REQUEST = Pattern.compile(
        "(?iu)(исправ|почин|измени|поменя|добав|удали|реализ|переимен|рефактор|отрефактор|сделай|допиши|перепиши|"
            + "\\bfix|\\bimplement|\\bchange|\\badd\\b|\\bremove|\\brefactor|\\brename|\\bupdate)");

    static final String PREFIX = "[Сообщение приложения, не пользователя]";

    private ChangeNudge() {
    }

    static boolean isChangeRequest(String prompt) {
        return CHANGE_REQUEST.matcher(prompt).find();
    }

    /** Текст напоминания или {@code null}, если оно не нужно. */
    static String message(String prompt, WorkspaceTools.TurnReport report, CommandPolicy policy) {
        if (!isChangeRequest(prompt)) return null;
        if (!report.changedFiles.isEmpty() || !report.declinedFiles.isEmpty() || !report.declinedCommands.isEmpty()) return null;
        if (report.checkLimitReached || (report.checkRun && report.lastCheckPassed)) return null;
        String check = policy.checks().isEmpty() ? "" : " и запусти проверку " + policy.checks().get(0);
        return PREFIX + " В этом ходе не изменён ни один файл, хотя пользователь просил изменение. "
            + "Если ты уже нашёл причину, внеси исправление через edit_file" + check + ". "
            + "Если изменение не требуется или без пользователя не обойтись, ответь, почему, — не повторяй план.";
    }
}
