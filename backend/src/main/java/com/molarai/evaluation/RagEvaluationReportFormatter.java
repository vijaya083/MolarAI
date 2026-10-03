package com.molarai.evaluation;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class RagEvaluationReportFormatter {
    public String format(RagEvaluationReport report) {
        StringBuilder output = new StringBuilder();
        output.append("MolarAI RAG evaluation (mode: ").append(report.mode()).append(")\n")
                .append("Cases: ").append(report.totalCases())
                .append(" | supported: ").append(report.supportedCases())
                .append(" | unsupported: ").append(report.unsupportedCases()).append('\n')
                .append("Top-1 accuracy: ").append(percent(report.top1Accuracy()))
                .append(" | Top-3 accuracy: ").append(percent(report.top3Accuracy()))
                .append(" | Recall@5: ").append(percent(report.recallAt5())).append('\n')
                .append("Retrieval failures: ").append(report.retrievalFailures()).append('\n');
        output.append("Per-category retrieval accuracy (supported cases only):\n");
        report.perCategory().forEach((category, result) -> output.append("- ").append(category)
                .append(": supported=").append(result.supportedCases()).append('/').append(result.totalCases())
                .append(", top1=").append(percent(result.top1Accuracy()))
                .append(", top3=").append(percent(result.top3Accuracy()))
                .append(", failures=").append(result.retrievalFailures()).append('\n'));
        if (report.mode().contains("generation")) {
            output.append("Unsupported queries handled: ").append(report.unsupportedHandled()).append('/')
                    .append(report.unsupportedCases())
                    .append(" | answer-generation failures: ").append(report.answerGenerationFailures()).append('\n');
        } else {
            output.append("Unsupported answer handling: NOT RUN | answer-generation failures: NOT RUN\n");
        }
        output.append("Per-case results:\n");

        for (EvaluationCaseResult result : report.cases()) {
            String sources = result.retrievedSources().stream()
                    .map(source -> source.documentId() + " (" + source.documentName() + ")#" + source.chunkIndex()
                            + " (distance=" + String.format(Locale.ROOT, "%.4f", source.cosineDistance()) + ")")
                    .collect(Collectors.joining(", "));
            output.append("- ").append(result.id()).append(" [").append(result.category()).append("] ")
                    .append(result.question()).append('\n')
                    .append("  expected=").append(result.expectedSourceDocuments().isEmpty()
                            ? "unsupported" : String.join(" | ", result.expectedSourceDocuments()))
                    .append(" [ids=").append(result.expectedDocumentIds().isEmpty()
                            ? "-" : String.join(" | ", result.expectedDocumentIds())).append(']')
                    .append("; retrieved=").append(sources.isBlank() ? "<none>" : sources)
                    .append("; matchedRank=").append(value(result.matchedRank()))
                    .append("; matchedDistance=").append(result.retrievalDistance() == null
                            ? "-" : String.format(Locale.ROOT, "%.4f", result.retrievalDistance()))
                    .append("; retrieval=").append(result.retrievalSucceeded() ? "OK" : "FAILED")
                    .append("; answer=").append(result.answerStatus())
                    .append("; grounding=").append(result.groundingStatus()).append('\n');
            if (result.answerStatus() != EvaluationCaseResult.AnswerStatus.NOT_RUN) {
                output.append("  answerChecks: source=").append(result.expectedSourceInAnswerSources())
                        .append(", expectedEvidence=").append(result.expectedAnswerPhrasePresent())
                        .append(", acknowledgesMissing=").append(result.acknowledgesMissingInformation())
                        .append(", noForbiddenAnswerPattern=").append(result.noForbiddenAnswerPattern())
                        .append(", numericClaimsGrounded=").append(result.numericClaimsAppearInRetrievedContext()).append('\n');
            }
            if (result.retrievalFailure() != null) output.append("  retrievalFailure=").append(result.retrievalFailure()).append('\n');
            if (result.answerFailure() != null) output.append("  answerFailure=").append(result.answerFailure()).append('\n');
            if (result.answer() != null) output.append("  generatedAnswer=").append(result.answer()).append('\n');
        }
        output.append("Failed retrieval cases: ")
                .append(report.failedRetrievalCases().stream().map(EvaluationCaseResult::id)
                        .collect(Collectors.joining(", ")));
        if (report.failedRetrievalCases().isEmpty()) output.append("none");
        output.append('\n');
        return output.toString();
    }

    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100.0);
    }

    private static String value(Object value) {
        return value == null ? "-" : value.toString();
    }
}
