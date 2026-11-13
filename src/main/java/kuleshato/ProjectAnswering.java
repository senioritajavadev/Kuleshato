package kuleshato;

/**
 * Маршрутизация и проверка ответов на вопросы о проекте. Общая для CLI и эталонного набора,
 * чтобы бенчмарк измерял тот же путь, что видит пользователь.
 */
final class ProjectAnswering {
    enum Route { OVERVIEW, PURPOSE, IMPROVEMENT, SELF_DESCRIPTION, MODEL }

    /** Источник структурированного JSON-ответа модели; {@code correction} пуст при первой попытке. */
    @FunctionalInterface
    interface Model {
        String answer(String context, String question, String correction) throws Exception;
    }

    record Answer(Route route, String text, int modelCalls, boolean deterministicFallback) {}

    static final String INVALID_JSON_CORRECTION =
        "The previous response was not valid JSON in the required claims schema. Return only valid JSON with exactly "
            + "{\"claims\":[]} or claims whose evidence values are IDs copied from the supplied source lines. "
            + "Do not include markdown or commentary.";
    static final String REJECTED_EVIDENCE_CORRECTION =
        "The previous response had evidence references rejected by the validator. Retry once using only evidence IDs "
            + "copied exactly from the supplied source lines. Do not repeat unsupported claims; return an empty claims "
            + "array if no valid claim can be made.";

    private ProjectAnswering() {}

    static Answer answer(
        String prompt, ProjectInspection.Inspection inspection, String model, String ollamaUrl, Model client
    ) throws Exception {
        return answer(prompt, inspection, model, ollamaUrl, client, null);
    }

    /** {@code root} нужен для карты кода в обзоре проекта; без него обзор строится только по README. */
    static Answer answer(
        String prompt, ProjectInspection.Inspection inspection, String model, String ollamaUrl, Model client, java.nio.file.Path root
    ) throws Exception {
        if (ProjectInspection.isProjectOverviewQuestion(prompt)) {
            String overview = root == null ? ProjectInspection.renderProjectOverview(inspection)
                : ProjectInspection.renderProjectOverview(inspection, root);
            if (overview != null) return new Answer(Route.OVERVIEW, overview, 0, false);
        } else if (ProjectInspection.isProjectPurposeQuestion(prompt)) {
            String purpose = ProjectInspection.renderProjectPurpose(inspection);
            if (purpose != null) return new Answer(Route.PURPOSE, purpose, 0, false);
        }
        if (ProjectInspection.isProjectImprovementQuestion(prompt)) {
            return new Answer(Route.IMPROVEMENT, ProjectInspection.renderProjectImprovementSuggestions(inspection), 0, false);
        }
        if (ProjectInspection.isKuleshatoSelfDescriptionCheck(prompt, inspection)) {
            return new Answer(Route.SELF_DESCRIPTION,
                ProjectInspection.renderKuleshatoSelfDescription(inspection, model, ollamaUrl), 0, false);
        }
        return modelAnswer(prompt, inspection, model, ollamaUrl, client);
    }

    private static Answer modelAnswer(
        String prompt, ProjectInspection.Inspection inspection, String model, String ollamaUrl, Model client
    ) throws Exception {
        String structured;
        try {
            structured = client.answer(inspection.context(), prompt, "");
        } catch (ModelBackend.TimeoutException timeout) {
            return fallback(inspection, model, ollamaUrl, timeout.getMessage(), 1);
        }
        String result;
        try {
            result = ProjectInspection.renderVerifiedAnswer(structured, inspection, model, ollamaUrl);
        } catch (ProjectInspection.InvalidProjectAnswerException firstError) {
            return retry(prompt, inspection, model, ollamaUrl, client, INVALID_JSON_CORRECTION, firstError);
        }
        if (!result.contains("Отклонённые тезисы")) return new Answer(Route.MODEL, result, 1, false);
        return retry(prompt, inspection, model, ollamaUrl, client, REJECTED_EVIDENCE_CORRECTION, null);
    }

    private static Answer retry(
        String prompt, ProjectInspection.Inspection inspection, String model, String ollamaUrl, Model client,
        String correction, ProjectInspection.InvalidProjectAnswerException firstError
    ) throws Exception {
        String retried;
        try {
            retried = client.answer(inspection.context(), prompt, correction);
        } catch (ModelBackend.TimeoutException timeout) {
            String reason = firstError == null ? timeout.getMessage()
                : firstError.getMessage() + "; повторный запрос: " + timeout.getMessage();
            return fallback(inspection, model, ollamaUrl, reason, 2);
        }
        try {
            return new Answer(Route.MODEL, ProjectInspection.renderVerifiedAnswer(retried, inspection, model, ollamaUrl), 2, false);
        } catch (ProjectInspection.InvalidProjectAnswerException retryError) {
            String reason = firstError == null ? retryError.getMessage()
                : firstError.getMessage() + "; после повтора: " + retryError.getMessage();
            return fallback(inspection, model, ollamaUrl, reason, 2);
        }
    }

    private static Answer fallback(
        ProjectInspection.Inspection inspection, String model, String ollamaUrl, String reason, int calls
    ) {
        return new Answer(Route.MODEL, ProjectInspection.renderUnavailableAnswer(inspection, model, ollamaUrl, reason), calls, true);
    }
}
