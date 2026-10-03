package com.molarai.service;

import com.molarai.model.AppointmentSlot;
import com.molarai.performance.PerformanceTiming;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Answers live availability from PostgreSQL via {@link AppointmentAvailabilityService}.
 * {@code tool_calls} counts that local lookup. This path does not call a language model.
 */
@Service
public class AppointmentAvailabilityResponder {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private final AppointmentAvailabilityRequestParser parser;
    private final AppointmentAvailabilityService availabilityService;
    private final AppointmentAvailabilityAnswerFormatter formatter;
    private final ClinicSchedule schedule;
    private final PerformanceTiming timing;

    public AppointmentAvailabilityResponder(
            AppointmentAvailabilityRequestParser parser,
            AppointmentAvailabilityService availabilityService,
            AppointmentAvailabilityAnswerFormatter formatter,
            ClinicSchedule schedule,
            PerformanceTiming timing) {
        this.parser = parser;
        this.availabilityService = availabilityService;
        this.formatter = formatter;
        this.schedule = schedule;
        this.timing = timing;
    }

    public String respond(String question) {
        AppointmentAvailabilityRequestParser.Interpretation interpretation = parser.parse(question);
        if (interpretation instanceof AppointmentAvailabilityRequestParser.Clarification clarification) {
            timing.event("appointment_clarification", "reason", clarification.reason());
            return clarification.message();
        }
        timing.increment("tool_calls");
        timing.event("appointment_lookup_started", "mode", "deterministic");
        try (var ignored = timing.stage("appointment_lookup")) {
            try {
                if (interpretation instanceof AppointmentAvailabilityRequestParser.ExactTime exact) {
                    return exactSlot(exact.date(), exact.time(), availabilityService.allOn(exact.date()));
                }
                if (interpretation instanceof AppointmentAvailabilityRequestParser.Day day) {
                    return daySlots(day.date(), availabilityService.allOn(day.date()));
                }
                AppointmentAvailabilityRequestParser.Range range =
                        (AppointmentAvailabilityRequestParser.Range) interpretation;
                return rangeSlots(range, availabilityService.allOn(range.from().toLocalDate()));
            } catch (AppointmentDateInPastException exception) {
                return formatter.heading(exception.requestedDate())
                        + " and has already passed. Please provide today or a future date.";
            }
        }
    }

    private String daySlots(LocalDate date, List<AppointmentSlot> stored) {
        String heading = formatter.heading(date);
        List<AppointmentSlot> available = available(stored);
        if (stored.isEmpty()) {
            return missingRows(date, null);
        }
        if (available.isEmpty()) {
            return heading + ". " + schedule.hoursClause(date)
                    + ", but no available appointment slots were found for that date.";
        }
        return heading + ". I found these available appointment slots: " + listed(available) + ".";
    }

    private String rangeSlots(AppointmentAvailabilityRequestParser.Range range, List<AppointmentSlot> stored) {
        String heading = formatter.heading(range.from().toLocalDate());
        if (stored.isEmpty()) {
            return missingRows(range.from().toLocalDate(), null);
        }
        List<AppointmentSlot> available = available(stored).stream()
                .filter(slot -> {
                    var start = range.from().toLocalDate().atTime(slot.startTime());
                    return !start.isBefore(range.from()) && start.isBefore(range.to());
                })
                .toList();
        if (available.isEmpty()) {
            return heading + ". No available appointment slots were found between "
                    + TIME.format(range.from().toLocalTime()) + " and " + TIME.format(range.to().toLocalTime()) + ".";
        }
        return heading + ". I found these available appointment slots: " + listed(available) + ".";
    }

    private String exactSlot(LocalDate date, LocalTime time, List<AppointmentSlot> stored) {
        String heading = formatter.heading(date);
        String clock = TIME.format(time);
        if (stored.isEmpty()) {
            return missingRows(date, clock);
        }
        List<AppointmentSlot> matches = stored.stream().filter(slot -> time.equals(slot.startTime())).toList();
        List<AppointmentSlot> open = available(matches);
        if (!open.isEmpty()) {
            return heading + ". Yes, " + listed(open) + " is available.";
        }
        if (!matches.isEmpty()) {
            return heading + ". No, " + clock + " is not available.";
        }
        return heading + ". No, " + clock + " is not an available appointment slot on that date.";
    }

    private String missingRows(LocalDate date, String clock) {
        String hours = formatter.heading(date) + ". " + schedule.hoursClause(date) + ". ";
        if (clock == null) {
            return hours + "No appointment records exist for that date, so availability cannot be confirmed.";
        }
        return hours + clock + " cannot be verified because no appointment slots are recorded for that date.";
    }

    private static List<AppointmentSlot> available(List<AppointmentSlot> slots) {
        return slots.stream().filter(slot -> slot.status() == AppointmentSlot.Status.AVAILABLE).toList();
    }

    private String listed(List<AppointmentSlot> slots) {
        return String.join("; ", slots.stream().map(formatter::describe).toList());
    }
}
