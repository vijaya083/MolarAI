package com.molarai.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.molarai.ai.LlmToolDefinition;
import com.molarai.model.AppointmentSlot;
import com.molarai.service.AppointmentAvailabilityService;
import com.molarai.service.AppointmentDateInPastException;
import com.molarai.service.InvalidAppointmentRequestException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AppointmentAvailabilityTool {
    public static final String NAME = "get_available_appointment_slots";
    private static final String SAFE_ERROR = "Appointment availability could not be checked. Ask the user to try again.";
    private static final String INVALID_INPUT = "Appointment search needs a valid date and, when supplied, both from and to times on that date.";
    private static final Set<String> ALLOWED_FIELDS = Set.of("date", "from", "to");

    private final AppointmentAvailabilityService appointmentService;
    private final ObjectMapper objectMapper;

    public AppointmentAvailabilityTool(AppointmentAvailabilityService appointmentService, ObjectMapper objectMapper) {
        this.appointmentService = appointmentService;
        this.objectMapper = objectMapper;
    }

    public LlmToolDefinition definition() {
        return new LlmToolDefinition(NAME,
                "Find available dental appointment slots at MolarAI Dental Studio for a requested date and optional time range.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "date", Map.of("type", "string", "format", "date",
                                        "description", "Requested local date in YYYY-MM-DD format."),
                                "from", Map.of("type", "string", "format", "date-time",
                                        "description", "Optional inclusive ISO local date-time lower bound."),
                                "to", Map.of("type", "string", "format", "date-time",
                                        "description", "Optional exclusive ISO local date-time upper bound.")),
                        "required", List.of("date"),
                        "additionalProperties", false));
    }

    public String execute(JsonNode arguments) {
        try {
            validateShape(arguments);
            LocalDate date = LocalDate.parse(arguments.get("date").textValue());
            JsonNode fromNode = arguments.get("from");
            JsonNode toNode = arguments.get("to");
            List<AppointmentSlot> slots;
            if (fromNode == null && toNode == null) {
                slots = appointmentService.availableOn(date);
            } else {
                if (fromNode == null || toNode == null || !fromNode.isTextual() || !toNode.isTextual()) {
                    return errorResult(INVALID_INPUT);
                }
                LocalDateTime from = LocalDateTime.parse(fromNode.textValue());
                LocalDateTime to = LocalDateTime.parse(toNode.textValue());
                if (!from.toLocalDate().equals(date) || !to.toLocalDate().equals(date) || !from.isBefore(to)) {
                    return errorResult(INVALID_INPUT);
                }
                slots = appointmentService.availableBetween(from, to);
            }
            return objectMapper.writeValueAsString(Map.of("slots", slots.stream()
                    .map(slot -> new AvailableSlot(
                            slot.date().toString(), slot.startTime().toString(), slot.endTime().toString(), slot.provider()))
                    .toList()));
        } catch (AppointmentDateInPastException exception) {
            throw exception;
        } catch (DateTimeParseException | InvalidAppointmentRequestException exception) {
            return errorResult(INVALID_INPUT);
        } catch (RuntimeException | JsonProcessingException exception) {
            return errorResult(SAFE_ERROR);
        }
    }

    private void validateShape(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()
                || !arguments.hasNonNull("date") || !arguments.get("date").isTextual()) {
            throw new InvalidAppointmentRequestException("invalid tool arguments");
        }
        arguments.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED_FIELDS.contains(field)) throw new InvalidAppointmentRequestException("unknown tool argument");
        });
    }

    private String errorResult(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message, "slots", List.of()));
        } catch (JsonProcessingException exception) {
            return "{\"error\":\"Appointment availability could not be checked.\",\"slots\":[]}";
        }
    }

    private record AvailableSlot(
            String date,
            String startTime,
            String endTime,
            String provider) {
    }
}
