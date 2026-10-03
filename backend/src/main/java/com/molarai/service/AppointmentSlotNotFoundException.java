package com.molarai.service;

public class AppointmentSlotNotFoundException extends RuntimeException {
    public AppointmentSlotNotFoundException() {
        super("Appointment slot was not found");
    }
}
