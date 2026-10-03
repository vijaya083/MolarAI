package com.molarai.service;

import java.time.Duration;

public record CancellationOtpPolicy(Duration expiry, Duration resendCooldown, int maxAttempts) {
    public CancellationOtpPolicy {
        if (expiry.isNegative() || expiry.isZero() || resendCooldown.isNegative() || resendCooldown.isZero() || maxAttempts < 1) {
            throw new IllegalArgumentException("invalid cancellation OTP policy");
        }
    }
}
