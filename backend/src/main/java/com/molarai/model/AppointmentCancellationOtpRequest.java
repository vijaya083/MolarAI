package com.molarai.model;

import java.time.Instant;
import java.util.UUID;

public record AppointmentCancellationOtpRequest(
        UUID id,
        UUID appointmentSlotId,
        String otpHash,
        String otpSalt,
        Instant expiresAt,
        int attemptCount,
        Instant lastSentAt,
        Status status,
        Instant verifiedAt,
        Instant consumedAt) {

    public enum Status { PENDING, VERIFIED, INVALIDATED, CONSUMED }
}
