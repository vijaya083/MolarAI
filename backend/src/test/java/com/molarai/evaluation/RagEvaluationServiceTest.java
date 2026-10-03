package com.molarai.evaluation;

import com.molarai.dto.GroundedAnswerResponse;
import com.molarai.model.GroundedAnswerSource;
import com.molarai.model.KnowledgeSearchMatch;
import com.molarai.service.GroundedResponseService;
import com.molarai.service.KnowledgeRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagEvaluationServiceTest {
    @Test
    void calculatesRecallAtOneThreeAndFiveAndBestExpectedRank() {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        GroundedResponseService generation = mock(GroundedResponseService.class);
        RagEvaluationService service = new RagEvaluationService(retrieval, generation);
        List<RagEvaluationCase> cases = List.of(
                supported("a", "A"), supported("b", "B"), supported("c", "C"), unsupported("u"));
        when(retrieval.search("a", 5)).thenReturn(List.of(match("A", 0.1), match("Other", 0.2)));
        when(retrieval.search("b", 5)).thenReturn(List.of(match("X", 0.1), match("Y", 0.2), match("B", 0.3)));
        when(retrieval.search("c", 5)).thenReturn(List.of(
                match("W", 0.1), match("X", 0.2), match("Y", 0.3), match("Z", 0.4), match("C", 0.5)));
        when(retrieval.search("u", 5)).thenReturn(List.of(match("Similar but not expected", 0.1)));

        RagEvaluationReport report = service.evaluateRetrieval(cases);

        assertEquals(4, report.totalCases());
        assertEquals(3, report.supportedCases());
        assertEquals(1, report.unsupportedCases());
        assertEquals(1.0 / 3.0, report.recallAt1(), 0.00001);
        assertEquals(2.0 / 3.0, report.recallAt3(), 0.00001);
        assertEquals(report.recallAt1(), report.top1Accuracy(), 0.0);
        assertEquals(report.recallAt3(), report.top3Accuracy(), 0.0);
        assertEquals(1.0, report.recallAt5(), 0.00001);
        assertEquals(1.0 / 3.0, report.perCategory().get("test").top1Accuracy(), 0.00001);
        assertEquals(2.0 / 3.0, report.perCategory().get("test").top3Accuracy(), 0.00001);
        assertEquals(0, report.failedRetrievalCases().size());
        assertEquals(0, report.retrievalFailures());
        assertEquals(3, report.cases().get(1).matchedRank());
        assertEquals(0.3, report.cases().get(1).retrievalDistance());
        assertNull(report.cases().get(0).answer());
        assertEquals(EvaluationCaseResult.AnswerStatus.NOT_RUN, report.cases().get(0).answerStatus());
        assertFalse(report.cases().get(3).expectedDocumentIds().size() > 0);
        assertEquals("x", report.cases().get(1).retrievedSources().get(0).documentId());
        verifyNoInteractions(generation);
    }

    @Test
    void countsSupportedCaseWithoutExpectedSourceAsRetrievalFailureButDoesNotPenalizeUnsupported() {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        RagEvaluationService service = new RagEvaluationService(retrieval, mock(GroundedResponseService.class));
        when(retrieval.search("supported", 5)).thenReturn(List.of(match("Wrong source", 0.4)));
        when(retrieval.search("unsupported", 5)).thenReturn(List.of(match("Similar source", 0.3)));

        RagEvaluationReport report = service.evaluateRetrieval(List.of(
                supported("supported", "Expected source"), unsupported("unsupported")));

        assertEquals(1, report.retrievalFailures());
        assertEquals(1, report.failedRetrievalCases().size());
        assertEquals("supported", report.failedRetrievalCases().getFirst().id());
        assertNull(report.cases().get(0).matchedRank());
        assertNull(report.cases().get(1).matchedRank());
    }

    @Test
    void assessesSupportedAnswersAndUnsupportedAbstentionWithDeterministicHeuristics() {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        GroundedResponseService generation = mock(GroundedResponseService.class);
        RagEvaluationService service = new RagEvaluationService(retrieval, generation);
        RagEvaluationCase supported = new RagEvaluationCase("h", "hours", List.of("Clinic Hours"), "hours",
                ExpectedBehavior.ANSWER_FROM_KNOWLEDGE, List.of("Monday", "8:00"), List.of(), List.of("hours"));
        RagEvaluationCase unsupported = new RagEvaluationCase("u", "wifi", List.of(), "unsupported",
                ExpectedBehavior.ACKNOWLEDGE_MISSING_INFORMATION, List.of(),
                List.of("(?i)(?:wi-?fi|wireless).{0,30}password +(?:is|:) +(?!not |unavailable|unknown|missing)[^ ]+"), List.of());
        when(retrieval.search("hours", 5)).thenReturn(List.of(match("Clinic Hours", 0.2,
                "Open Monday through Friday from 8:00 a.m. to 5:00 p.m.")));
        when(retrieval.search("wifi", 5)).thenReturn(List.of(match("Contact", 0.3, "Email us for information.")));
        when(generation.answer("hours")).thenReturn(new GroundedAnswerResponse(
                "Open Monday through Friday from 8:00 a.m. to 5:00 p.m.",
                List.of(new GroundedAnswerSource("hours", "Clinic Hours", 0, Map.of(), 0.2))));
        when(generation.answer("wifi")).thenReturn(new GroundedAnswerResponse(
                "The clinic Wi-Fi password is not mentioned in the provided context.", List.of()));

        RagEvaluationReport report = service.evaluateGeneration(List.of(supported, unsupported));

        assertEquals(1, report.unsupportedHandled());
        assertEquals(EvaluationCaseResult.GroundingStatus.PASS, report.cases().get(0).groundingStatus());
        assertEquals(EvaluationCaseResult.GroundingStatus.PASS, report.cases().get(1).groundingStatus());
        assertTrue(report.cases().get(1).acknowledgesMissingInformation());
        assertTrue(report.cases().get(1).noForbiddenAnswerPattern());
    }

    @Test
    void flagsKnownContradictionPatternsInGeneratedAnswers() {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        GroundedResponseService generation = mock(GroundedResponseService.class);
        RagEvaluationService service = new RagEvaluationService(retrieval, generation);
        RagEvaluationCase hours = new RagEvaluationCase("hours", "hours", List.of("Clinic Hours"), "hours",
                ExpectedBehavior.ANSWER_FROM_KNOWLEDGE, List.of("Monday"),
                List.of("(?i)closes Saturday from 1:00 p[.]m[.] to 5:00 p[.]m[.]"), List.of("hours"));
        when(retrieval.search("hours", 5)).thenReturn(List.of(match("Clinic Hours", 0.1,
                "Monday to Friday 8:00 a.m. to 5:00 p.m.; Saturday 9:00 a.m. to 1:00 p.m.")));
        when(generation.answer("hours")).thenReturn(new GroundedAnswerResponse(
                "The office opens Monday at 8:00 a.m. and closes Saturday from 1:00 p.m. to 5:00 p.m.",
                List.of(new GroundedAnswerSource("hours", "Clinic Hours", 0, Map.of(), 0.1))));

        RagEvaluationReport report = service.evaluateGeneration(List.of(hours));

        assertEquals(EvaluationCaseResult.GroundingStatus.FAIL, report.cases().getFirst().groundingStatus());
        assertFalse(report.cases().getFirst().noForbiddenAnswerPattern());
    }

    @Test
    void recognizesModelWordingThatSaysContextDoesNotSpecifyAnAnswer() {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        GroundedResponseService generation = mock(GroundedResponseService.class);
        RagEvaluationService service = new RagEvaluationService(retrieval, generation);
        RagEvaluationCase unsupported = unsupported("dentist");
        when(retrieval.search("dentist", 5)).thenReturn(List.of(match("Clinic Hours", 0.5, "Opening hours only.")));
        when(generation.answer("dentist")).thenReturn(new GroundedAnswerResponse(
                "The clinic context does not specify which dentist is assigned. The excerpts do not mention dentist assignments.",
                List.of()));

        RagEvaluationReport report = service.evaluateGeneration(List.of(unsupported));

        assertEquals(1, report.unsupportedHandled());
        assertEquals(EvaluationCaseResult.GroundingStatus.PASS, report.cases().getFirst().groundingStatus());
    }

    @Test
    void numericEvidencePhraseMustNotMatchPartOfAnotherNumber() {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        GroundedResponseService generation = mock(GroundedResponseService.class);
        RagEvaluationService service = new RagEvaluationService(retrieval, generation);
        RagEvaluationCase age = new RagEvaluationCase("age", "age", List.of("Pediatric Dentistry"), "pediatric",
                ExpectedBehavior.ANSWER_FROM_KNOWLEDGE, List.of("5"), List.of(), List.of("pediatric"));
        when(retrieval.search("age", 5)).thenReturn(List.of(match("Pediatric Dentistry", 0.1,
                "Children age 5 and older are welcome.")));
        when(generation.answer("age")).thenReturn(new GroundedAnswerResponse(
                "Children age 15 and older are welcome.",
                List.of(new GroundedAnswerSource("pediatric", "Pediatric Dentistry", 0, Map.of(), 0.1))));

        RagEvaluationReport report = service.evaluateGeneration(List.of(age));

        assertFalse(report.cases().getFirst().expectedAnswerPhrasePresent());
        assertEquals(EvaluationCaseResult.GroundingStatus.FAIL, report.cases().getFirst().groundingStatus());
    }

    @Test
    void reportsFailuresInsteadOfHidingRetrievalExceptions() {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.search("query", 5)).thenThrow(new IllegalStateException("offline"));
        RagEvaluationService service = new RagEvaluationService(retrieval, mock(GroundedResponseService.class));

        RagEvaluationReport report = service.evaluateRetrieval(List.of(supported("query", "Source")));

        assertEquals(1, report.retrievalFailures());
        assertFalse(report.cases().getFirst().retrievalSucceeded());
        assertEquals("IllegalStateException", report.cases().getFirst().retrievalFailure());
    }

    private static RagEvaluationCase supported(String question, String expectedSource) {
        return new RagEvaluationCase(question, question, List.of(expectedSource), "test",
                ExpectedBehavior.ANSWER_FROM_KNOWLEDGE, List.of("evidence"), List.of(),
                List.of(expectedSource.toLowerCase()));
    }

    private static RagEvaluationCase unsupported(String question) {
        return new RagEvaluationCase(question, question, List.of(), "unsupported",
                ExpectedBehavior.ACKNOWLEDGE_MISSING_INFORMATION, List.of(), List.of(), List.of());
    }

    private static KnowledgeSearchMatch match(String name, double distance) {
        return match(name, distance, "Some evidence");
    }

    private static KnowledgeSearchMatch match(String name, double distance, String content) {
        return new KnowledgeSearchMatch(name.toLowerCase(), name, 0, content, Map.of(), distance);
    }
}
