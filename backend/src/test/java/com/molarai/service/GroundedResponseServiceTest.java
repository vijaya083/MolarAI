package com.molarai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.molarai.dto.GroundedAnswerResponse;
import com.molarai.model.KnowledgeSearchMatch;
import com.molarai.ai.LlmService;
import com.molarai.ai.LlmAnswerValidationException;
import com.molarai.ai.DisabledEmbeddingService;
import com.molarai.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class GroundedResponseServiceTest {
    @Test
    void retrievesBuildsGroundedPromptGeneratesAnswerAndReturnsSourceMetadata() {
        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        AppointmentAvailabilityResponder appointments = mock(AppointmentAvailabilityResponder.class);
        LlmService faqLlm = mock(LlmService.class);
        GroundedPromptBuilder promptBuilder = new GroundedPromptBuilder(new ObjectMapper());
        when(faqLlm.generate(anyString(), anyString())).thenReturn("The clinic is out of network.");
        KnowledgeSearchMatch source = new KnowledgeSearchMatch(
                "insurance", "Insurance and Coverage", 0, "The clinic is out of network.",
                Map.of("sourceFilename", "03-insurance.md"), 0.2);
        when(retrievalService.search("Do you accept Aetna insurance?", 3)).thenReturn(List.of(source));
        GroundedResponseService service = service(retrievalService, promptBuilder, appointments, faqLlm);

        GroundedAnswerResponse response = service.answer(" Do you accept Aetna insurance? ");

        assertEquals("The clinic is out of network.", response.answer());
        assertEquals("knowledge_base", response.answerSource());
        assertEquals("insurance", response.sources().getFirst().documentId());
        assertEquals("Insurance and Coverage", response.sources().getFirst().documentName());
        assertEquals(Map.of("sourceFilename", "03-insurance.md"), response.sources().getFirst().metadata());
        verify(retrievalService).search("Do you accept Aetna insurance?", 3);
        verify(appointments, never()).respond(anyString());
        verify(faqLlm, times(1)).generate(eq(promptBuilder.faqSystemPrompt()), anyString());
        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(faqLlm).generate(eq(promptBuilder.faqSystemPrompt()), prompt.capture());
        assertTrue(prompt.getValue().contains("The clinic is out of network."));
        assertTrue(prompt.getValue().contains("03-insurance.md"));
    }

    @Test
    void marksToolBackedAnswersAsLiveAvailabilityAndOmitsUnrelatedKnowledgeSources() {
        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        AppointmentAvailabilityResponder appointments = mock(AppointmentAvailabilityResponder.class);
        LlmService faqLlm = mock(LlmService.class);
        GroundedPromptBuilder promptBuilder = new GroundedPromptBuilder(new ObjectMapper());
        when(appointments.respond("Any appointment slots tomorrow?")).thenReturn("No open appointments tomorrow.");

        GroundedResponseService service = service(retrievalService, promptBuilder, appointments, faqLlm);
        GroundedAnswerResponse response = service
                .answer("Any appointment slots tomorrow?");

        assertEquals("No open appointments tomorrow.", response.answer());
        assertEquals("live_availability", response.answerSource());
        assertTrue(response.sources().isEmpty());
        verify(appointments, times(1)).respond("Any appointment slots tomorrow?");
        verify(faqLlm, never()).generate(anyString(), anyString());
        verify(retrievalService, never()).search(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void slotAvailabilityOnANamedDateUsesTheAppointmentLookupNotClinicHours() {
        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        AppointmentAvailabilityResponder appointments = mock(AppointmentAvailabilityResponder.class);
        ClinicCalendarResponder calendar = mock(ClinicCalendarResponder.class);
        LlmService faqLlm = mock(LlmService.class);
        when(appointments.respond("Is slot available on September 29, 2026?"))
                .thenReturn("September 29, 2026 is Tuesday. Appointment availability could not be confirmed for that date.");
        GroundedResponseService service = new GroundedResponseService(
                retrievalService, new GroundedPromptBuilder(new ObjectMapper()), appointments, calendar,
                new AppointmentAvailabilityIntentRouter(), faqLlm);

        GroundedAnswerResponse response = service.answer("Is slot available on September 29, 2026?");

        assertEquals("live_availability", response.answerSource());
        verify(appointments).respond("Is slot available on September 29, 2026?");
        verify(faqLlm, never()).generate(anyString(), anyString());
        verify(calendar, never()).describeHours(anyString());
        verify(retrievalService, never()).search(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void clinicHoursDoNotQueryAppointmentSlotsOrCallTheLlm() {
        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        AppointmentAvailabilityResponder appointments = mock(AppointmentAvailabilityResponder.class);
        ClinicCalendarResponder calendar = mock(ClinicCalendarResponder.class);
        LlmService faqLlm = mock(LlmService.class);
        when(calendar.describeHours("What are your opening hours?")).thenReturn("Monday through Friday");
        GroundedResponseService service = new GroundedResponseService(
                retrievalService, new GroundedPromptBuilder(new ObjectMapper()), appointments, calendar,
                new AppointmentAvailabilityIntentRouter(), faqLlm);

        GroundedAnswerResponse response = service.answer("What are your opening hours?");

        assertEquals("clinic_hours", response.answerSource());
        assertEquals("Monday through Friday", response.answer());
        verify(appointments, never()).respond(anyString());
        verify(faqLlm, never()).generate(anyString(), anyString());
        verify(retrievalService, never()).search(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void overlongFaqResponseFailsClosedAfterOneLlmCall() {
        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        AppointmentAvailabilityResponder appointments = mock(AppointmentAvailabilityResponder.class);
        LlmService faqLlm = mock(LlmService.class);
        GroundedPromptBuilder promptBuilder = new GroundedPromptBuilder(new ObjectMapper());
        KnowledgeSearchMatch source = new KnowledgeSearchMatch(
                "insurance", "Insurance and Coverage", 0, "Clinic insurance information.", Map.of(), 0.1);
        when(retrievalService.search("Do you accept Aetna insurance?", 3)).thenReturn(List.of(source));
        when(faqLlm.generate(anyString(), anyString())).thenThrow(
                new LlmAnswerValidationException("answer_over_word_limit", "private validator detail"));

        LlmAnswerValidationException exception = assertThrows(LlmAnswerValidationException.class,
                () -> service(retrievalService, promptBuilder, appointments, faqLlm)
                        .answer("Do you accept Aetna insurance?"));

        assertEquals("answer_over_word_limit", exception.category());
        verify(appointments, never()).respond(anyString());
        verify(faqLlm, times(1)).generate(anyString(), anyString());
    }

    @Test
    void truncatedOrInvalidFaqAnswersAreNotConvertedIntoSuccessfulResponses() {
        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        AppointmentAvailabilityResponder appointments = mock(AppointmentAvailabilityResponder.class);
        LlmService faqLlm = mock(LlmService.class);
        GroundedPromptBuilder promptBuilder = new GroundedPromptBuilder(new ObjectMapper());
        when(retrievalService.search("Do you accept Aetna insurance?", 3)).thenReturn(List.of());
        when(faqLlm.generate(anyString(), anyString())).thenThrow(
                new LlmAnswerValidationException("internal_or_tool_narration", "private validator detail"));

        assertThrows(LlmAnswerValidationException.class, () -> service(retrievalService, promptBuilder, appointments, faqLlm)
                .answer("Do you accept Aetna insurance?"));
        verify(faqLlm, times(1)).generate(anyString(), anyString());
        verify(appointments, never()).respond(anyString());
    }

    @Test
    void unsupportedFaqDoesNotInventClinicFacts() {
        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        AppointmentAvailabilityResponder appointments = mock(AppointmentAvailabilityResponder.class);
        LlmService faqLlm = mock(LlmService.class);
        GroundedPromptBuilder promptBuilder = new GroundedPromptBuilder(new ObjectMapper());
        when(retrievalService.search("Do you offer laser whitening?", 3)).thenReturn(List.of());
        when(faqLlm.generate(anyString(), anyString())).thenReturn(
                "I don't have clinic information confirming that service.");

        GroundedAnswerResponse response = service(retrievalService, promptBuilder, appointments, faqLlm)
                .answer("Do you offer laser whitening?");

        assertEquals("I don't have clinic information confirming that service.", response.answer());
        assertTrue(response.sources().isEmpty());
        verify(appointments, never()).respond(anyString());
        verify(faqLlm, times(1)).generate(anyString(), anyString());
        verify(retrievalService).search("Do you offer laser whitening?", 3);
    }

    @Test
    void rejectsBlankQuestionBeforeRetrievalOrGeneration() {
        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        AppointmentAvailabilityResponder appointments = mock(AppointmentAvailabilityResponder.class);
        LlmService faqLlm = mock(LlmService.class);
        GroundedResponseService service = new GroundedResponseService(
                retrievalService, new GroundedPromptBuilder(new ObjectMapper()), appointments,
                mock(ClinicCalendarResponder.class), new AppointmentAvailabilityIntentRouter(), faqLlm);

        assertThrows(IllegalArgumentException.class, () -> service.answer("  "));
        verify(retrievalService, never()).search(anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(appointments, never()).respond(anyString());
    }

    @Test
    void disabledEmbeddingsRejectRagQuestionsWithoutVectorSearchOrLlmButKeepDeterministicRoutes() {
        KnowledgeChunkRepository repository = mock(KnowledgeChunkRepository.class);
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(new DisabledEmbeddingService(), repository);
        AppointmentAvailabilityResponder appointments = mock(AppointmentAvailabilityResponder.class);
        ClinicCalendarResponder calendar = mock(ClinicCalendarResponder.class);
        LlmService faqLlm = mock(LlmService.class);
        when(appointments.respond("Any appointment slots tomorrow?")).thenReturn("There are no available appointments.");
        when(appointments.respond("Is 10:30 AM available tomorrow?")).thenReturn("10:30 AM is available tomorrow.");
        when(calendar.describeHours("What are your clinic hours?")).thenReturn("The clinic is open weekdays.");
        when(calendar.describeDate("What day is June 10, 2030?")).thenReturn("June 10, 2030 is Monday.");
        GroundedResponseService service = new GroundedResponseService(
                retrieval, new GroundedPromptBuilder(new ObjectMapper()), appointments, calendar,
                new AppointmentAvailabilityIntentRouter(), faqLlm);

        assertThrows(com.molarai.service.KnowledgeUnavailableException.class,
                () -> service.answer("Do you accept Aetna insurance?"));
        assertEquals("There are no available appointments.",
                service.answer("Any appointment slots tomorrow?").answer());
        assertEquals("10:30 AM is available tomorrow.",
                service.answer("Is 10:30 AM available tomorrow?").answer());
        assertEquals("The clinic is open weekdays.",
                service.answer("What are your clinic hours?").answer());
        assertEquals("June 10, 2030 is Monday.",
                service.answer("What day is June 10, 2030?").answer());

        verifyNoInteractions(repository, faqLlm);
        verify(appointments).respond("Any appointment slots tomorrow?");
        verify(appointments).respond("Is 10:30 AM available tomorrow?");
        verify(calendar).describeHours("What are your clinic hours?");
        verify(calendar).describeDate("What day is June 10, 2030?");
    }

    private GroundedResponseService service(KnowledgeRetrievalService retrieval,
            GroundedPromptBuilder promptBuilder, AppointmentAvailabilityResponder appointments, LlmService faqLlm) {
        return new GroundedResponseService(retrieval, promptBuilder, appointments,
                mock(ClinicCalendarResponder.class), new AppointmentAvailabilityIntentRouter(), faqLlm);
    }
}
