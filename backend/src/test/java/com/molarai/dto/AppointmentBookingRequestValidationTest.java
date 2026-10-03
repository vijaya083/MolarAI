package com.molarai.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppointmentBookingRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void requiresSlotAndNonBlankBoundedPatientDetails() {
        assertEquals(3, validator.validate(new AppointmentBookingRequest(null, " ", " ")).size());
        assertEquals(0, validator.validate(new AppointmentBookingRequest(UUID.randomUUID(), "Sam", "555-0100")).size());
        assertEquals(1, validator.validate(new AppointmentBookingRequest(UUID.randomUUID(), "x".repeat(121), "555-0100")).size());
    }
}
