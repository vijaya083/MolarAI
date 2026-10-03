package com.molarai.dto;

import java.time.Instant;
import java.util.UUID;

public record CancellationOtpResponse(
        UUID requestId,
        Instant expiresAt,
        Instant resendAvailableAt,
        boolean verified,
        String developmentOtp) {
}
