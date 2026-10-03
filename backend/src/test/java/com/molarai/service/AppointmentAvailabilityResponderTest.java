package com.molarai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.molarai.model.AppointmentSlot;
import com.molarai.performance.PerformanceTiming;
import com.molarai.repository.AppointmentSlotRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppointmentAvailabilityResponderTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2030-06-01T17:00:00Z"), ZoneId.of("America/Los_Angeles"));

    @Test
    void explicitDateLooksUpSlotsLocallyWithoutAnLlmCallOrPatientDetails() {
        AppointmentSlotRepository repository = mock(AppointmentSlotRepository.class);
        AppointmentSlot slot = slot(LocalDate.of(2030, 6, 10), LocalTime.of(9, 0), "Dr. Maya Chen");
        when(repository.findForDate(slot.date())).thenReturn(List.of(slot));
        Harness harness = harness(repository);

        String answer = harness.responder.respond("Do you have appointments available on June 10, 2030?");

        assertEquals(new AppointmentAvailabilityAnswerFormatter(new ObjectMapper()).heading(slot.date())
                + ". I found these available appointment slots: 9:00 AM–9:30 AM with Dr. Maya Chen.", answer);
        assertFalse(answer.contains("Friday"));
        assertFalse(answer.contains("secret patient"));
        assertFalse(answer.contains("secret-contact"));
        verify(repository).findForDate(LocalDate.of(2030, 6, 10));
        verify(repository, never()).bookIfAvailable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).cancelIfBooked(org.mockito.ArgumentMatchers.any());
        verify(harness.timing).increment("tool_calls");
        verify(harness.timing).stage("appointment_lookup");
        verify(harness.timing, never()).increment("llm_calls");
    }

    @Test
    void isoDateAndExplicitTimeWindowUseTheExistingRangeLookup() {
        AppointmentSlotRepository repository = mock(AppointmentSlotRepository.class);
        when(repository.findForDate(LocalDate.of(2030, 6, 10))).thenReturn(List.of(
                slot(LocalDate.of(2030, 6, 10), LocalTime.of(14, 0), "Dr. Jordan Lee")));
        Harness harness = harness(repository);

        String answer = harness.responder.respond(
                "Any appointments available on 2030-06-10 from 9:00 AM to 12:00 PM?");

        assertTrue(answer.contains("No available appointment slots were found between 9:00 AM and 12:00 PM"));
        assertFalse(answer.contains("Dr. Jordan Lee"));
        verify(repository).findForDate(LocalDate.of(2030, 6, 10));
        verify(repository, never()).findAvailableBetween(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(harness.timing, never()).increment("llm_calls");
    }

    @Test
    void missingOrAmbiguousDateAsksForClarificationAndDoesNotQuery() {
        AppointmentSlotRepository repository = mock(AppointmentSlotRepository.class);
        Harness harness = harness(repository);

        assertEquals(AppointmentAvailabilityRequestParser.MISSING_DATE,
                harness.responder.respond("Do you have any appointments available?"));
        assertEquals(AppointmentAvailabilityRequestParser.AMBIGUOUS_DATE,
                harness.responder.respond("Do you have appointments available on June 10, 2030 or July 2, 2030?"));
        assertEquals(AppointmentAvailabilityRequestParser.AMBIGUOUS_TIME,
                harness.responder.respond("Is any slot open tomorrow afternoon?"));
        assertEquals(AppointmentAvailabilityRequestParser.UNREADABLE_DATE,
                harness.responder.respond("Do you have appointments available on February 31, 2030?"));
        assertEquals(AppointmentAvailabilityRequestParser.AMBIGUOUS_NUMERIC_DATE,
                harness.responder.respond("Is slot available on 9/29/2026?"));
        assertTrue(harness.responder.respond("Is slot available on Friday, September 29, 2026?")
                .contains("is Tuesday, not Friday"));

        verify(repository, never()).findForDate(org.mockito.ArgumentMatchers.any());
        verify(repository, never()).findAvailableBetween(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(harness.timing, never()).increment("tool_calls");
        verify(harness.timing, never()).increment("llm_calls");
    }

    @Test
    void pastDateReturnsTheServiceValidationMessageWithoutQueryingSlots() {
        AppointmentSlotRepository repository = mock(AppointmentSlotRepository.class);
        Harness harness = harness(repository);

        String answer = harness.responder.respond("Do you have appointments available on May 31, 2030?");

        assertEquals("May 31, 2030 is Friday and has already passed. Please provide today or a future date.", answer);
        verify(repository, never()).findForDate(org.mockito.ArgumentMatchers.any());
        verify(harness.timing).increment("tool_calls");
        verify(harness.timing).stage("appointment_lookup");
    }

    @Test
    void emptyScheduleSaysNothingIsAvailable() {
        AppointmentSlotRepository repository = mock(AppointmentSlotRepository.class);
        when(repository.findForDate(LocalDate.of(2030, 6, 10))).thenReturn(List.of());
        Harness harness = harness(repository);

        String answer = harness.responder.respond("Are there any slots on June 10, 2030?");
        assertTrue(answer.contains("The clinic is open from 9:00 AM to 2:00 PM"));
        assertTrue(answer.contains("No appointment records exist for that date, so availability cannot be confirmed."));
        assertFalse(answer.contains("no available appointment slots were found"));
        assertFalse(answer.contains("Dr."));
    }

    @Test
    void openFridayWithNoRowsStatesHoursAndDoesNotConfirmNineAm() {
        AppointmentSlotRepository repository = mock(AppointmentSlotRepository.class);
        when(repository.findForDate(LocalDate.of(2026, 10, 2))).thenReturn(List.of());
        Clock clock = Clock.fixed(Instant.parse("2026-10-01T17:00:00Z"), ZoneId.of("America/Los_Angeles"));
        Harness harness = harness(repository, clock);

        String day = harness.responder.respond("Is slot available on October 2, 2026?");
        String exact = harness.responder.respond("Is 9 AM available on October 2, 2026?");

        assertTrue(day.startsWith("October 2, 2026 is Friday."));
        assertTrue(day.contains("The clinic is open from 9:00 AM to 2:00 PM"));
        assertTrue(day.contains("No appointment records exist for that date, so availability cannot be confirmed."));
        assertEquals("October 2, 2026 is Friday. The clinic is open from 9:00 AM to 2:00 PM. "
                + "9:00 AM cannot be verified because no appointment slots are recorded for that date.", exact);
        assertFalse(exact.contains("Yes"));
        assertFalse(exact.contains("No,"));
        assertFalse(day.contains("Dr."));
    }

    @Test
    void september292026IsTuesdayAndLooksUpDatabaseSlots() {
        assertEquals(java.time.DayOfWeek.TUESDAY, LocalDate.of(2026, 9, 29).getDayOfWeek());
        AppointmentSlotRepository repository = mock(AppointmentSlotRepository.class);
        LocalDate date = LocalDate.of(2026, 9, 29);
        when(repository.findForDate(date)).thenReturn(List.of(slot(date, LocalTime.of(10, 0), "Dr. Maya Chen")));
        Clock clock = Clock.fixed(Instant.parse("2026-09-01T17:00:00Z"), ZoneId.of("America/Los_Angeles"));
        Harness harness = harness(repository, clock);

        String answer = harness.responder.respond("Is slot available on September 29, 2026?");

        assertTrue(answer.startsWith("September 29, 2026 is Tuesday."));
        assertTrue(answer.contains("10:00 AM–10:30 AM with Dr. Maya Chen"));
        assertFalse(answer.contains("Friday"));
        verify(repository).findForDate(date);
    }

    @Test
    void exactTimeChecksTheStoredSlotAndBookedSlotsAreExcluded() {
        AppointmentSlotRepository repository = mock(AppointmentSlotRepository.class);
        LocalDate date = LocalDate.of(2030, 6, 10);
        AppointmentSlot booked = new AppointmentSlot(UUID.randomUUID(), date, LocalTime.of(9, 0), LocalTime.of(9, 30),
                AppointmentSlot.Status.BOOKED, "Dr. Maya Chen", "secret patient", "secret-contact");
        AppointmentSlot open = slot(date, LocalTime.of(10, 0), "Dr. Jordan Lee");
        when(repository.findForDate(date)).thenReturn(List.of(booked, open));
        Harness harness = harness(repository);

        String no = harness.responder.respond("Is 9 AM available on June 10, 2030?");
        String yes = harness.responder.respond("Is 10 AM available on June 10, 2030?");
        String day = harness.responder.respond("Is slot available on June 10, 2030?");

        assertTrue(no.contains("No, 9:00 AM is not available."));
        assertFalse(no.contains("secret"));
        assertTrue(yes.contains("Yes, 10:00 AM–10:30 AM with Dr. Jordan Lee is available."));
        assertTrue(day.contains("10:00 AM–10:30 AM with Dr. Jordan Lee"));
        assertFalse(day.contains("9:00 AM"));
        assertFalse(day.contains("secret patient"));
    }

    @Test
    void bookedOutDayIsDistinctFromMissingDataAndClosedDaysDoNotInventSlots() {
        AppointmentSlotRepository repository = mock(AppointmentSlotRepository.class);
        LocalDate date = LocalDate.of(2030, 6, 10);
        when(repository.findForDate(date)).thenReturn(List.of(new AppointmentSlot(
                UUID.randomUUID(), date, LocalTime.of(9, 0), LocalTime.of(9, 30),
                AppointmentSlot.Status.BOOKED, "Dr. Maya Chen", "secret patient", "secret-contact")));
        Harness harness = harness(repository);
        String bookedOut = harness.responder.respond("Is slot available on June 10, 2030?");
        assertTrue(bookedOut.contains("no available appointment slots were found"));
        assertTrue(bookedOut.contains("The clinic is open from"));
        assertFalse(bookedOut.contains("secret"));

        LocalDate sunday = LocalDate.of(2030, 6, 1);
        while (sunday.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) sunday = sunday.plusDays(1);
        when(repository.findForDate(sunday)).thenReturn(List.of());
        String closed = harness.responder.respond("Is slot available on " + sunday.getMonth().name().charAt(0)
                + sunday.getMonth().name().substring(1).toLowerCase(java.util.Locale.ROOT) + " " + sunday.getDayOfMonth()
                + ", " + sunday.getYear() + "?");
        assertTrue(closed.contains("The clinic is closed on Sunday"));
        assertTrue(closed.contains("availability cannot be confirmed"));
        assertFalse(closed.contains("Dr."));
        assertFalse(closed.contains("9:00"));
    }

    private static Harness harness(AppointmentSlotRepository repository) {
        return harness(repository, CLOCK);
    }

    private static Harness harness(AppointmentSlotRepository repository, Clock clock) {
        PerformanceTiming timing = spy(new PerformanceTiming());
        AppointmentAvailabilityResponder responder = new AppointmentAvailabilityResponder(
                new AppointmentAvailabilityRequestParser(clock),
                new AppointmentAvailabilityService(repository, clock),
                new AppointmentAvailabilityAnswerFormatter(new ObjectMapper()),
                new ClinicSchedule(),
                timing);
        return new Harness(responder, timing);
    }

    private static AppointmentSlot slot(LocalDate date, LocalTime start, String provider) {
        return new AppointmentSlot(UUID.randomUUID(), date, start, start.plusMinutes(30),
                AppointmentSlot.Status.AVAILABLE, provider, "secret patient", "secret-contact");
    }

    private record Harness(AppointmentAvailabilityResponder responder, PerformanceTiming timing) {
    }
}
