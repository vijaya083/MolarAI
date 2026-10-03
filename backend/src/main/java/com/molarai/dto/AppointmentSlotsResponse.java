package com.molarai.dto;

import com.molarai.model.AppointmentSlot;

import java.util.Comparator;
import java.util.List;

public record AppointmentSlotsResponse(List<AppointmentSlotResponse> slots, Availability availability) {
    public AppointmentSlotsResponse {
        slots = List.copyOf(slots == null ? List.of() : slots);
    }

    public AppointmentSlotsResponse(List<AppointmentSlotResponse> slots) {
        this(slots, slots == null || slots.isEmpty() ? null : Availability.AVAILABLE);
    }

    public static AppointmentSlotsResponse fromStored(List<AppointmentSlot> stored) {
        List<AppointmentSlot> rows = stored == null ? List.of() : stored.stream()
                .sorted(Comparator.comparing(AppointmentSlot::startTime).thenComparing(AppointmentSlot::provider))
                .toList();
        List<AppointmentSlotResponse> available = rows.stream()
                .filter(slot -> slot.status() == AppointmentSlot.Status.AVAILABLE)
                .map(AppointmentSlotResponse::from)
                .toList();
        Availability availability = rows.isEmpty()
                ? Availability.NO_RECORDS
                : available.isEmpty() ? Availability.FULLY_BOOKED : Availability.AVAILABLE;
        return new AppointmentSlotsResponse(available, availability);
    }

    public enum Availability {
        NO_RECORDS,
        FULLY_BOOKED,
        AVAILABLE,
        CLOSED,
        OUTSIDE_BOOKING_WINDOW
    }
}
