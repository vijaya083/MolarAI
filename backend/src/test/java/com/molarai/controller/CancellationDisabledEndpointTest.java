package com.molarai.controller;

import com.molarai.config.CancellationAccessInterceptor;
import com.molarai.service.AppointmentAvailabilityService;
import com.molarai.service.CancellationOtpService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CancellationDisabledEndpointTest {
    @Test
    void rejectsAllCancellationRoutesBeforeValidationOrServiceCalls() throws Exception {
        AppointmentAvailabilityService appointments = mock(AppointmentAvailabilityService.class);
        CancellationOtpService otpService = mock(CancellationOtpService.class);
        AppointmentController controller =
                new AppointmentController(appointments, otpService, new MockEnvironment(), false);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .addInterceptors(new CancellationAccessInterceptor(false))
                .build();
        UUID id = UUID.randomUUID();

        mvc.perform(post("/api/appointments/cancellation-matches")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Appointment cancellation is disabled in this demo."));
        mvc.perform(delete("/api/appointments/slots/{id}/booking", id))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/appointments/cancellation-requests")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/appointments/cancellation-requests/{id}/resend", id))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/appointments/cancellation-requests/{id}/verify", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/appointments/cancellation-requests/{id}", id))
                .andExpect(status().isForbidden());

        verifyNoInteractions(appointments, otpService);
    }
}
