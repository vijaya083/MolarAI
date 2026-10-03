package com.molarai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AppointmentCancellationRequest(
        @NotBlank @Size(max = 120) String patientName,
        @NotBlank @Size(max = 255) String patientContact) {
}
