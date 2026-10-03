package com.molarai.service;

import com.molarai.dto.GroundedAnswerResponse;
import com.molarai.ai.LlmAnswerValidationException;
import com.molarai.ai.LlmService;
import com.molarai.model.GroundedAnswerSource;
import com.molarai.model.KnowledgeSearchMatch;
import com.molarai.performance.PerformanceTiming;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GroundedResponseService {
    private static final int RETRIEVAL_TOP_K = 3;

    private final KnowledgeRetrievalService retrievalService;
    private final GroundedPromptBuilder promptBuilder;
    private final AppointmentAvailabilityResponder appointmentResponder;
    private final ClinicCalendarResponder calendarResponder;
    private final AppointmentAvailabilityIntentRouter intentRouter;
    private final LlmService faqLlmService;
    private final PerformanceTiming timing;

    @Autowired
    public GroundedResponseService(
            KnowledgeRetrievalService retrievalService,
            GroundedPromptBuilder promptBuilder,
            AppointmentAvailabilityResponder appointmentResponder,
            ClinicCalendarResponder calendarResponder,
            AppointmentAvailabilityIntentRouter intentRouter,
            LlmService faqLlmService,
            PerformanceTiming timing) {
        this.retrievalService = retrievalService;
        this.promptBuilder = promptBuilder;
        this.appointmentResponder = appointmentResponder;
        this.calendarResponder = calendarResponder;
        this.intentRouter = intentRouter;
        this.faqLlmService = faqLlmService;
        this.timing = timing;
    }

    GroundedResponseService(
            KnowledgeRetrievalService retrievalService,
            GroundedPromptBuilder promptBuilder,
            AppointmentAvailabilityResponder appointmentResponder,
            ClinicCalendarResponder calendarResponder,
            AppointmentAvailabilityIntentRouter intentRouter,
            LlmService faqLlmService) {
        this(retrievalService, promptBuilder, appointmentResponder, calendarResponder, intentRouter, faqLlmService,
                new PerformanceTiming());
    }

    public GroundedAnswerResponse answer(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }

        String normalizedQuery = query.trim();
        QuestionIntent intent;
        try (var ignored = timing.stage("intent_classification")) {
            intent = intentRouter.classify(normalizedQuery);
        }
        if (intent == QuestionIntent.APPOINTMENT_AVAILABILITY || intent == QuestionIntent.EXACT_SLOT_AVAILABILITY) {
            timing.event("llm_path_selected", "path", intent.name().toLowerCase(java.util.Locale.ROOT));
            return new GroundedAnswerResponse(appointmentResponder.respond(normalizedQuery), List.of(), "live_availability");
        }
        if (intent == QuestionIntent.DATE_OR_WEEKDAY_QUERY) {
            timing.event("llm_path_selected", "path", "date_or_weekday");
            return new GroundedAnswerResponse(calendarResponder.describeDate(normalizedQuery), List.of(), "calendar");
        }
        if (intent == QuestionIntent.CLINIC_HOURS) {
            timing.event("llm_path_selected", "path", "clinic_hours");
            return new GroundedAnswerResponse(calendarResponder.describeHours(normalizedQuery), List.of(), "clinic_hours");
        }

        List<KnowledgeSearchMatch> sources = retrievalService.search(normalizedQuery, RETRIEVAL_TOP_K);
        String userPrompt;
        try (var ignored = timing.stage("prompt_construction")) {
            userPrompt = promptBuilder.buildUserPrompt(normalizedQuery, sources);
        }
        timing.event("prompt_ready", "source_count", Integer.toString(sources.size()));
        timing.event("prompt_ready", "prompt_chars", Integer.toString(userPrompt.length()));
        timing.event("llm_path_selected", "path", "faq");
        String answer;
        try {
            answer = faqLlmService.generate(promptBuilder.faqSystemPrompt(), userPrompt);
        } catch (LlmAnswerValidationException exception) {
            // Fail closed. A second generation would add another full model round,
            // and rewriting the failure as HTTP 200 would hide an invalid answer.
            timing.event("faq_answer_rejected", "reason", exception.category());
            throw exception;
        }
        return new GroundedAnswerResponse(answer,
                sources.stream().map(GroundedAnswerSource::from).toList(), "knowledge_base");
    }
}
