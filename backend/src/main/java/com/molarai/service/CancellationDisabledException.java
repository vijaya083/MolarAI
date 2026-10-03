package com.molarai.service;

public class CancellationDisabledException extends RuntimeException {
    public CancellationDisabledException() {
        super("Appointment cancellation is disabled in this demo.");
    }
}
