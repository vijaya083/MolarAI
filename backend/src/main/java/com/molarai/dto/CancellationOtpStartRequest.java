package com.molarai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CancellationOtpStartRequest(
        @NotNull UUID appointmentSlotId,
        @NotBlank @Size(max = 120) String patientName,
        @NotBlank @Size(max = 255) String patientContact) {
}
