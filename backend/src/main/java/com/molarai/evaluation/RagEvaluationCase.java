package com.molarai.evaluation;

import java.util.List;

public record RagEvaluationCase(
        String id,
        String question,
        List<String> expectedSourceDocuments,
        String category,
        ExpectedBehavior expectedBehavior,
        List<String> expectedAnswerPhrases,
        List<String> forbiddenAnswerPatterns,
        List<String> expectedDocumentIds) {

    public RagEvaluationCase {
        expectedSourceDocuments = expectedSourceDocuments == null ? List.of() : List.copyOf(expectedSourceDocuments);
        expectedAnswerPhrases = expectedAnswerPhrases == null ? List.of() : List.copyOf(expectedAnswerPhrases);
        forbiddenAnswerPatterns = forbiddenAnswerPatterns == null ? List.of() : List.copyOf(forbiddenAnswerPatterns);
        expectedDocumentIds = expectedDocumentIds == null ? List.of() : List.copyOf(expectedDocumentIds);
    }
}
