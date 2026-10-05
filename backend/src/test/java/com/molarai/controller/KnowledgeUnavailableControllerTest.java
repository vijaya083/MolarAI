package com.molarai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.molarai.ai.DisabledEmbeddingService;
import com.molarai.ai.LlmService;
import com.molarai.performance.PerformanceTiming;
import com.molarai.repository.KnowledgeChunkRepository;
import com.molarai.service.AppointmentAvailabilityResponder;
import com.molarai.service.AppointmentAvailabilityIntentRouter;
import com.molarai.service.ClinicCalendarResponder;
import com.molarai.service.GroundedPromptBuilder;
import com.molarai.service.GroundedResponseService;
import com.molarai.service.KnowledgeRetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeUnavailableControllerTest {
    @Test
    void ragChatReturnsStructured503WithoutVectorSearchOrLlmFallback() throws Exception {
        KnowledgeChunkRepository repository = mock(KnowledgeChunkRepository.class);
        DisabledEmbeddingService embeddings = org.mockito.Mockito.spy(new DisabledEmbeddingService());
        LlmService llm = mock(LlmService.class);
        GroundedResponseService responseService = new GroundedResponseService(
                new KnowledgeRetrievalService(embeddings, repository, new PerformanceTiming()),
                new GroundedPromptBuilder(new ObjectMapper()),
                mock(AppointmentAvailabilityResponder.class),
                mock(ClinicCalendarResponder.class),
                new AppointmentAvailabilityIntentRouter(),
                llm,
                new PerformanceTiming());
        GroundedAnswerController controller = new GroundedAnswerController(responseService, new PerformanceTiming());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        var result = mvc.perform(post("/api/knowledge/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"Do you accept Aetna insurance?"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error").value("Knowledge-based answers are temporarily unavailable."))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("DisabledEmbeddingService"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("KnowledgeUnavailableException"));
        verify(embeddings).embedAll(java.util.List.of("Do you accept Aetna insurance?"));
        verifyNoInteractions(repository, llm);
    }

    @Test
    void knowledgeSearchReturnsStructured503WithoutQueryingStoredVectors() throws Exception {
        KnowledgeChunkRepository repository = mock(KnowledgeChunkRepository.class);
        KnowledgeSearchController controller = new KnowledgeSearchController(
                new KnowledgeRetrievalService(new DisabledEmbeddingService(), repository, new PerformanceTiming()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(post("/api/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"clinic services","topK":3}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error").value("Knowledge-based answers are temporarily unavailable."));

        verifyNoInteractions(repository);
    }
}
