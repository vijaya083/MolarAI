package com.molarai.controller;

import com.molarai.dto.AppointmentBookingRequest;
import com.molarai.dto.AppointmentCancellationRequest;
import com.molarai.dto.AppointmentRescheduleRequest;
import com.molarai.dto.AppointmentSlotResponse;
import com.molarai.dto.AppointmentSlotsResponse;
import com.molarai.dto.CancellationOtpResponse;
import com.molarai.dto.CancellationOtpStartRequest;
import com.molarai.dto.CancellationOtpVerifyRequest;
import com.molarai.service.AppointmentAvailabilityService;
import com.molarai.service.CancellationOtpException;
import com.molarai.service.CancellationDisabledException;
import com.molarai.service.CancellationOtpService;
import com.molarai.service.InvalidAppointmentRequestException;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {
    private final AppointmentAvailabilityService appointmentService;
    private final CancellationOtpService cancellationOtpService;
    private final Environment environment;
    private final boolean cancellationEnabled;

    public AppointmentController(AppointmentAvailabilityService appointmentService) {
        this.appointmentService = appointmentService;
        this.cancellationOtpService = null;
        this.environment = new StandardEnvironment();
        this.cancellationEnabled = true;
    }

    @Autowired
    public AppointmentController(
            AppointmentAvailabilityService appointmentService,
            CancellationOtpService cancellationOtpService,
            Environment environment,
            @Value("${molarai.cancellation.enabled:true}") boolean cancellationEnabled) {
        this.appointmentService = appointmentService;
        this.cancellationOtpService = cancellationOtpService;
        this.environment = environment;
        this.cancellationEnabled = cancellationEnabled;
    }

    public AppointmentController(
            AppointmentAvailabilityService appointmentService,
            CancellationOtpService cancellationOtpService,
            Environment environment) {
        this(appointmentService, cancellationOtpService, environment, true);
    }

    @GetMapping("/slots")
    public AppointmentSlotsResponse availableSlots(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        if (date != null && (from != null || to != null)) {
            throw new InvalidAppointmentRequestException("provide date or the from/to range, not both");
        }
        if (date != null) {
            return appointmentService.prepareForBooking(date);
        }
        if (from != null && to != null) {
            return new AppointmentSlotsResponse(appointmentService.availableBetween(from, to).stream()
                    .map(AppointmentSlotResponse::from).toList());
        }
        throw new InvalidAppointmentRequestException("provide date or both from and to");
    }

    @PostMapping("/book")
    public ResponseEntity<AppointmentSlotResponse> book(@Valid @RequestBody AppointmentBookingRequest request) {
        AppointmentSlotResponse response = AppointmentSlotResponse.from(appointmentService.book(
                request.slotId(), request.patientName(), request.patientContact()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/cancellation-matches")
    public AppointmentSlotsResponse cancellationMatches(@Valid @RequestBody AppointmentCancellationRequest request) {
        requireCancellationEnabled();
        return new AppointmentSlotsResponse(appointmentService.findUpcomingAppointments(
                        request.patientName(), request.patientContact()).stream()
                .map(AppointmentSlotResponse::from)
                .toList());
    }

    @GetMapping("/slots/{slotId}")
    public AppointmentSlotResponse slot(@PathVariable UUID slotId) {
        return AppointmentSlotResponse.from(appointmentService.findPublicSlot(slotId));
    }

    @DeleteMapping("/slots/{slotId}/booking")
    public AppointmentSlotResponse cancel(@PathVariable UUID slotId) {
        requireCancellationEnabled();
        if (cancellationOtpService != null) {
            throw new CancellationOtpException(CancellationOtpException.Reason.INVALID_REQUEST,
                    "Cancellation requires verification.");
        }
        return AppointmentSlotResponse.from(appointmentService.cancel(slotId));
    }

    @PostMapping("/cancellation-requests")
    public CancellationOtpResponse startCancellation(@Valid @RequestBody CancellationOtpStartRequest request) {
        requireCancellationEnabled();
        return toResponse(cancellationOtpService.issue(request.appointmentSlotId(), request.patientName(), request.patientContact()));
    }

    @PostMapping("/cancellation-requests/{requestId}/resend")
    public CancellationOtpResponse resendCancellation(@PathVariable UUID requestId) {
        requireCancellationEnabled();
        return toResponse(cancellationOtpService.resend(requestId));
    }

    @PostMapping("/cancellation-requests/{requestId}/verify")
    public CancellationOtpResponse verifyCancellation(
            @PathVariable UUID requestId, @Valid @RequestBody CancellationOtpVerifyRequest request) {
        requireCancellationEnabled();
        cancellationOtpService.verify(requestId, request.otp());
        return new CancellationOtpResponse(requestId, null, null, true, null);
    }

    @DeleteMapping("/cancellation-requests/{requestId}")
    public AppointmentSlotResponse cancelVerified(@PathVariable UUID requestId) {
        requireCancellationEnabled();
        return AppointmentSlotResponse.from(cancellationOtpService.cancelVerified(requestId));
    }

    private void requireCancellationEnabled() {
        if (!cancellationEnabled) throw new CancellationDisabledException();
    }

    private CancellationOtpResponse toResponse(CancellationOtpService.IssuedOtp issued) {
        return new CancellationOtpResponse(issued.requestId(), issued.expiresAt(), issued.resendAvailableAt(), false,
                environment.acceptsProfiles(Profiles.of("prod", "production")) ? null : issued.developmentCode());
    }

    @PostMapping("/slots/{slotId}/reschedule")
    public AppointmentSlotResponse reschedule(
            @PathVariable UUID slotId, @Valid @RequestBody AppointmentRescheduleRequest request) {
        return AppointmentSlotResponse.from(appointmentService.reschedule(slotId, request.newSlotId()));
    }
}
