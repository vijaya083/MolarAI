package com.molarai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.molarai.model.AppointmentSlot;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns appointment tool JSON into a short customer-facing answer without a second LLM call. */
@Component
public class AppointmentAvailabilityAnswerFormatter {
    static final int MAX_LISTED_SLOTS = 3;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final String GENERIC_FAILURE =
            "Appointment availability could not be checked. Please try again.";
    private static final String INVALID_DATE =
            "Appointment search needs a valid date. Please try again with a specific date.";

    private final ObjectMapper objectMapper;

    public AppointmentAvailabilityAnswerFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String heading(LocalDate date) {
        return DATE.format(date) + " is " + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US);
    }

    public String describe(AppointmentSlot slot) {
        String range = TIME.format(slot.startTime()) + "–" + TIME.format(slot.endTime());
        if (slot.provider() != null && !slot.provider().isBlank()) {
            return range + " with " + slot.provider();
        }
        return range;
    }

    public String formatDay(LocalDate date, List<AppointmentSlot> slots) {
        return formatListed(" on " + DATE.format(date), slots);
    }

    public String formatRange(LocalDateTime from, LocalDateTime to, List<AppointmentSlot> slots) {
        String window = " on " + DATE.format(from.toLocalDate())
                + " between " + TIME.format(from.toLocalTime()) + " and " + TIME.format(to.toLocalTime());
        return formatListed(window, slots);
    }

    public String format(JsonNode toolArguments, String toolResultJson) {
        if (toolResultJson == null || toolResultJson.isBlank()) {
            return GENERIC_FAILURE;
        }
        try {
            JsonNode root = objectMapper.readTree(toolResultJson);
            if (root == null || !root.isObject()) {
                return GENERIC_FAILURE;
            }
            if (root.hasNonNull("error") && root.get("error").isTextual()) {
                String error = root.get("error").asText();
                if (error.toLowerCase(Locale.ROOT).contains("valid date")) {
                    return INVALID_DATE;
                }
                return GENERIC_FAILURE;
            }
            JsonNode slotsNode = root.get("slots");
            if (slotsNode == null || !slotsNode.isArray() || slotsNode.isEmpty()) {
                return "No appointments are available" + dateSuffix(toolArguments) + ".";
            }
            List<String> listed = new ArrayList<>();
            int total = slotsNode.size();
            for (int index = 0; index < Math.min(total, MAX_LISTED_SLOTS); index++) {
                String slot = describeSlot(slotsNode.get(index));
                if (slot != null) listed.add(slot);
            }
            if (listed.isEmpty()) {
                return GENERIC_FAILURE;
            }
            StringBuilder answer = new StringBuilder("Available");
            answer.append(dateSuffix(toolArguments)).append(": ").append(String.join("; ", listed));
            int remaining = total - listed.size();
            if (remaining > 0) {
                answer.append("; and ").append(remaining).append(" more");
            }
            answer.append('.');
            return answer.toString();
        } catch (Exception exception) {
            return GENERIC_FAILURE;
        }
    }

    private static String formatListed(String when, List<AppointmentSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return "No appointments are available" + when + ".";
        }
        List<String> listed = new ArrayList<>();
        int total = slots.size();
        for (int index = 0; index < Math.min(total, MAX_LISTED_SLOTS); index++) {
            AppointmentSlot slot = slots.get(index);
            if (slot == null || slot.startTime() == null || slot.endTime() == null) continue;
            String range = TIME.format(slot.startTime()) + "–" + TIME.format(slot.endTime());
            if (slot.provider() != null && !slot.provider().isBlank()) {
                range = range + " with " + slot.provider();
            }
            listed.add(range);
        }
        if (listed.isEmpty()) {
            return GENERIC_FAILURE;
        }
        StringBuilder answer = new StringBuilder("Available").append(when).append(": ")
                .append(String.join("; ", listed));
        int remaining = total - listed.size();
        if (remaining > 0) {
            answer.append("; and ").append(remaining).append(" more");
        }
        return answer.append('.').toString();
    }

    private static String dateSuffix(JsonNode toolArguments) {
        if (toolArguments == null || !toolArguments.hasNonNull("date") || !toolArguments.get("date").isTextual()) {
            return "";
        }
        try {
            LocalDate date = LocalDate.parse(toolArguments.get("date").asText());
            return " on " + DATE.format(date);
        } catch (DateTimeParseException exception) {
            return "";
        }
    }

    private static String describeSlot(JsonNode slot) {
        if (slot == null || !slot.isObject()) return null;
        try {
            LocalTime start = LocalTime.parse(slot.path("startTime").asText());
            LocalTime end = LocalTime.parse(slot.path("endTime").asText());
            String provider = slot.path("provider").asText("");
            String range = TIME.format(start) + "–" + TIME.format(end);
            if (provider.isBlank()) return range;
            return range + " with " + provider;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
