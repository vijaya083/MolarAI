package com.molarai.dto;

import com.molarai.model.AppointmentSlot;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AppointmentSlotResponse(
        UUID id,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        AppointmentSlot.Status status,
        String provider) {

    public static AppointmentSlotResponse from(AppointmentSlot slot) {
        return new AppointmentSlotResponse(
                slot.id(), slot.date(), slot.startTime(), slot.endTime(), slot.status(), slot.provider());
    }
}
