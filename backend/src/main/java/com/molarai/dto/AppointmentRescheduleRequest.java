package com.molarai.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AppointmentRescheduleRequest(@NotNull UUID newSlotId) {
}
