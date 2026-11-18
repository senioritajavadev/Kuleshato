package kuleshato;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Протокол обращения к языковой модели. Здесь только транспорт и формат сообщений: цикл инструментов,
 * подсказки и проверка доказательств живут в {@link ModelClient}, {@link WorkspaceTools},
 * {@link ProjectInspection} и {@link CodeReview} и от адаптера не зависят.
 *
 * <p>Сообщения передаются в формате чата: {@code {"role", "content", "tool_calls"}}, где вызов инструмента —
 * {@code {"function": {"name", "arguments"}}}. Адаптер обязан вернуть ответ ассистента в том же формате.
 */
interface ModelBackend {
    /** Параметры одного запроса; {@code tools} может быть {@code null}, если инструменты не нужны. */
    record Request(
        List<Object> messages, List<Object> tools, boolean json, boolean think,
        int contextTokens, int maxTokens, double temperature, Duration timeout, int thinkingBudget
    ) {
        /** Запрос без бюджета рассуждения (0 — рассуждение не ограничено ничем, кроме {@code maxTokens}). */
        Request(List<Object> messages, List<Object> tools, boolean json, boolean think,
                int contextTokens, int maxTokens, double temperature, Duration timeout) {
            this(messages, tools, json, think, contextTokens, maxTokens, temperature, timeout, 0);
        }

        Request withoutThinking() {
            return new Request(messages, tools, json, false, contextTokens, maxTokens, temperature, timeout, 0);
        }
    }

    /** Модель не ответила за отведённое время; вызывающий код может показать детерминированный резервный ответ. */
    final class TimeoutException extends IOException {
        private static final long serialVersionUID = 1L;

        TimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Рассуждение превысило {@link Request#thinkingBudget()}; запрос прерван, его можно повторить без Thinking. */
    final class ThinkingBudgetExceeded extends IOException {
        private static final long serialVersionUID = 1L;
        final int thinkingTokens;

        ThinkingBudgetExceeded(int thinkingTokens) {
            super("Рассуждение модели превысило бюджет: " + thinkingTokens + " токенов.");
            this.thinkingTokens = thinkingTokens;
        }
    }

    /** Этап генерации для строки состояния. */
    enum Phase { PROMPT, THINKING, ANSWER }

    /** Где загружена модель: доля в видеопамяти и окно контекста, с которым её загрузил сервер. */
    record Placement(String model, double gpuShare, int contextTokens) {
        boolean partlyOnCpu() {
            return gpuShare < 0.99;
        }

        int cpuPercent() {
            return (int) Math.round((1 - gpuShare) * 100);
        }
    }

    /** Наблюдатель за ходом генерации; вызывается из потока запроса примерно раз в секунду и при новых токенах. */
    interface Progress {
        default void update(Phase phase, int thinkingTokens, int answerTokens, Duration elapsed) {}
        default void placement(Placement placement) {}
    }

    /** Подписывает наблюдателя за ходом генерации; адаптер без потоковой передачи может его игнорировать. */
    default void progress(Progress listener) {}

    /** Где сейчас загружена модель, или {@code null}, если адаптер этого не знает или модель не загружена. */
    default Placement placement() {
        return null;
    }

    /** Накопленная статистика генерации; адаптер, который её не знает, возвращает {@link Usage#EMPTY}. */
    record Usage(int calls, long promptTokens, long outputTokens, long outputNanos) {
        static final Usage EMPTY = new Usage(0, 0, 0, 0);

        Usage plus(long prompt, long output, long nanos) {
            return new Usage(calls + 1, promptTokens + prompt, outputTokens + output, outputNanos + nanos);
        }

        Usage minus(Usage earlier) {
            return new Usage(calls - earlier.calls, promptTokens - earlier.promptTokens,
                outputTokens - earlier.outputTokens, outputNanos - earlier.outputNanos);
        }

        /** Скорость генерации в токенах в секунду или 0, если адаптер не сообщил время. */
        double tokensPerSecond() {
            return outputNanos <= 0 ? 0 : outputTokens / (outputNanos / 1e9);
        }
    }

    List<String> SUPPORTED = List.of("ollama");

    default Usage usage() {
        return Usage.EMPTY;
    }

    /** Имя адаптера для настроек и отчётов, например {@code ollama}. */
    String name();

    String model();

    /** Выполняет запрос и возвращает сообщение ассистента. */
    Map<String, Object> complete(Request request) throws IOException;

    /** Адаптер по имени из настроек. Новые адаптеры добавляются только после проверки API, tool calls и безопасности. */
    static ModelBackend create(String backend, String url, String model) throws IOException {
        if ("ollama".equals(backend)) return new OllamaBackend(url, model);
        throw new IOException("Адаптер модели \"" + backend + "\" не поддерживается; доступны: " + String.join(", ", SUPPORTED));
    }
}
