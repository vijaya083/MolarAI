package com.molarai.dto;

import com.molarai.controller.AppointmentController;
import com.molarai.model.AppointmentSlot;
import com.molarai.repository.AppointmentSlotRepository;
import com.molarai.service.AppointmentAvailabilityService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppointmentSlotsResponseTest {
    private static final LocalDate DATE = LocalDate.of(2030, 6, 10);

    @Test
    void classifiesMissingRecordsBookedDaysAndOpenSlots() {
        assertEquals(AppointmentSlotsResponse.Availability.NO_RECORDS, AppointmentSlotsResponse.fromStored(List.of()).availability());
        assertTrue(AppointmentSlotsResponse.fromStored(List.of()).slots().isEmpty());

        AppointmentSlotsResponse booked = AppointmentSlotsResponse.fromStored(List.of(slot(AppointmentSlot.Status.BOOKED)));
        assertEquals(AppointmentSlotsResponse.Availability.FULLY_BOOKED, booked.availability());
        assertTrue(booked.slots().isEmpty());

        AppointmentSlot open = slot(AppointmentSlot.Status.AVAILABLE);
        AppointmentSlotsResponse available = AppointmentSlotsResponse.fromStored(List.of(slot(AppointmentSlot.Status.BOOKED), open));
        assertEquals(AppointmentSlotsResponse.Availability.AVAILABLE, available.availability());
        assertEquals(1, available.slots().size());
        AppointmentSlotResponse choice = available.slots().getFirst();
        assertEquals(open.id(), choice.id());
        assertEquals(DATE, choice.date());
        assertEquals(LocalTime.of(9, 0), choice.startTime());
        assertEquals(LocalTime.of(9, 30), choice.endTime());
        assertEquals(AppointmentSlot.Status.AVAILABLE, choice.status());
        assertEquals("Maya Chen", choice.provider());
    }

    @Test
    void dateEndpointReusesStoredRowsAndDoesNotRegenerateABookedDay() {
        AppointmentSlotRepository repository = mock(AppointmentSlotRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2030-06-01T17:00:00Z"), ZoneId.of("America/Los_Angeles"));
        AppointmentAvailabilityService service = new AppointmentAvailabilityService(repository, clock);
        when(repository.findForDate(DATE)).thenReturn(List.of(slot(AppointmentSlot.Status.BOOKED)));

        AppointmentSlotsResponse response = new AppointmentController(service).availableSlots(DATE, null, null);

        assertEquals(AppointmentSlotsResponse.Availability.FULLY_BOOKED, response.availability());
        assertTrue(response.slots().isEmpty());
        verify(repository).findForDate(DATE);
        verify(repository, never()).insertIfAbsent(any());
        verify(repository, never()).findAvailableForDate(any());
    }

    private static AppointmentSlot slot(AppointmentSlot.Status status) {
        return new AppointmentSlot(UUID.randomUUID(), DATE, LocalTime.of(9, 0), LocalTime.of(9, 30),
                status, "Maya Chen", null, null);
    }
}
