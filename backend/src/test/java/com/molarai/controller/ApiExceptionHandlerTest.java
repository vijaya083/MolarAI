package com.molarai.controller;

import com.molarai.ai.EmbeddingProviderException;
import com.molarai.ai.LlmAnswerValidationException;
import com.molarai.ai.LlmOutputLimitException;
import com.molarai.ai.LlmProviderException;
import com.molarai.service.AppointmentSlotNotFoundException;
import com.molarai.service.AppointmentSlotUnavailableException;
import com.molarai.service.CancellationDisabledException;
import com.molarai.service.InvalidAppointmentRequestException;
import com.molarai.service.KnowledgeUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApiExceptionHandlerTest {
    @Test
    void returnsSafeGatewayErrorForProviderFailure() {
        var response = new ApiExceptionHandler().handleAiProviderFailure(
                new LlmProviderException("Local language model request failed", new IllegalStateException("private detail")));

        assertEquals(502, response.getStatusCode().value());
        assertEquals("The assistant could not complete that response. Please try again shortly.", response.getBody().error());
        assertFalse(response.getBody().error().contains("private detail"));
    }

    @Test
    void mapsTruncationValidationAndEmbeddingFailuresToTheSameSafeGatewayError() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        String safe = "The assistant could not complete that response. Please try again shortly.";

        var truncated = handler.handleAiProviderFailure(new LlmOutputLimitException("truncated provider body"));
        var invalid = handler.handleAiProviderFailure(
                new LlmAnswerValidationException("answer_over_word_limit", "private validator detail"));
        var embedding = handler.handleAiProviderFailure(new EmbeddingProviderException("embedding endpoint detail"));

        assertEquals(502, truncated.getStatusCode().value());
        assertEquals(502, invalid.getStatusCode().value());
        assertEquals(502, embedding.getStatusCode().value());
        assertEquals(safe, truncated.getBody().error());
        assertEquals(safe, invalid.getBody().error());
        assertEquals(safe, embedding.getBody().error());
        assertFalse(invalid.getBody().error().contains("private validator"));
    }

    @Test
    void mapsAppointmentErrorsToClientStatuses() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        assertEquals(404, handler.handleAppointmentSlotNotFound(new AppointmentSlotNotFoundException())
                .getStatusCode().value());
        assertEquals(409, handler.handleAppointmentSlotUnavailable(new AppointmentSlotUnavailableException())
                .getStatusCode().value());
        assertEquals(400, handler.handleInvalidAppointmentRequest(
                new InvalidAppointmentRequestException("invalid range")).getStatusCode().value());
    }

    @Test
    void mapsDisabledCancellationToConsistentForbiddenResponse() {
        var response = new ApiExceptionHandler().handleCancellationDisabled(new CancellationDisabledException());

        assertEquals(403, response.getStatusCode().value());
        assertEquals("Appointment cancellation is disabled in this demo.", response.getBody().error());
    }

    @Test
    void mapsUnavailableKnowledgeToStructuredServiceUnavailableResponse() {
        var response = new ApiExceptionHandler().handleKnowledgeUnavailable(new KnowledgeUnavailableException());

        assertEquals(503, response.getStatusCode().value());
        assertEquals("KNOWLEDGE_UNAVAILABLE", response.getBody().code());
        assertEquals("Knowledge-based answers are temporarily unavailable.", response.getBody().error());
    }
}
