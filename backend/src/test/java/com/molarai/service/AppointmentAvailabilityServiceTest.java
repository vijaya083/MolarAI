package com.molarai.service;

import com.molarai.model.AppointmentSlot;
import com.molarai.repository.AppointmentSlotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class AppointmentAvailabilityServiceTest {
    private static final ZoneId APP_ZONE = ZoneId.of("America/Los_Angeles");
    private static final LocalDate TODAY = LocalDate.of(2030, 6, 10);
    private final AppointmentSlotRepository repository = mock(AppointmentSlotRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-06-10T19:00:00Z"), APP_ZONE);
    private final AppointmentAvailabilityService service = new AppointmentAvailabilityService(repository, clock);

    @Test
    void retrievesAvailableSlotsByDateAndRange() {
        LocalDate date = TODAY.plusDays(2);
        var available = availableSlot(date);
        when(repository.findAvailableForDate(date)).thenReturn(List.of(available));

        assertEquals(List.of(available), service.availableOn(date));

        var from = date.atStartOfDay();
        var to = from.plusDays(1);
        when(repository.findAvailableBetween(from, to)).thenReturn(List.of(available));
        assertEquals(List.of(available), service.availableBetween(from, to));
        verify(repository).findAvailableForDate(date);
        verify(repository).findAvailableBetween(from, to);
    }

    @Test
    void rejectsYesterdayButAllowsTodayAndTomorrowUsingApplicationClock() {
        LocalDate yesterday = TODAY.minusDays(1);
        AppointmentDateInPastException error = assertThrows(AppointmentDateInPastException.class,
                () -> service.availableOn(yesterday));
        assertEquals("June 9, 2030 has already passed. Please provide today or a future date.", error.getMessage());
        assertEquals(yesterday, error.requestedDate());
        assertEquals(TODAY, error.currentDate());
        verify(repository, never()).findAvailableForDate(yesterday);

        when(repository.findAvailableForDate(TODAY)).thenReturn(List.of());
        when(repository.findAvailableForDate(TODAY.plusDays(1))).thenReturn(List.of());
        assertEquals(List.of(), service.availableOn(TODAY));
        assertEquals(List.of(), service.availableOn(TODAY.plusDays(1)));
        verify(repository).findAvailableForDate(TODAY);
        verify(repository).findAvailableForDate(TODAY.plusDays(1));
    }

    @Test
    void nullDateRetainsExistingValidationAndDoesNotQueryRepository() {
        InvalidAppointmentRequestException error = assertThrows(InvalidAppointmentRequestException.class,
                () -> service.availableOn(null));
        assertEquals("date is required", error.getMessage());
        verify(repository, never()).findAvailableForDate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsPastDateRangeBeforeRepositoryQuery() {
        var from = TODAY.minusDays(1).atTime(9, 0);
        var to = from.plusMinutes(30);
        AppointmentDateInPastException error = assertThrows(AppointmentDateInPastException.class,
                () -> service.availableBetween(from, to));

        assertEquals(TODAY.minusDays(1), error.requestedDate());
        assertEquals(TODAY, error.currentDate());
        verify(repository, never()).findAvailableBetween(from, to);
    }

    @Test
    void booksAvailableSlotAndTrimsPatientDetails() {
        UUID id = UUID.randomUUID();
        AppointmentSlot available = availableSlot(TODAY.plusDays(1));
        AppointmentSlot booked = new AppointmentSlot(id, available.date(), available.startTime(), available.endTime(),
                AppointmentSlot.Status.BOOKED, available.provider(), "Sam Patient", "555-0100");
        when(repository.findById(id)).thenReturn(Optional.of(available));
        when(repository.bookIfAvailable(id, "Sam Patient", "555-0100")).thenReturn(Optional.of(booked));

        assertEquals(booked, service.book(id, " Sam Patient ", " 555-0100 "));
        verify(repository).bookIfAvailable(id, "Sam Patient", "555-0100");
    }

    @Test
    void preventsBookingAnAlreadyBookedSlot() {
        UUID id = UUID.randomUUID();
        AppointmentSlot booked = new AppointmentSlot(id, TODAY.plusDays(1), LocalTime.of(9, 0),
                LocalTime.of(9, 30), AppointmentSlot.Status.BOOKED, "Dr. Chen", "Someone", "555-0101");
        when(repository.findById(id)).thenReturn(Optional.of(booked));

        assertThrows(AppointmentSlotUnavailableException.class, () -> service.book(id, "Sam", "555-0100"));
        verify(repository, never()).bookIfAvailable(id, "Sam", "555-0100");
    }

    @Test
    void rejectsUnknownSlotPastDateAndBlankPatientInformation() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThrows(AppointmentSlotNotFoundException.class, () -> service.book(id, "Sam", "555-0100"));
        assertThrows(InvalidAppointmentRequestException.class,
                () -> service.book(id, "  ", "555-0100"));
        assertThrows(InvalidAppointmentRequestException.class,
                () -> service.availableBetween(TODAY.plusDays(1).atTime(11, 0),
                        TODAY.plusDays(1).atTime(10, 0)));
    }

    @Test
    void cancellationMakesBookedSlotAvailableAgain() {
        UUID id = UUID.randomUUID();
        LocalDate date = TODAY.plusDays(1);
        AppointmentSlot booked = new AppointmentSlot(id, date, LocalTime.of(9, 0), LocalTime.of(9, 30),
                AppointmentSlot.Status.BOOKED, "Dr. Chen", "Sam", "555-0100");
        AppointmentSlot available = new AppointmentSlot(id, date, booked.startTime(), booked.endTime(),
                AppointmentSlot.Status.AVAILABLE, booked.provider(), null, null);
        when(repository.findById(id)).thenReturn(Optional.of(booked));
        when(repository.cancelIfBooked(id)).thenReturn(Optional.of(available));

        assertEquals(AppointmentSlot.Status.AVAILABLE, service.cancel(id).status());
    }

    @Test
    void alreadyCancelledAppointmentCannotBeCancelledAgain() {
        UUID id = UUID.randomUUID();
        AppointmentSlot available = new AppointmentSlot(id, TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(9, 30),
                AppointmentSlot.Status.AVAILABLE, "Dr. Chen", null, null);
        when(repository.findById(id)).thenReturn(Optional.of(available));
        assertThrows(AppointmentSlotUnavailableException.class, () -> service.cancel(id));
        verify(repository, never()).cancelIfBooked(id);
    }

    @Test
    void findsUpcomingAppointmentsByBothRegisteredDetails() {
        AppointmentSlot booked = new AppointmentSlot(UUID.randomUUID(), TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(9, 30),
                AppointmentSlot.Status.BOOKED, "Dr. Chen", "Sam Patient", "555-0100");
        when(repository.findUpcomingBookedByPatient(eq("Sam Patient"), eq("555-0100"), any(LocalDateTime.class))).thenReturn(List.of(booked));
        assertEquals(List.of(booked), service.findUpcomingAppointments(" Sam Patient ", " 555-0100 "));
    }

    @Test
    void bookingAndCancellationAreTransactional() throws ReflectiveOperationException {
        assertEquals(Transactional.class,
                AppointmentAvailabilityService.class.getMethod("book", UUID.class, String.class, String.class)
                        .getAnnotation(Transactional.class).annotationType());
        assertEquals(Transactional.class,
                AppointmentAvailabilityService.class.getMethod("cancel", UUID.class)
                        .getAnnotation(Transactional.class).annotationType());
    }

    private static AppointmentSlot availableSlot(LocalDate date) {
        return new AppointmentSlot(UUID.randomUUID(), date, LocalTime.of(9, 0), LocalTime.of(9, 30),
                AppointmentSlot.Status.AVAILABLE, "Dr. Maya Chen", null, null);
    }
}
