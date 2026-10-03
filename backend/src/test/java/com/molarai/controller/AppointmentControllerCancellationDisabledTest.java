package com.molarai.controller;

import com.molarai.dto.AppointmentCancellationRequest;
import com.molarai.dto.CancellationOtpStartRequest;
import com.molarai.dto.CancellationOtpVerifyRequest;
import com.molarai.service.AppointmentAvailabilityService;
import com.molarai.service.CancellationDisabledException;
import com.molarai.service.CancellationOtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AppointmentControllerCancellationDisabledTest {
    private final AppointmentAvailabilityService appointments = mock(AppointmentAvailabilityService.class);
    private final CancellationOtpService otpService = mock(CancellationOtpService.class);
    private AppointmentController controller;

    @BeforeEach
    void setUp() {
        controller = new AppointmentController(appointments, otpService, new MockEnvironment(), false);
    }

    @Test
    void rejectsEveryCancellationEndpointBeforeCallingServices() {
        UUID id = UUID.randomUUID();

        assertThrows(CancellationDisabledException.class,
                () -> controller.cancellationMatches(new AppointmentCancellationRequest("Sam", "555-0100")));
        assertThrows(CancellationDisabledException.class, () -> controller.cancel(id));
        assertThrows(CancellationDisabledException.class,
                () -> controller.startCancellation(new CancellationOtpStartRequest(id, "Sam", "555-0100")));
        assertThrows(CancellationDisabledException.class, () -> controller.resendCancellation(id));
        assertThrows(CancellationDisabledException.class,
                () -> controller.verifyCancellation(id, new CancellationOtpVerifyRequest("123456")));
        assertThrows(CancellationDisabledException.class, () -> controller.cancelVerified(id));

        verifyNoInteractions(appointments, otpService);
    }
}
