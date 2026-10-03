package com.molarai.service;

import com.molarai.ai.LlmChatMessage;
import com.molarai.ai.LlmChatResult;
import com.molarai.ai.LlmProviderException;
import com.molarai.ai.LlmToolCall;
import com.molarai.ai.ToolCallingLlmService;
import com.molarai.performance.PerformanceTiming;
import org.springframework.beans.factory.annotation.Autowired;
import com.molarai.tool.AppointmentAvailabilityTool;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class AppointmentToolCallingOrchestrator {
    public static final int MAX_TOOL_ROUNDS = 1;
    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public record Answer(String text, boolean usedAppointmentTool) {
    }

    private final ToolCallingLlmService llmService;
    private final AppointmentAvailabilityTool appointmentTool;
    private final AppointmentAvailabilityAnswerFormatter answerFormatter;
    private final Clock clock;
    private final PerformanceTiming timing;

    @Autowired
    public AppointmentToolCallingOrchestrator(
            ToolCallingLlmService llmService,
            AppointmentAvailabilityTool appointmentTool,
            AppointmentAvailabilityAnswerFormatter answerFormatter,
            Clock clock,
            PerformanceTiming timing) {
        this.llmService = llmService;
        this.appointmentTool = appointmentTool;
        this.answerFormatter = answerFormatter;
        this.clock = clock;
        this.timing = timing;
    }

    public AppointmentToolCallingOrchestrator(
            ToolCallingLlmService llmService,
            AppointmentAvailabilityTool appointmentTool,
            Clock clock) {
        this(llmService, appointmentTool, new AppointmentAvailabilityAnswerFormatter(
                new com.fasterxml.jackson.databind.ObjectMapper()), clock, new PerformanceTiming());
    }

    public Answer answer(String systemPrompt, String userPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank() || userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("System and user prompts must not be blank");
        }
        LlmChatResult result = llmService.chatWithTools(
                toolAwareSystemPrompt(systemPrompt),
                List.of(LlmChatMessage.user(userPrompt)),
                List.of(appointmentTool.definition()));
        if (result == null) throw new LlmProviderException("Local language model returned an incomplete response");
        if (result.toolCalls().isEmpty()) {
            // One tool-selection call only. Timeouts, truncation, and validation failures
            // are not retried; a missing native call is also not worth another 16–36s round.
            timing.event("appointment_tool_call_missing", "reason", "no_native_tool_call");
            throw new LlmProviderException("Local language model did not request appointment availability");
        }
        if (result.toolCalls().size() != 1) {
            throw new LlmProviderException("Local language model requested an unsupported tool call");
        }

        LlmToolCall call = result.toolCalls().getFirst();
        if (!AppointmentAvailabilityTool.NAME.equals(call.name())) {
            throw new LlmProviderException("Local language model requested an unsupported tool");
        }
        timing.increment("tool_calls");
        timing.event("tool_call_accepted", "tool", call.name());
        String toolResult;
        try {
            try (var ignored = timing.stage("appointment_tool_execution")) {
                toolResult = appointmentTool.execute(call.arguments());
            }
        } catch (AppointmentDateInPastException exception) {
            return new Answer(exception.getMessage(), true);
        }
        return new Answer(answerFormatter.format(call.arguments(), toolResult), true);
    }

    private String toolAwareSystemPrompt(String basePrompt) {
        LocalDateTime now = LocalDateTime.now(clock);
        return basePrompt + "\n\n"
                + "For actual appointment availability requests, you MUST emit a native "
                + "get_available_appointment_slots tool call. Do not narrate the call or answer from static context. "
                + "Resolve relative dates only from the application-local time below; ask for clarification by calling "
                + "the tool only when the date can be resolved. "
                + "Availability lookup is the only LLM appointment action; do not book or cancel. "
                + "Never expose patient contact data or invent availability. "
                + "If no slots are returned, the application says none are available.\n"
                + "Current application-local date/time: " + NOW_FORMAT.format(now)
                + " (" + clock.getZone().getId() + ").";
    }
}
