package com.molarai.evaluation;

public record CategoryEvaluationResult(
        int totalCases,
        int supportedCases,
        double top1Accuracy,
        double top3Accuracy,
        int retrievalFailures) {
}
