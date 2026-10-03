package com.molarai.evaluation;

import java.util.List;

public record EvaluationCaseResult(
        String id,
        String question,
        String category,
        ExpectedBehavior expectedBehavior,
        List<String> expectedSourceDocuments,
        List<String> expectedDocumentIds,
        List<EvaluationRetrievedSource> retrievedSources,
        boolean retrievalSucceeded,
        String retrievalFailure,
        Integer matchedRank,
        Double retrievalDistance,
        AnswerStatus answerStatus,
        GroundingStatus groundingStatus,
        boolean expectedSourceInAnswerSources,
        boolean expectedAnswerPhrasePresent,
        boolean acknowledgesMissingInformation,
        boolean noForbiddenAnswerPattern,
        boolean numericClaimsAppearInRetrievedContext,
        String answer,
        String answerFailure) {

    public enum AnswerStatus {
        NOT_RUN,
        GENERATED,
        FAILED
    }

    public enum GroundingStatus {
        NOT_RUN,
        PASS,
        FAIL
    }
}
