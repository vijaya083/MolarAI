package com.molarai.service;

public class CancellationOtpException extends RuntimeException {
    public enum Reason { INVALID_REQUEST, INVALID_OTP, EXPIRED, TOO_MANY_ATTEMPTS, COOLDOWN, ALREADY_USED, CONFLICT }

    private final Reason reason;

    public CancellationOtpException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
