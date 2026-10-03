package com.molarai.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import com.molarai.service.AppointmentToolCallingOrchestrator;
import com.molarai.tool.AppointmentAvailabilityTool;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.mockito.Mockito.mock;
import org.springframework.http.HttpStatus;

class OllamaChatServiceTest {
    @Test
    void discardsTruncatedNoToolResponseAndDoesNotRetryAutomatically() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andExpect(jsonPath("$.options.num_predict").value(256))
                .andExpect(jsonPath("$.format").doesNotExist())
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant",
                         "content":"A truncated answer that must never be returned.",
                         "thinking":"Private reasoning."},"done":true,"finish_reason":"length"}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        LlmOutputLimitException exception = assertThrows(LlmOutputLimitException.class,
                () -> service.chatWithTools("Use retrieved clinic facts.",
                        List.of(LlmChatMessage.user("Do you accept Aetna?")),
                        List.of(new LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of()))));

        assertEquals("The assistant could not complete that response", exception.getMessage());
        server.verify(); // Only the single initial LLM request was made.
    }

    @Test
    void rejectsOverlongCompletedAnswerWithoutTruncatingIt() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String overlong = String.join(" ", java.util.Collections.nCopies(51, "word"));
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andRespond(withSuccess(new ObjectMapper().writeValueAsString(Map.of(
                        "model", "gpt-oss:120b-cloud",
                        "message", Map.of("role", "assistant", "content", overlong),
                        "done", true, "done_reason", "stop")), MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        LlmProviderException exception = assertThrows(LlmProviderException.class,
                () -> service.chatWithTools("system", List.of(LlmChatMessage.user("question")),
                        List.of(new LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of()))));

        assertEquals("Ollama returned an overlong customer-facing answer", exception.getMessage());
        server.verify();
    }

    @Test
    void appointmentFlowRejectsPlainContentWithoutAToolCallAfterOneRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andExpect(jsonPath("$.model").value("gpt-oss:120b-cloud"))
                .andExpect(jsonPath("$.tools[0].function.name").value("get_available_appointment_slots"))
                .andExpect(jsonPath("$.format").doesNotExist())
                .andRespond(withSuccess("""
                        {
                          "model":"gpt-oss:120b-cloud",
                          "created_at":"2026-10-01T06:45:00Z",
                          "message":{
                            "role":"assistant",
                            "content":"The clinic is out of network with Aetna.",
                            "thinking":"Private reasoning must never be returned."
                          },
                          "done":true,
                          "done_reason":"stop",
                          "total_duration":28500000000,
                          "prompt_eval_count":932,
                          "eval_count":14
                        }
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService llm = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());
        AppointmentAvailabilityTool appointmentTool = new AppointmentAvailabilityTool(
                mock(com.molarai.service.AppointmentAvailabilityService.class), new ObjectMapper());
        AppointmentToolCallingOrchestrator orchestrator = new AppointmentToolCallingOrchestrator(
                llm, appointmentTool, Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC));

        LlmProviderException exception = assertThrows(LlmProviderException.class, () -> orchestrator.answer(
                "Answer from clinic sources only.", "Do you accept Aetna insurance?"));

        assertEquals("Local language model did not request appointment availability", exception.getMessage());
        assertFalse(exception.getMessage().contains("Private reasoning"));
        server.verify(); // Exactly one request: no second generate() call occurred.
    }

    @Test
    void acceptsAJsonStringScalarInMessageContentAsValidatedCustomerText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant",
                         "content":"\\\"The clinic is out of network with Aetna.\\\"",
                         "thinking":"Private reasoning."},"done":true,"done_reason":"stop"}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        var result = service.chatWithTools("Ground in retrieved clinic facts.",
                List.of(LlmChatMessage.user("Do you accept Aetna insurance?")),
                List.of(new LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of())));

        assertEquals("The clinic is out of network with Aetna.", result.content());
        assertEquals(0, result.toolCalls().size());
        server.verify();
    }

    @Test
    void sendsNonStreamingChatRequestToConfiguredModelAndBaseUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer unit-test-token"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andExpect(content().json("""
                        {
                          "model":"gpt-oss:120b-cloud",
                          "messages":[
                            {"role":"system","content":"Use clinic context only."},
                            {"role":"user","content":"Are you open today?"}
                          ],
                          "stream":false,
                          "think":false,
                          "options":{"num_predict":128},
                          "format":{"type":"object","properties":{"answer":{"type":"string","description":"A direct, concise 1 to 3 sentence response to the user, with no reasoning or source analysis."}},"required":["answer"],"additionalProperties":false}
                        }
                        """))
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant","content":"{\\"answer\\":\\"The clinic is open today.\\"}","thinking":"First, I need to review the clinic context. This is private reasoning."},"done":true}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        assertEquals("The clinic is open today.", service.generate("Use clinic context only.", "Are you open today?"));
        server.verify();
    }

    @Test
    void directFaqGenerationFailsOnTruncationWithoutRetryingOrExposingPartialContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andExpect(jsonPath("$.options.num_predict").value(128))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant",
                         "content":"A partial answer that must not reach the user.",
                         "thinking":"Private reasoning."},"done":true,"finish_reason":"length"}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        LlmOutputLimitException exception = assertThrows(LlmOutputLimitException.class,
                () -> service.generate("Use retrieved facts only.", "Do you accept Aetna?"));

        assertEquals("The assistant could not complete that response", exception.getMessage());
        server.verify(); // One FAQ request, no automatic recovery request.
    }

    @Test
    void returnsSafeFailureWithoutExposingProviderResponseBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("internal provider detail"));
        OllamaChatService service = new OllamaChatService(builder, "http://localhost:11434/api", "qwen3:4b", "", new ObjectMapper());

        LlmProviderException exception = assertThrows(LlmProviderException.class,
                () -> service.generate("system", "question"));

        assertEquals("Ollama chat request failed", exception.getMessage());
        assertFalse(exception.getMessage().contains("internal provider detail"));
        server.verify();
    }

    @Test
    void rejectsIncompleteResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andRespond(withSuccess("{\"model\":\"qwen3:4b\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}", MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "http://localhost:11434/api", "qwen3:4b", "", new ObjectMapper());

        assertThrows(LlmProviderException.class, () -> service.generate("system", "question"));
        server.verify();
    }

    @Test
    void validatesInitialCustomerAnswerWithoutConstrainingNativeToolSelectionOrLeakingThinking() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andExpect(jsonPath("$.tools").exists())
                .andExpect(jsonPath("$.format").doesNotExist())
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant",
                         "content":"Yes, appointments are available on June 10.",
                         "thinking":"Private chain of thought."}}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        var result = service.chatWithTools("system", List.of(LlmChatMessage.user("question")),
                List.of(new LlmToolDefinition("test_tool", "test", Map.of())));

        assertEquals("Yes, appointments are available on June 10.", result.content());
        assertFalse(result.content().contains("Private chain of thought"));
        assertEquals(0, result.toolCalls().size());
        server.verify();
    }

    @Test
    void rejectsToolCallNarrationWhenNoNativeToolCallWasEmitted() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andExpect(jsonPath("$.format").doesNotExist())
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant",
                         "content":"I must call get_available_appointment_slots to check that date."}}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        LlmProviderException exception = assertThrows(LlmProviderException.class,
                () -> service.chatWithTools("system", List.of(LlmChatMessage.user("Any slots?")),
                        List.of(new LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of()))));

        assertEquals("Ollama returned non-customer-facing content", exception.getMessage());
        server.verify();
    }

    @Test
    void recoversOneStructuredToolCallFromContentWithoutASecondRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant",
                         "content":"{\\"name\\":\\"get_available_appointment_slots\\",\\"arguments\\":{\\"date\\":\\"2030-06-10\\"}}",
                         "thinking":"Private reasoning."},"done":true,"done_reason":"stop"}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        var result = service.chatWithTools("system", List.of(LlmChatMessage.user("Any slots on June 10, 2030?")),
                List.of(new LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of())));

        assertEquals(1, result.toolCalls().size());
        assertEquals("get_available_appointment_slots", result.toolCalls().getFirst().name());
        assertEquals("2030-06-10", result.toolCalls().getFirst().arguments().path("date").asText());
        assertEquals("", result.content());
        server.verify();
    }

    @Test
    void normalizesStringEncodedNativeToolArguments() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant","content":"",
                         "tool_calls":[{"function":{"name":"get_available_appointment_slots",
                          "arguments":"{\\"date\\":\\"2030-06-10\\"}"}}]},"done":true,"done_reason":"tool_calls"}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        var result = service.chatWithTools("system", List.of(LlmChatMessage.user("Any slots?")),
                List.of(new LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of())));

        assertEquals("2030-06-10", result.toolCalls().getFirst().arguments().path("date").asText());
        server.verify();
    }

    @Test
    void providerTimeoutFailsOnceWithoutRetryOrLeakingTheCause() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andRespond(withException(new java.net.SocketTimeoutException("read timed out talking to provider")));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        LlmProviderException exception = assertThrows(LlmProviderException.class,
                () -> service.generate("system", "Do you accept Aetna?"));

        assertEquals("Ollama chat request failed", exception.getMessage());
        assertFalse(exception.getMessage().contains("timed out"));
        server.verify();
    }

    @Test
    void rejectsReasoningStyleNarrationOnInitialNoToolResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant",
                         "content":"Okay, let's tackle this. First, I need to check the clinic context."}}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        LlmProviderException exception = assertThrows(LlmProviderException.class,
                () -> service.chatWithTools("system", List.of(LlmChatMessage.user("Do you accept Aetna?")),
                        List.of(new LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of()))));

        assertEquals("Ollama returned non-customer-facing content", exception.getMessage());
        server.verify();
    }

    @Test
    void rejectsClinicRecordExplanationsInsteadOfPresentingThemAsAvailabilityAnswers() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant",
                         "content":"The availability information for October 2, 2026 is not available in the clinic records."}}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        LlmProviderException exception = assertThrows(LlmProviderException.class,
                () -> service.chatWithTools("system", List.of(LlmChatMessage.user("Is there a slot on Oct 2?")),
                        List.of(new LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of()))));

        assertEquals("Ollama returned non-customer-facing content", exception.getMessage());
        server.verify();
    }

    @Test
    void rejectsEmptyAndMalformedInitialNoToolResponses() throws Exception {
        for (String content : List.of("", "{\"answer\":\"unfinished")) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://ollama.com/api/chat"))
                    .andRespond(withSuccess(new ObjectMapper().writeValueAsString(Map.of(
                            "model", "gpt-oss:120b-cloud",
                            "message", Map.of("role", "assistant", "content", content))),
                            MediaType.APPLICATION_JSON));
            OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                    "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());
            assertThrows(LlmProviderException.class,
                    () -> service.chatWithTools("system", List.of(LlmChatMessage.user("question")),
                            List.of(new LlmToolDefinition("test_tool", "test", Map.of()))));
            server.verify();
        }
    }

    @Test
    void sendsStrictToolSchemaAndMapsOllamaToolCall() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer unit-test-token"))
                .andExpect(content().json("""
                        {
                          "model":"gpt-oss:120b-cloud",
                          "messages":[
                            {"role":"system","content":"Use tools when live availability is requested."},
                            {"role":"user","content":"Any slots on June 10?"}
                          ],
                          "stream":false,
                          "tools":[{
                            "type":"function",
                            "function":{"name":"get_available_appointment_slots","description":"Find slots.",
                              "parameters":{"type":"object","properties":{"date":{"type":"string","format":"date"}},
                                "required":["date"],"additionalProperties":false}}
                          }]
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant","content":"The user is asking about appointment availability. First, I need to check the schedule.","thinking":"Internal analysis must never be forwarded.","tool_calls":[
                          {"function":{"name":"get_available_appointment_slots","arguments":{"date":"2030-06-10"}}}
                        ]},"done":true}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());
        JsonNode schema = new ObjectMapper().readTree("""
                {"type":"object","properties":{"date":{"type":"string","format":"date"}},
                 "required":["date"],"additionalProperties":false}
                """);

        var result = service.chatWithTools("Use tools when live availability is requested.",
                List.of(com.molarai.ai.LlmChatMessage.user("Any slots on June 10?")),
                List.of(new com.molarai.ai.LlmToolDefinition("get_available_appointment_slots", "Find slots.",
                        new ObjectMapper().convertValue(schema, Map.class))));

        assertEquals("get_available_appointment_slots", result.toolCalls().getFirst().name());
        assertEquals("2030-06-10", result.toolCalls().getFirst().arguments().get("date").asText());
        assertEquals("", result.content());
        server.verify();
    }

    @Test
    void serializesToolCallHistoryAndToolResultForFinalAssistantTurn() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andExpect(header("Authorization", "Bearer unit-test-token"))
                .andExpect(content().json("""
                        {"messages":[
                          {"role":"system","content":"system"},
                          {"role":"user","content":"question"},
                          {"role":"assistant","content":"","tool_calls":[
                            {"function":{"name":"get_available_appointment_slots","arguments":{"date":"2030-06-10"}}}
                          ]},
                          {"role":"tool","tool_name":"get_available_appointment_slots","content":"{\\"slots\\":[]}"}
                        ]}
                        """, false))
                .andExpect(jsonPath("$.format.required[0]").value("answer"))
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant","content":"{\\"answer\\":\\"No appointments are open.\\"}","thinking":"First, I need to analyze the tool result. The user asked if appointments are available."}}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());
        JsonNode arguments;
        try {
            arguments = new ObjectMapper().readTree("{\"date\":\"2030-06-10\"}");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }

        var response = service.chatWithTools("system", List.of(
                com.molarai.ai.LlmChatMessage.user("question"),
                com.molarai.ai.LlmChatMessage.assistant("", List.of(new com.molarai.ai.LlmToolCall(
                        "call-1", "get_available_appointment_slots", arguments))),
                com.molarai.ai.LlmChatMessage.tool("get_available_appointment_slots", "{\"slots\":[]}")),
                List.of(new com.molarai.ai.LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of())));

        assertEquals("No appointments are open.", response.content());
        assertFalse(response.content().contains("First, I need to"));
        assertEquals(0, response.toolCalls().size());
        server.verify();
    }

    @Test
    void acceptsPlainTextAsFinalAnswerAfterToolResultAndNeverIncludesThinking() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andExpect(jsonPath("$.format.required[0]").value("answer"))
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant",
                         "content":"Yes, appointments are available on June 10, 2030 at 9:00–9:30 AM.",
                         "thinking":"First I need to inspect the tool result and reason about the answer."}}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        var result = service.chatWithTools("system", List.of(
                        LlmChatMessage.user("Any appointments on June 10, 2030?"),
                        LlmChatMessage.tool("get_available_appointment_slots", "{\"slots\":[{\"startTime\":\"09:00\"}]}")),
                List.of(new LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of())));

        assertEquals("Yes, appointments are available on June 10, 2030 at 9:00–9:30 AM.", result.content());
        assertFalse(result.content().contains("First I need"));
        assertEquals(0, result.toolCalls().size());
        server.verify();
    }

    @Test
    void rejectsEmptyFinalContentAfterToolResult() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant","content":"",
                         "thinking":"Private reasoning is not an answer."}}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        LlmProviderException exception = assertThrows(LlmProviderException.class,
                () -> service.chatWithTools("system", List.of(
                                LlmChatMessage.user("question"),
                                LlmChatMessage.tool("get_available_appointment_slots", "{\"slots\":[]}")),
                        List.of(new LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of()))));

        assertEquals("Ollama returned no customer-facing answer", exception.getMessage());
        server.verify();
    }

    @Test
    void rejectsMalformedJsonInsteadOfTreatingItAsPlainText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant",
                         "content":"{\\\"answer\\\":\\\"Yes, appointments are available"}}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        LlmProviderException exception = assertThrows(LlmProviderException.class,
                () -> service.chatWithTools("system", List.of(
                                LlmChatMessage.user("question"),
                                LlmChatMessage.tool("get_available_appointment_slots", "{\"slots\":[]}")),
                        List.of(new LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of()))));

        assertEquals("Ollama returned an invalid customer-answer format", exception.getMessage());
        server.verify();
    }

    @Test
    void rejectsPlainTextToolNarrationOnFinalAnswerPath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ollama.com/api/chat"))
                .andRespond(withSuccess("""
                        {"model":"gpt-oss:120b-cloud","message":{"role":"assistant",
                         "content":"I must call get_available_appointment_slots to check that date."}}
                        """, MediaType.APPLICATION_JSON));
        OllamaChatService service = new OllamaChatService(builder, "https://ollama.com/api",
                "gpt-oss:120b-cloud", "unit-test-token", new ObjectMapper());

        LlmProviderException exception = assertThrows(LlmProviderException.class,
                () -> service.chatWithTools("system", List.of(
                                LlmChatMessage.user("question"),
                                LlmChatMessage.tool("get_available_appointment_slots", "{\"slots\":[]}")),
                        List.of(new LlmToolDefinition("get_available_appointment_slots", "Find slots.", Map.of()))));

        assertEquals("Ollama returned non-customer-facing content", exception.getMessage());
        server.verify();
    }
}
