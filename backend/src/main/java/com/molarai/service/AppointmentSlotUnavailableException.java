package com.molarai.service;

public class AppointmentSlotUnavailableException extends RuntimeException {
    public AppointmentSlotUnavailableException() {
        super("Appointment slot is no longer available");
    }
}
