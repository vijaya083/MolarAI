package com.molarai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.molarai.ai.LlmChatMessage;
import com.molarai.ai.LlmChatResult;
import com.molarai.ai.LlmProviderException;
import com.molarai.ai.LlmToolCall;
import com.molarai.ai.ToolCallingLlmService;
import com.molarai.model.AppointmentSlot;
import com.molarai.service.AppointmentAvailabilityService;
import com.molarai.service.AppointmentToolCallingOrchestrator;
import com.molarai.repository.AppointmentSlotRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppointmentAvailabilityToolTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishesStrictSmallSchemaAndRejectsInvalidInput() throws Exception {
        AppointmentAvailabilityService service = mock(AppointmentAvailabilityService.class);
        AppointmentAvailabilityTool tool = new AppointmentAvailabilityTool(service, objectMapper);

        var definition = tool.definition();
        assertEquals(AppointmentAvailabilityTool.NAME, definition.name());
        assertEquals(List.of("date"), definition.parameters().get("required"));
        assertEquals(false, definition.parameters().get("additionalProperties"));
        assertTrue(((Map<?, ?>) definition.parameters().get("properties")).keySet()
                .containsAll(List.of("date", "from", "to")));

        String error = tool.execute(objectMapper.readTree("{\"date\":\"not-a-date\",\"extra\":true}"));
        assertTrue(error.contains("error"));
        verify(service, never()).availableOn(org.mockito.ArgumentMatchers.any());
        verify(service, never()).availableBetween(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void delegatesDateAndRangeQueriesAndReturnsOnlySafeSlotFields() throws Exception {
        AppointmentAvailabilityService service = mock(AppointmentAvailabilityService.class);
        AppointmentAvailabilityTool tool = new AppointmentAvailabilityTool(service, objectMapper);
        AppointmentSlot slot = new AppointmentSlot(
                java.util.UUID.randomUUID(), LocalDate.of(2030, 6, 10), LocalTime.of(9, 0), LocalTime.of(9, 30),
                AppointmentSlot.Status.AVAILABLE, "Dr. Maya Chen", "private patient", "private contact");
        when(service.availableOn(slot.date())).thenReturn(List.of(slot));

        JsonNode dateArgs = objectMapper.readTree("{\"date\":\"2030-06-10\"}");
        String dateResult = tool.execute(dateArgs);

        assertTrue(dateResult.contains("2030-06-10"));
        assertTrue(dateResult.contains("Dr. Maya Chen"));
        assertTrue(dateResult.contains("09:00"));
        assertFalse(dateResult.contains("private patient"));
        assertFalse(dateResult.contains("private contact"));
        assertFalse(dateResult.contains(slot.id().toString()));
        verify(service).availableOn(slot.date());

        when(service.availableBetween(
                java.time.LocalDateTime.parse("2030-06-10T09:00:00"),
                java.time.LocalDateTime.parse("2030-06-10T12:00:00"))).thenReturn(List.of(slot));
        tool.execute(objectMapper.readTree("""
                {"date":"2030-06-10","from":"2030-06-10T09:00:00","to":"2030-06-10T12:00:00"}
                """));
        verify(service).availableBetween(java.time.LocalDateTime.parse("2030-06-10T09:00:00"),
                java.time.LocalDateTime.parse("2030-06-10T12:00:00"));
    }

    @Test
    void toolCallIsExecutedThenItsResultIsSentBackForTheFinalAnswer() throws Exception {
        AppointmentAvailabilityService availability = mock(AppointmentAvailabilityService.class);
        when(availability.availableOn(LocalDate.of(2030, 6, 10))).thenReturn(List.of(new AppointmentSlot(
                java.util.UUID.randomUUID(), LocalDate.of(2030, 6, 10), LocalTime.of(9, 0), LocalTime.of(9, 30),
                AppointmentSlot.Status.AVAILABLE, "Dr. Maya Chen", null, null)));
        AppointmentAvailabilityTool tool = new AppointmentAvailabilityTool(availability, objectMapper);
        ToolCallingLlmService llm = mock(ToolCallingLlmService.class);
        JsonNode args = objectMapper.readTree("{\"date\":\"2030-06-10\"}");
        when(llm.chatWithTools(anyString(), anyList(), anyList())).thenReturn(
                new LlmChatResult("", List.of(new LlmToolCall("call-1", AppointmentAvailabilityTool.NAME, args))),
                new LlmChatResult("Yes, 9:00–9:30 AM is available with Dr. Maya Chen.", List.of()));
        AppointmentToolCallingOrchestrator orchestrator = orchestrator(llm, tool);

        AppointmentToolCallingOrchestrator.Answer result =
                orchestrator.answer("Ground answers in supplied sources.", "Any slots June 10, 2030?");

        assertEquals("Available on June 10, 2030: 9:00 AM–9:30 AM with Dr. Maya Chen.", result.text());
        assertTrue(result.usedAppointmentTool());
        assertFalse(result.text().contains("private"));
        org.mockito.ArgumentCaptor<List<LlmChatMessage>> history = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(llm, times(1)).chatWithTools(anyString(), history.capture(), anyList());
        verify(llm, never()).generate(anyString(), anyString());
        assertEquals(1, history.getValue().size());
        assertEquals("user", history.getValue().getFirst().role());
        verify(availability, never()).book(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
        verify(availability, never()).cancel(org.mockito.ArgumentMatchers.any());
        org.mockito.ArgumentCaptor<String> systemPrompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llm).chatWithTools(systemPrompts.capture(), anyList(), anyList());
        assertTrue(systemPrompts.getValue().contains("2030-06-01T10:00:00"));
        assertTrue(systemPrompts.getValue().contains("America/Los_Angeles"));
        assertTrue(systemPrompts.getValue().contains("you MUST emit a native"));
        assertTrue(systemPrompts.getValue().contains("Do not narrate the call"));
        assertTrue(systemPrompts.getValue().contains("none are available"));
        assertTrue(systemPrompts.getValue().contains("Never expose patient contact data"));
        assertTrue(systemPrompts.getValue().contains("do not book or cancel"));
    }

    @Test
    void emptyAvailabilityResultProducesAConciseNoSlotsAnswerFromToolData() throws Exception {
        AppointmentAvailabilityService availability = mock(AppointmentAvailabilityService.class);
        when(availability.availableOn(LocalDate.of(2026, 10, 2))).thenReturn(List.of());
        AppointmentAvailabilityTool tool = new AppointmentAvailabilityTool(availability, objectMapper);
        ToolCallingLlmService llm = mock(ToolCallingLlmService.class);
        JsonNode args = objectMapper.readTree("{\"date\":\"2026-10-02\"}");
        when(llm.chatWithTools(anyString(), anyList(), anyList())).thenReturn(
                new LlmChatResult("", List.of(new LlmToolCall("call-empty", AppointmentAvailabilityTool.NAME, args))));

        Clock clock = Clock.fixed(Instant.parse("2026-10-01T18:00:00Z"), ZoneId.of("America/Los_Angeles"));
        AppointmentToolCallingOrchestrator.Answer answer = new AppointmentToolCallingOrchestrator(llm, tool, clock)
                .answer("system", "Is there a slot available on October 2, 2026?");

        assertEquals("No appointments are available on October 2, 2026.", answer.text());
        assertTrue(answer.usedAppointmentTool());
        verify(availability).availableOn(LocalDate.of(2026, 10, 2));
        verify(llm, times(1)).chatWithTools(anyString(), anyList(), anyList());
        verify(llm, never()).generate(anyString(), anyString());
    }

    @Test
    void noToolResponseFailsClosedAfterOneCallAndDoesNotQueryAppointments() {
        AppointmentAvailabilityService availability = mock(AppointmentAvailabilityService.class);
        AppointmentAvailabilityTool tool = new AppointmentAvailabilityTool(availability, objectMapper);
        ToolCallingLlmService llm = mock(ToolCallingLlmService.class);
        when(llm.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(new LlmChatResult("The clinic is out of network.", List.of()));

        LlmProviderException exception = assertThrows(LlmProviderException.class,
                () -> orchestrator(llm, tool).answer("Use retrieved clinic facts.", "Do you accept Aetna?"));
        assertEquals("Local language model did not request appointment availability", exception.getMessage());
        verify(llm, times(1)).chatWithTools(anyString(), anyList(), anyList());
        verify(llm, never()).generate(anyString(), anyString());
        verify(availability, never()).availableOn(org.mockito.ArgumentMatchers.any());
        verify(availability, never()).availableBetween(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(availability, never()).book(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
        verify(availability, never()).cancel(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotTreatNaturalLanguageToolNarrationAsAToolCallOrReturnItToTheUser() {
        AppointmentAvailabilityService availability = mock(AppointmentAvailabilityService.class);
        AppointmentAvailabilityTool tool = new AppointmentAvailabilityTool(availability, objectMapper);
        ToolCallingLlmService llm = mock(ToolCallingLlmService.class);
        when(llm.chatWithTools(anyString(), anyList(), anyList())).thenThrow(
                new LlmProviderException("Ollama returned non-customer-facing content"));

        LlmProviderException exception = assertThrows(LlmProviderException.class,
                () -> orchestrator(llm, tool).answer("Ground answers in supplied sources.",
                        "Do you have appointments on June 10, 2030?"));

        assertEquals("Ollama returned non-customer-facing content", exception.getMessage());
        verify(llm, times(1)).chatWithTools(anyString(), anyList(), anyList());
        verify(llm, never()).generate(anyString(), anyString());
        verify(availability, never()).availableOn(org.mockito.ArgumentMatchers.any());
        verify(availability, never()).availableBetween(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pastDateToolCallReturnsApplicationErrorWithoutRepositoryQueryOrLlmOverride() throws Exception {
        AppointmentSlotRepository repository = mock(AppointmentSlotRepository.class);
        Clock applicationClock = Clock.fixed(Instant.parse("2030-06-01T17:00:00Z"),
                ZoneId.of("America/Los_Angeles"));
        AppointmentAvailabilityService availability = new AppointmentAvailabilityService(repository, applicationClock);
        AppointmentAvailabilityTool tool = new AppointmentAvailabilityTool(availability, objectMapper);
        ToolCallingLlmService llm = mock(ToolCallingLlmService.class);
        JsonNode yesterdayArgs = objectMapper.readTree("{\"date\":\"2030-05-31\"}");
        when(llm.chatWithTools(anyString(), anyList(), anyList())).thenReturn(new LlmChatResult(
                "", List.of(new LlmToolCall("call-past", AppointmentAvailabilityTool.NAME, yesterdayArgs))));

        AppointmentToolCallingOrchestrator.Answer answer =
                orchestrator(llm, tool).answer("system", "Any appointments on May 31, 2030?");

        assertEquals("May 31, 2030 has already passed. Please provide today or a future date.", answer.text());
        assertTrue(answer.usedAppointmentTool());
        verify(llm, times(1)).chatWithTools(anyString(), anyList(), anyList());
        verify(repository, never()).findAvailableForDate(org.mockito.ArgumentMatchers.any());
        verify(repository, never()).findAvailableBetween(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void safelyReturnsToolFailureToLlmWithoutLeakingExceptionDetails() throws Exception {
        AppointmentAvailabilityService availability = mock(AppointmentAvailabilityService.class);
        when(availability.availableOn(LocalDate.of(2030, 6, 10)))
                .thenThrow(new IllegalStateException("SELECT * FROM appointment_slots; secret detail"));
        AppointmentAvailabilityTool tool = new AppointmentAvailabilityTool(availability, objectMapper);
        ToolCallingLlmService llm = mock(ToolCallingLlmService.class);
        JsonNode args = objectMapper.readTree("{\"date\":\"2030-06-10\"}");
        when(llm.chatWithTools(anyString(), anyList(), anyList())).thenReturn(
                new LlmChatResult("", List.of(new LlmToolCall("call-1", AppointmentAvailabilityTool.NAME, args))));

        String answer = orchestrator(llm, tool).answer("system", "Any openings on June 10?").text();
        assertEquals("Appointment availability could not be checked. Please try again.", answer);
        assertFalse(answer.contains("SELECT"));
        assertFalse(answer.contains("secret detail"));
        verify(llm, times(1)).chatWithTools(anyString(), anyList(), anyList());
        verify(llm, never()).generate(anyString(), anyString());
    }

    @Test
    void rejectsUnknownToolAndStopsAfterMaximumToolRounds() throws Exception {
        AppointmentAvailabilityService availability = mock(AppointmentAvailabilityService.class);
        AppointmentAvailabilityTool tool = new AppointmentAvailabilityTool(availability, objectMapper);
        JsonNode args = objectMapper.readTree("{\"date\":\"2030-06-10\"}");
        ToolCallingLlmService unknownLlm = mock(ToolCallingLlmService.class);
        when(unknownLlm.chatWithTools(anyString(), anyList(), anyList())).thenReturn(new LlmChatResult(
                "", List.of(new LlmToolCall("call-1", "delete_everything", args))));
        assertThrows(LlmProviderException.class,
                () -> orchestrator(unknownLlm, tool).answer("system", "question"));

        ToolCallingLlmService loopingLlm = mock(ToolCallingLlmService.class);
        when(loopingLlm.chatWithTools(anyString(), anyList(), anyList())).thenReturn(new LlmChatResult(
                "", List.of(new LlmToolCall("call-1", AppointmentAvailabilityTool.NAME, args))));
        AppointmentToolCallingOrchestrator looping = orchestrator(loopingLlm, tool);
        assertTrue(looping.answer("system", "question").usedAppointmentTool());
        verify(loopingLlm, times(AppointmentToolCallingOrchestrator.MAX_TOOL_ROUNDS))
                .chatWithTools(anyString(), anyList(), anyList());
        verify(availability, never()).book(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
        verify(availability, never()).cancel(org.mockito.ArgumentMatchers.any());
    }

    private AppointmentToolCallingOrchestrator orchestrator(
            ToolCallingLlmService llm, AppointmentAvailabilityTool tool) {
        Clock fixedClock = Clock.fixed(Instant.parse("2030-06-01T17:00:00Z"), ZoneId.of("America/Los_Angeles"));
        return new AppointmentToolCallingOrchestrator(llm, tool, fixedClock);
    }
}
