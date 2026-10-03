package com.molarai.evaluation;

import java.util.List;
import java.util.Map;

public record RagEvaluationReport(
        String mode,
        int totalCases,
        int supportedCases,
        int unsupportedCases,
        double recallAt1,
        double recallAt3,
        double recallAt5,
        int retrievalFailures,
        int unsupportedHandled,
        int answerGenerationFailures,
        List<EvaluationCaseResult> cases,
        Map<String, CategoryEvaluationResult> perCategory,
        List<EvaluationCaseResult> failedRetrievalCases) {

    public double top1Accuracy() {
        return recallAt1;
    }

    public double top3Accuracy() {
        return recallAt3;
    }
}
