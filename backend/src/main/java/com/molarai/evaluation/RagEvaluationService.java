package com.molarai.evaluation;

import com.molarai.dto.GroundedAnswerResponse;
import com.molarai.model.GroundedAnswerSource;
import com.molarai.model.KnowledgeSearchMatch;
import com.molarai.service.GroundedResponseService;
import com.molarai.service.KnowledgeRetrievalService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TreeMap;

@Service
public class RagEvaluationService {
    public static final int EVALUATION_TOP_K = 5;
    private static final Pattern MISSING_INFORMATION = Pattern.compile(
            "(?i)\\b(not available|unavailable|not mentioned|not specified|not provided|not included|(?:does|do) not mention|doesn't mention|(?:does|do) not specify|doesn't specify|not in (?:the )?(?:provided )?(?:context|excerpts)|do not have (?:that )?information|don't have (?:that )?information|cannot confirm|can't confirm|unable to (?:find|confirm))\\b");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern SOURCE_ORDINAL = Pattern.compile("(?i)\\bsource\\s+\\d+\\b");

    private final KnowledgeRetrievalService retrievalService;
    private final GroundedResponseService groundedResponseService;

    public RagEvaluationService(
            KnowledgeRetrievalService retrievalService,
            GroundedResponseService groundedResponseService) {
        this.retrievalService = retrievalService;
        this.groundedResponseService = groundedResponseService;
    }

    public RagEvaluationReport evaluateRetrieval(List<RagEvaluationCase> dataset) {
        return evaluate(dataset, false);
    }

    public RagEvaluationReport evaluateGeneration(List<RagEvaluationCase> dataset) {
        return evaluate(dataset, true);
    }

    private RagEvaluationReport evaluate(List<RagEvaluationCase> dataset, boolean includeGeneration) {
        if (dataset == null || dataset.isEmpty()) {
            throw new IllegalArgumentException("Evaluation dataset must not be empty");
        }

        List<EvaluationCaseResult> caseResults = new ArrayList<>();
        int supportedCount = 0;
        int unsupportedCount = 0;
        int retrievalFailures = 0;
        int unsupportedHandled = 0;
        int answerFailures = 0;
        int recall1Hits = 0;
        int recall3Hits = 0;
        int recall5Hits = 0;
        List<EvaluationCaseResult> failedRetrievalCases = new ArrayList<>();

        for (RagEvaluationCase testCase : dataset) {
            boolean supported = testCase.expectedBehavior() == ExpectedBehavior.ANSWER_FROM_KNOWLEDGE;
            if (supported) {
                supportedCount++;
            } else {
                unsupportedCount++;
            }

            List<KnowledgeSearchMatch> matches = List.of();
            boolean retrievalSucceeded = false;
            String retrievalFailure = null;
            try {
                matches = retrievalService.search(testCase.question(), EVALUATION_TOP_K);
                retrievalSucceeded = true;
            } catch (RuntimeException exception) {
                retrievalFailure = exception.getClass().getSimpleName();
            }

            Integer matchedRank = findExpectedRank(testCase, matches);
            Double matchedDistance = matchedRank == null ? null : matches.get(matchedRank - 1).cosineDistance();
            if (!retrievalSucceeded || (supported && matchedRank == null)) {
                retrievalFailures++;
            }
            if (supported && matchedRank != null) {
                if (matchedRank <= 1) recall1Hits++;
                if (matchedRank <= 3) recall3Hits++;
                if (matchedRank <= 5) recall5Hits++;
            }

            List<EvaluationRetrievedSource> retrievedSources = matches.stream()
                    .map(match -> new EvaluationRetrievedSource(
                            match.documentId(), match.documentName(), match.chunkIndex(), match.cosineDistance()))
                    .toList();
            EvaluationCaseResult.AnswerStatus answerStatus = EvaluationCaseResult.AnswerStatus.NOT_RUN;
            EvaluationCaseResult.GroundingStatus groundingStatus = EvaluationCaseResult.GroundingStatus.NOT_RUN;
            boolean expectedSourceInAnswer = false;
            boolean answerPhrasePresent = false;
            boolean acknowledgesMissing = false;
            boolean noForbiddenClaim = true;
            boolean numericClaimsGrounded = true;
            String answer = null;
            String answerFailure = null;

            if (includeGeneration) {
                try {
                    GroundedAnswerResponse response = groundedResponseService.answer(testCase.question());
                    answer = response.answer();
                    answerStatus = answer == null || answer.isBlank()
                            ? EvaluationCaseResult.AnswerStatus.FAILED
                            : EvaluationCaseResult.AnswerStatus.GENERATED;
                    expectedSourceInAnswer = response.sources() != null && response.sources().stream()
                            .map(GroundedAnswerSource::documentId)
                            .anyMatch(testCase.expectedDocumentIds()::contains);
                    answerPhrasePresent = containsAny(answer, testCase.expectedAnswerPhrases());
                    acknowledgesMissing = answer != null && MISSING_INFORMATION.matcher(answer).find();
                    noForbiddenClaim = matchesNoForbiddenPattern(answer, testCase.forbiddenAnswerPatterns());
                    numericClaimsGrounded = numericClaimsGrounded(answer, matches);
                    boolean passed = answerStatus == EvaluationCaseResult.AnswerStatus.GENERATED
                            && numericClaimsGrounded
                            && (supported
                                ? expectedSourceInAnswer && answerPhrasePresent && noForbiddenClaim
                                : acknowledgesMissing && noForbiddenClaim);
                    groundingStatus = passed
                            ? EvaluationCaseResult.GroundingStatus.PASS
                            : EvaluationCaseResult.GroundingStatus.FAIL;
                    if (!supported && passed) unsupportedHandled++;
                    if (answerStatus == EvaluationCaseResult.AnswerStatus.FAILED) answerFailures++;
                } catch (RuntimeException exception) {
                    answerStatus = EvaluationCaseResult.AnswerStatus.FAILED;
                    groundingStatus = EvaluationCaseResult.GroundingStatus.FAIL;
                    answerFailure = exception.getClass().getSimpleName();
                    answerFailures++;
                }
            }

            caseResults.add(new EvaluationCaseResult(
                    testCase.id(), testCase.question(), testCase.category(), testCase.expectedBehavior(),
                    testCase.expectedSourceDocuments(), testCase.expectedDocumentIds(), retrievedSources,
                    retrievalSucceeded, retrievalFailure,
                    matchedRank, matchedDistance, answerStatus, groundingStatus, expectedSourceInAnswer,
                    answerPhrasePresent, acknowledgesMissing, noForbiddenClaim, numericClaimsGrounded,
                    answer, answerFailure));
            if (!retrievalSucceeded || (supported && matchedRank == null)) {
                failedRetrievalCases.add(caseResults.getLast());
            }
        }

        double denominator = supportedCount;
        Map<String, CategoryEvaluationResult> perCategory = buildCategoryResults(caseResults);
        return new RagEvaluationReport(
                includeGeneration ? "retrieval+generation" : "retrieval",
                dataset.size(), supportedCount, unsupportedCount,
                ratio(recall1Hits, denominator), ratio(recall3Hits, denominator), ratio(recall5Hits, denominator),
                retrievalFailures, unsupportedHandled, answerFailures, List.copyOf(caseResults),
                perCategory, List.copyOf(failedRetrievalCases));
    }

    private static Map<String, CategoryEvaluationResult> buildCategoryResults(List<EvaluationCaseResult> cases) {
        Map<String, List<EvaluationCaseResult>> grouped = new TreeMap<>();
        for (EvaluationCaseResult result : cases) {
            grouped.computeIfAbsent(result.category(), ignored -> new ArrayList<>()).add(result);
        }
        Map<String, CategoryEvaluationResult> summaries = new TreeMap<>();
        grouped.forEach((category, categoryCases) -> {
            List<EvaluationCaseResult> supported = categoryCases.stream()
                    .filter(result -> result.expectedBehavior() == ExpectedBehavior.ANSWER_FROM_KNOWLEDGE)
                    .toList();
            int top1 = (int) supported.stream().filter(result -> result.matchedRank() != null
                    && result.matchedRank() == 1).count();
            int top3 = (int) supported.stream().filter(result -> result.matchedRank() != null
                    && result.matchedRank() <= 3).count();
            int failures = (int) categoryCases.stream().filter(result -> !result.retrievalSucceeded()
                    || (result.expectedBehavior() == ExpectedBehavior.ANSWER_FROM_KNOWLEDGE
                    && result.matchedRank() == null)).count();
            summaries.put(category, new CategoryEvaluationResult(categoryCases.size(), supported.size(),
                    ratio(top1, supported.size()), ratio(top3, supported.size()), failures));
        });
        return Collections.unmodifiableMap(summaries);
    }

    private static Integer findExpectedRank(RagEvaluationCase testCase, List<KnowledgeSearchMatch> matches) {
        for (int index = 0; index < matches.size(); index++) {
            if (testCase.expectedDocumentIds().contains(matches.get(index).documentId())) return index + 1;
        }
        return null;
    }

    private static boolean containsAny(String text, List<String> phrases) {
        if (text == null || phrases.isEmpty()) return false;
        String normalized = text.toLowerCase(Locale.ROOT);
        return phrases.stream().filter(phrase -> phrase != null && !phrase.isBlank())
                .map(phrase -> phrase.toLowerCase(Locale.ROOT)).anyMatch(phrase -> containsPhrase(normalized, phrase));
    }

    private static boolean containsPhrase(String text, String phrase) {
        int start = text.indexOf(phrase);
        while (start >= 0) {
            int end = start + phrase.length();
            boolean leftBoundary = !Character.isLetterOrDigit(phrase.charAt(0))
                    || start == 0 || !Character.isLetterOrDigit(text.charAt(start - 1));
            boolean rightBoundary = !Character.isLetterOrDigit(phrase.charAt(phrase.length() - 1))
                    || end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            start = text.indexOf(phrase, start + 1);
        }
        return false;
    }

    private static boolean matchesNoForbiddenPattern(String answer, List<String> forbiddenPatterns) {
        if (answer == null) return false;
        return forbiddenPatterns.stream().noneMatch(expression -> Pattern.compile(expression).matcher(answer).find());
    }

    private static boolean numericClaimsGrounded(String answer, List<KnowledgeSearchMatch> matches) {
        if (answer == null) return false;
        String context = matches.stream().map(KnowledgeSearchMatch::content).reduce("", (left, right) -> left + " " + right);
        Matcher matcher = DIGITS.matcher(SOURCE_ORDINAL.matcher(answer).replaceAll(""));
        while (matcher.find()) {
            if (!containsDigitToken(context, matcher.group())) return false;
        }
        return true;
    }

    private static boolean containsDigitToken(String text, String number) {
        Matcher matcher = DIGITS.matcher(text);
        while (matcher.find()) {
            if (matcher.group().equals(number)) return true;
        }
        return false;
    }

    private static double ratio(int numerator, double denominator) {
        return denominator == 0 ? 0.0 : numerator / denominator;
    }
}
