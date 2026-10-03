package com.molarai.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvaluationReportFormatterTest {
    @Test
    void printsSummaryAndPerCaseFailureDetails() {
        EvaluationCaseResult result = new EvaluationCaseResult(
                "unsupported-001", "What is Wi-Fi?", "unsupported",
                ExpectedBehavior.ACKNOWLEDGE_MISSING_INFORMATION, List.of(), List.of(), List.of(), true, null,
                null, null, EvaluationCaseResult.AnswerStatus.FAILED, EvaluationCaseResult.GroundingStatus.FAIL,
                false, false, false, true, true, null, "LlmProviderException");
        RagEvaluationReport report = new RagEvaluationReport(
                "generation", 1, 0, 1, 0, 0, 0, 0, 0, 1, List.of(result), java.util.Map.of(), List.of());

        String text = new RagEvaluationReportFormatter().format(report);

        assertTrue(text.contains("Top-1 accuracy: 0.0%"));
        assertTrue(text.contains("unsupported-001"));
        assertTrue(text.contains("answer=FAILED"));
        assertTrue(text.contains("answerFailure=LlmProviderException"));
    }
}
