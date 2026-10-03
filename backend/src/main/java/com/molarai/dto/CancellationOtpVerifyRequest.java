package com.molarai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CancellationOtpVerifyRequest(
        @NotBlank @Pattern(regexp = "\\d{6}") String otp) {
}
