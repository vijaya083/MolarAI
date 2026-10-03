package com.molarai.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves one clinic-local date, or an explicit same-day time window, from an availability question.
 * Vague or conflicting dates are not guessed.
 */
@Component
public class AppointmentAvailabilityRequestParser {
    static final String MISSING_DATE =
            "Please tell me the date you want to check, such as June 10, 2030.";
    static final String AMBIGUOUS_DATE =
            "I need one specific date to check availability. Please include a single date such as June 10, 2030.";
    static final String AMBIGUOUS_TIME =
            "I can check that date, but I need either the whole day or both a start and end time.";
    static final String UNREADABLE_DATE =
            "I couldn't read that date. Please use a date such as June 10, 2030.";
    static final String AMBIGUOUS_NUMERIC_DATE =
            "Please write the month name, such as September 29, 2026. Numeric dates like 9/29 are ambiguous.";

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("january", 1), Map.entry("february", 2), Map.entry("march", 3),
            Map.entry("april", 4), Map.entry("may", 5), Map.entry("june", 6),
            Map.entry("july", 7), Map.entry("august", 8), Map.entry("september", 9),
            Map.entry("october", 10), Map.entry("november", 11), Map.entry("december", 12));
    private static final Map<String, DayOfWeek> WEEKDAYS = Map.of(
            "monday", DayOfWeek.MONDAY, "tuesday", DayOfWeek.TUESDAY, "wednesday", DayOfWeek.WEDNESDAY,
            "thursday", DayOfWeek.THURSDAY, "friday", DayOfWeek.FRIDAY, "saturday", DayOfWeek.SATURDAY,
            "sunday", DayOfWeek.SUNDAY);

    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern MONTH_DATE = Pattern.compile(
            "\\b(january|february|march|april|may|june|july|august|september|october|november|december)"
                    + "\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_DATE = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b");
    private static final Pattern TODAY_OR_TOMORROW = Pattern.compile("\\b(today|tomorrow)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT_WEEKDAY = Pattern.compile(
            "\\bnext\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern VAGUE_WINDOW = Pattern.compile(
            "\\b(tonight|this week|next week)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKDAY = Pattern.compile(
            "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_RANGE = Pattern.compile(
            "\\b(?:from|between)\\s+(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))\\s+(?:to|and|until)\\s+"
                    + "(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK_TIME = Pattern.compile(
            "\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART_OF_DAY = Pattern.compile(
            "\\b(morning|afternoon|evening)\\b", Pattern.CASE_INSENSITIVE);

    private final Clock clock;

    public AppointmentAvailabilityRequestParser(Clock clock) {
        this.clock = clock;
    }

    public Interpretation parse(String question) {
        if (question == null || question.isBlank()) {
            return new Clarification("missing_date", MISSING_DATE);
        }
        String text = question.trim();
        if (NUMERIC_DATE.matcher(text).find()) {
            return new Clarification("ambiguous_date", AMBIGUOUS_NUMERIC_DATE);
        }
        if (VAGUE_WINDOW.matcher(text).find()) {
            return new Clarification("ambiguous_date", AMBIGUOUS_DATE);
        }
        List<LocalDate> dates = new ArrayList<>();
        try {
            collectIso(text, dates);
            collectMonth(text, dates);
            boolean explicitCalendarDate = !dates.isEmpty();
            if (!explicitCalendarDate && hasUnresolvedWeekday(text)) {
                return new Clarification("ambiguous_date", AMBIGUOUS_DATE);
            }
            collectTodayTomorrowAndNextWeekday(text, dates);
        } catch (UnreadableDateException exception) {
            return new Clarification("unreadable_date", UNREADABLE_DATE);
        }
        LinkedHashSet<LocalDate> unique = new LinkedHashSet<>(dates);
        if (unique.isEmpty()) {
            return new Clarification("missing_date", MISSING_DATE);
        }
        if (unique.size() > 1) {
            return new Clarification("ambiguous_date", AMBIGUOUS_DATE);
        }
        LocalDate date = unique.getFirst();
        String weekdayConflict = weekdayConflict(text, date);
        if (weekdayConflict != null) {
            return new Clarification("weekday_conflict", weekdayConflict);
        }
        Matcher range = TIME_RANGE.matcher(text);
        if (range.find()) {
            LocalTime start = parseClockTime(range.group(1));
            LocalTime end = parseClockTime(range.group(2));
            if (start == null || end == null || !start.isBefore(end)) {
                return new Clarification("ambiguous_time", AMBIGUOUS_TIME);
            }
            return new Range(LocalDateTime.of(date, start), LocalDateTime.of(date, end));
        }
        if (PART_OF_DAY.matcher(text).find()) {
            return new Clarification("ambiguous_time", AMBIGUOUS_TIME);
        }
        List<LocalTime> times = new ArrayList<>();
        Matcher clock = CLOCK_TIME.matcher(text);
        while (clock.find()) {
            LocalTime time = parseClockTime(text.substring(clock.start(), clock.end()));
            if (time == null) return new Clarification("ambiguous_time", AMBIGUOUS_TIME);
            times.add(time);
        }
        if (times.size() > 1) return new Clarification("ambiguous_time", AMBIGUOUS_TIME);
        if (times.size() == 1) return new ExactTime(date, times.getFirst());
        return new Day(date);
    }

    private static String weekdayConflict(String text, LocalDate date) {
        Matcher matcher = WEEKDAY.matcher(text);
        DayOfWeek conflict = null;
        while (matcher.find()) {
            String prefix = text.substring(Math.max(0, matcher.start() - 5), matcher.start()).toLowerCase(Locale.ROOT);
            if (prefix.endsWith("next ")) continue;
            DayOfWeek stated = WEEKDAYS.get(matcher.group(1).toLowerCase(Locale.ROOT));
            if (stated != date.getDayOfWeek()) {
                conflict = stated;
            }
        }
        if (conflict == null) return null;
        return DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US).format(date)
                + " is " + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US)
                + ", not " + conflict.getDisplayName(TextStyle.FULL, Locale.US)
                + ". Please confirm which date you mean.";
    }

    private void collectIso(String text, List<LocalDate> dates) {
        Matcher matcher = ISO_DATE.matcher(text);
        while (matcher.find()) {
            dates.add(date(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))));
        }
    }

    private void collectMonth(String text, List<LocalDate> dates) {
        Matcher matcher = MONTH_DATE.matcher(text);
        LocalDate today = LocalDate.now(clock);
        while (matcher.find()) {
            int month = MONTHS.get(matcher.group(1).toLowerCase(Locale.ROOT));
            int day = Integer.parseInt(matcher.group(2));
            if (matcher.group(3) != null) {
                dates.add(date(Integer.parseInt(matcher.group(3)), month, day));
            } else {
                dates.add(dateInCurrentOrNextYear(today, month, day));
            }
        }
    }

    private void collectTodayTomorrowAndNextWeekday(String text, List<LocalDate> dates) {
        LocalDate today = LocalDate.now(clock);
        Matcher relative = TODAY_OR_TOMORROW.matcher(text);
        while (relative.find()) {
            dates.add("tomorrow".equalsIgnoreCase(relative.group(1)) ? today.plusDays(1) : today);
        }
        Matcher nextWeekday = NEXT_WEEKDAY.matcher(text);
        while (nextWeekday.find()) {
            DayOfWeek target = WEEKDAYS.get(nextWeekday.group(1).toLowerCase(Locale.ROOT));
            int daysUntil = (target.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
            if (daysUntil == 0) daysUntil = 7;
            dates.add(today.plusDays(daysUntil));
        }
    }

    private static boolean hasUnresolvedWeekday(String text) {
        Matcher matcher = WEEKDAY.matcher(text);
        while (matcher.find()) {
            String prefix = text.substring(Math.max(0, matcher.start() - 5), matcher.start()).toLowerCase(Locale.ROOT);
            if (!prefix.endsWith("next ")) {
                return true;
            }
        }
        return false;
    }

    private static LocalDate dateInCurrentOrNextYear(LocalDate today, int month, int day) {
        LocalDate thisYear = date(today.getYear(), month, day);
        return thisYear.isBefore(today) ? date(today.getYear() + 1, month, day) : thisYear;
    }

    private static LocalDate date(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException exception) {
            throw new UnreadableDateException();
        }
    }

    private static LocalTime parseClockTime(String text) {
        Matcher matcher = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)", Pattern.CASE_INSENSITIVE)
                .matcher(text.trim());
        if (!matcher.matches()) return null;
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        if (hour < 1 || hour > 12 || minute > 59) return null;
        boolean pm = matcher.group(3).equalsIgnoreCase("pm");
        if (hour == 12) hour = 0;
        if (pm) hour += 12;
        return LocalTime.of(hour, minute);
    }

    public sealed interface Interpretation permits Day, Range, ExactTime, Clarification {
    }

    public record Day(LocalDate date) implements Interpretation {
    }

    public record Range(LocalDateTime from, LocalDateTime to) implements Interpretation {
    }

    public record ExactTime(LocalDate date, LocalTime time) implements Interpretation {
    }

    public record Clarification(String reason, String message) implements Interpretation {
    }

    private static final class UnreadableDateException extends RuntimeException {
    }
}
