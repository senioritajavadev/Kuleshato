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
        int contextTokens, int maxTokens, double temperature, Duration timeout
    ) {}

    /** Модель не ответила за отведённое время; вызывающий код может показать детерминированный резервный ответ. */
    final class TimeoutException extends IOException {
        private static final long serialVersionUID = 1L;

        TimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
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
