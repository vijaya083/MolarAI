package com.molarai.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class AppointmentDateInPastException extends InvalidAppointmentRequestException {
    private static final DateTimeFormatter FRIENDLY_DATE =
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US);

    private final LocalDate requestedDate;
    private final LocalDate currentDate;

    public AppointmentDateInPastException(LocalDate requestedDate, LocalDate currentDate) {
        super(FRIENDLY_DATE.format(requestedDate) + " has already passed. Please provide today or a future date.");
        this.requestedDate = requestedDate;
        this.currentDate = currentDate;
    }

    public LocalDate requestedDate() {
        return requestedDate;
    }

    public LocalDate currentDate() {
        return currentDate;
    }
}
