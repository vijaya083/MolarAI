package com.molarai.service;

import org.springframework.stereotype.Service;

/**
 * Answers weekday and clinic-hours questions from {@link java.time.LocalDate} and the published schedule.
 * It does not call a language model or the appointment database.
 */
@Service
public class ClinicCalendarResponder {
    private final AppointmentAvailabilityRequestParser parser;
    private final ClinicSchedule schedule;
    private final AppointmentAvailabilityAnswerFormatter formatter;

    public ClinicCalendarResponder(
            AppointmentAvailabilityRequestParser parser,
            ClinicSchedule schedule,
            AppointmentAvailabilityAnswerFormatter formatter) {
        this.parser = parser;
        this.schedule = schedule;
        this.formatter = formatter;
    }

    public String describeDate(String question) {
        return switch (parser.parse(question)) {
            case AppointmentAvailabilityRequestParser.Clarification clarification -> clarification.message();
            case AppointmentAvailabilityRequestParser.Day day -> formatter.heading(day.date());
            case AppointmentAvailabilityRequestParser.ExactTime exact -> formatter.heading(exact.date());
            case AppointmentAvailabilityRequestParser.Range range -> formatter.heading(range.from().toLocalDate());
        };
    }

    public String describeHours(String question) {
        return switch (parser.parse(question)) {
            case AppointmentAvailabilityRequestParser.Clarification clarification
                    when "missing_date".equals(clarification.reason()) -> ClinicSchedule.WEEKLY_HOURS;
            case AppointmentAvailabilityRequestParser.Clarification clarification -> clarification.message();
            case AppointmentAvailabilityRequestParser.Day day -> hoursFor(day.date());
            case AppointmentAvailabilityRequestParser.ExactTime exact -> hoursFor(exact.date());
            case AppointmentAvailabilityRequestParser.Range range -> hoursFor(range.from().toLocalDate());
        };
    }

    private String hoursFor(java.time.LocalDate date) {
        return formatter.heading(date) + ". " + schedule.hoursClause(date) + ".";
    }
}
