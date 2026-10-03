package com.molarai.service;

import com.molarai.model.AppointmentSlot;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Authoritative fictional clinic rules for generating appointment slots.
 * Narrative clinic-hours text stays in {@link ClinicSchedule}; booking validation uses this policy.
 * A day has at most {@link #MAX_DAILY_SLOTS} appointments, shared across providers.
 */
public final class ClinicSchedulingPolicy {
    public static final int MAX_DAILY_SLOTS = 6;

    private final LocalTime weekdayOpen;
    private final LocalTime weekdayClose;
    private final LocalTime saturdayOpen;
    private final LocalTime saturdayClose;
    private final int slotMinutes;
    private final List<LocalTime> dailySlotStarts;
    private final int bookingHorizonDays;
    private final int minimumAdvanceMinutes;
    private final List<String> providers;
    private final Set<LocalDate> holidays;

    public ClinicSchedulingPolicy(
            LocalTime weekdayOpen,
            LocalTime weekdayClose,
            LocalTime saturdayOpen,
            LocalTime saturdayClose,
            int slotMinutes,
            List<LocalTime> dailySlotStarts,
            int bookingHorizonDays,
            int minimumAdvanceMinutes,
            List<String> providers,
            Set<LocalDate> holidays) {
        this.weekdayOpen = weekdayOpen;
        this.weekdayClose = weekdayClose;
        this.saturdayOpen = saturdayOpen;
        this.saturdayClose = saturdayClose;
        this.slotMinutes = slotMinutes;
        this.dailySlotStarts = List.copyOf(dailySlotStarts.stream().limit(MAX_DAILY_SLOTS).toList());
        this.bookingHorizonDays = bookingHorizonDays;
        this.minimumAdvanceMinutes = minimumAdvanceMinutes;
        this.providers = List.copyOf(providers);
        this.holidays = Set.copyOf(holidays);
    }

    public ClinicSchedulingPolicy withHolidays(Set<LocalDate> extraHolidays) {
        return new ClinicSchedulingPolicy(
                weekdayOpen, weekdayClose, saturdayOpen, saturdayClose, slotMinutes,
                dailySlotStarts, bookingHorizonDays, minimumAdvanceMinutes, providers, extraHolidays);
    }

    public static ClinicSchedulingPolicy defaults() {
        return new ClinicSchedulingPolicy(
                LocalTime.of(9, 0),
                LocalTime.of(14, 0),
                LocalTime.of(9, 0),
                LocalTime.of(14, 0),
                30,
                List.of(
                        LocalTime.of(9, 0),
                        LocalTime.of(9, 30),
                        LocalTime.of(10, 0),
                        LocalTime.of(11, 0),
                        LocalTime.of(12, 0),
                        LocalTime.of(13, 30)),
                90,
                60,
                List.of("Dr. Maya Chen", "Dr. Jordan Lee"),
                Set.of());
    }

    public int bookingHorizonDays() {
        return bookingHorizonDays;
    }

    public List<LocalTime> dailySlotStarts() {
        return dailySlotStarts;
    }

    public boolean isHoliday(LocalDate date) {
        return holidays.contains(date);
    }

    public boolean isClosed(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(date);
    }

    public boolean isOutsideBookingHorizon(LocalDate date, LocalDate today) {
        return date.isAfter(today.plusDays(bookingHorizonDays));
    }

    public LocalTime opensAt(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY ? saturdayOpen : weekdayOpen;
    }

    public LocalTime closesAt(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY ? saturdayClose : weekdayClose;
    }

    public List<AppointmentSlot> generate(LocalDate date, LocalDateTime now) {
        if (isClosed(date)) return List.of();
        LocalTime open = opensAt(date);
        LocalTime close = closesAt(date);
        if (!open.isBefore(close) || slotMinutes < 1 || providers.isEmpty() || dailySlotStarts.isEmpty()) {
            return List.of();
        }
        LocalDateTime earliest = now.plusMinutes(minimumAdvanceMinutes);
        List<AppointmentSlot> slots = new ArrayList<>();
        for (LocalTime start : dailySlotStarts) {
            if (slots.size() >= MAX_DAILY_SLOTS) break;
            LocalTime end = start.plusMinutes(slotMinutes);
            if (start.isBefore(open) || end.isAfter(close) || !end.isAfter(start)) continue;
            if (LocalDateTime.of(date, start).isBefore(earliest)) continue;
            String provider = providers.get(slots.size() % providers.size());
            slots.add(new AppointmentSlot(
                    UUID.randomUUID(), date, start, end,
                    AppointmentSlot.Status.AVAILABLE, provider, null, null));
        }
        return List.copyOf(slots);
    }

    public Duration slotLength() {
        return Duration.ofMinutes(slotMinutes);
    }
}
