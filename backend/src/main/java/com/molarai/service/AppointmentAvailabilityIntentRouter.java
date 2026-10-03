package com.molarai.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Separates clinic hours, live slot lookup, exact-time checks, and calendar questions.
 * A slot question is never discarded because it also mentions hours or a closed day.
 */
@Component
public class AppointmentAvailabilityIntentRouter {
    private static final Pattern SLOT = Pattern.compile(
            "\\b(appointments?|slots?|visits?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AVAILABLE = Pattern.compile(
            "\\b(available|availability|openings?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUESTS_A_SLOT = Pattern.compile(
            "\\b(any (appointments?|slots?)|do you have (any )?(appointments?|slots?)|"
                    + "are there (any )?(appointments?|slots?)|is there (an? )?(appointment|slot)|"
                    + "is (a |the )?(appointment|slot)|"
                    + "can i get (an? )?(appointment|slot)|find (me )?(an? )?(appointment|slot)|"
                    + "check (for )?(appointments?|slots?)|show (me )?(appointments?|slots?))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern POLICY = Pattern.compile(
            "\\b(cancell?ation|policy|policies|price|pricing|fee|fees|insurance)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATED = Pattern.compile(
            "\\b(today|tomorrow|tonight|"
                    + "monday|tuesday|wednesday|thursday|friday|saturday|sunday|"
                    + "january|february|march|april|may|june|july|august|september|october|november|december)\\b"
                    + "|\\b\\d{4}-\\d{2}-\\d{2}\\b"
                    + "|\\b\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK_TIME = Pattern.compile(
            "\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_RANGE = Pattern.compile(
            "\\b(?:from|between)\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\s+(?:to|and|until)\\s+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKDAY_QUESTION = Pattern.compile(
            "\\b(what day|what weekday|which day|what day of the week)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLINIC_HOURS = Pattern.compile(
            "\\b(opening hours|clinic hours|business hours|office hours|your hours|are you open|when do you open|"
                    + "when are you open|hours of operation)\\b",
            Pattern.CASE_INSENSITIVE);

    public QuestionIntent classify(String question) {
        if (question == null || question.isBlank()) return QuestionIntent.FAQ;
        String text = question.toLowerCase(Locale.ROOT);
        if (POLICY.matcher(text).find()) return QuestionIntent.FAQ;
        boolean availability = isAppointmentAvailability(text);
        if (WEEKDAY_QUESTION.matcher(text).find() && !availability) return QuestionIntent.DATE_OR_WEEKDAY_QUERY;
        if (availability && CLOCK_TIME.matcher(text).find() && !TIME_RANGE.matcher(text).find()) {
            return QuestionIntent.EXACT_SLOT_AVAILABILITY;
        }
        if (availability) return QuestionIntent.APPOINTMENT_AVAILABILITY;
        if (CLINIC_HOURS.matcher(text).find()) return QuestionIntent.CLINIC_HOURS;
        return QuestionIntent.FAQ;
    }

    public boolean requiresLiveAvailability(String question) {
        QuestionIntent intent = classify(question);
        return intent == QuestionIntent.APPOINTMENT_AVAILABILITY || intent == QuestionIntent.EXACT_SLOT_AVAILABILITY;
    }

    private static boolean isAppointmentAvailability(String text) {
        boolean slot = SLOT.matcher(text).find();
        boolean available = AVAILABLE.matcher(text).find();
        boolean dated = DATED.matcher(text).find();
        boolean clock = CLOCK_TIME.matcher(text).find();
        if (slot && available) return true;
        if (slot && dated) return true;
        if (available && dated) return true;
        return REQUESTS_A_SLOT.matcher(text).find() && (dated || clock);
    }
}
