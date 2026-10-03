package com.molarai.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AppointmentSlot(
        UUID id,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        Status status,
        String provider,
        String patientName,
        String patientContact) {

    public enum Status {
        AVAILABLE,
        BOOKED
    }
}
