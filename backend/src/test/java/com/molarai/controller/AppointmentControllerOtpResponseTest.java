package com.molarai.controller;

import com.molarai.service.AppointmentAvailabilityService;
import com.molarai.service.CancellationOtpService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppointmentControllerOtpResponseTest {
    @Test
    void includesDevelopmentCodeOnlyWhenDeliveryProviderReturnsIt() {
        AppointmentAvailabilityService appointmentService = mock(AppointmentAvailabilityService.class);
        CancellationOtpService cancellationOtpService = mock(CancellationOtpService.class);
        UUID requestId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2030-06-01T12:05:00Z");
        Instant resendAt = Instant.parse("2030-06-01T12:01:00Z");
        when(cancellationOtpService.issue(slotId, "Sam", "555-0100"))
                .thenReturn(new CancellationOtpService.IssuedOtp(requestId, expiresAt, resendAt, "123456"));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        AppointmentController controller = new AppointmentController(appointmentService, cancellationOtpService, environment);

        var response = controller.startCancellation(
                new com.molarai.dto.CancellationOtpStartRequest(slotId, "Sam", "555-0100"));

        assertEquals(requestId, response.requestId());
        assertEquals("123456", response.developmentOtp());
    }

    @Test
    void neverIncludesDevelopmentCodeInProductionResponse() {
        AppointmentAvailabilityService appointmentService = mock(AppointmentAvailabilityService.class);
        CancellationOtpService cancellationOtpService = mock(CancellationOtpService.class);
        UUID requestId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        when(cancellationOtpService.issue(slotId, "Sam", "555-0100"))
                .thenReturn(new CancellationOtpService.IssuedOtp(requestId, Instant.now(), Instant.now(), "123456"));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        AppointmentController controller = new AppointmentController(appointmentService, cancellationOtpService, environment);

        var response = controller.startCancellation(
                new com.molarai.dto.CancellationOtpStartRequest(slotId, "Sam", "555-0100"));

        assertNull(response.developmentOtp());
    }
}
