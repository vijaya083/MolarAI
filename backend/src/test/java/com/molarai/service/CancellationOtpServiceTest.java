package com.molarai.service;

import com.molarai.model.AppointmentCancellationOtpRequest;
import com.molarai.model.AppointmentSlot;
import com.molarai.repository.AppointmentCancellationOtpRequestRepository;
import com.molarai.repository.AppointmentSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancellationOtpServiceTest {
    private static final Instant NOW = Instant.parse("2030-06-01T12:00:00Z");
    private final AppointmentSlotRepository appointments = mock(AppointmentSlotRepository.class);
    private final AppointmentCancellationOtpRequestRepository requests = mock(AppointmentCancellationOtpRequestRepository.class);
    private final OtpDeliveryProvider delivery = mock(OtpDeliveryProvider.class);
    private final SecureRandom random = new FixedSecureRandom();
    private final UUID slotId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final AppointmentSlot booked = new AppointmentSlot(slotId, LocalDate.of(2030, 6, 2),
            LocalTime.of(9, 0), LocalTime.of(9, 30), AppointmentSlot.Status.BOOKED,
            "Dr. Chen", "Sam Patient", "9876543210");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private CancellationOtpService service;

    @BeforeEach
    void setUp() {
        when(appointments.findById(slotId)).thenReturn(Optional.of(booked));
        when(requests.lockBookedAppointment(slotId)).thenReturn(true);
        when(delivery.send(any(), any())).thenReturn(new OtpDeliveryProvider.DeliveryResult(null));
        service = new CancellationOtpService(appointments, requests,
                new AppointmentAvailabilityService(appointments, clock), delivery,
                new CancellationOtpPolicy(Duration.ofMinutes(5), Duration.ofSeconds(60), 5), clock, random);
    }

    @Test
    void issuesHashedOtpAndBindsItToAppointment() {
        CancellationOtpService.IssuedOtp issued = service.issue(slotId, " Sam Patient ", "9876543210");
        AppointmentCancellationOtpRequest stored = captured();
        assertEquals(slotId, stored.appointmentSlotId());
        assertEquals(issued.requestId(), stored.id());
        assertEquals(NOW.plusSeconds(300), issued.expiresAt());
        verify(delivery).send("9876543210", "123456");
        verify(requests).invalidateActiveForAppointment(slotId);
        assertEquals(false, stored.otpHash().equals("123456"));
    }

    @Test
    void rejectsIncorrectOtpAndCountsAttempt() {
        AppointmentCancellationOtpRequest request = pending(0, NOW.plusSeconds(300));
        when(requests.findForUpdate(requestId)).thenReturn(request);
        CancellationOtpException error = assertThrows(CancellationOtpException.class,
                () -> service.verify(requestId, "000000"));
        assertEquals(CancellationOtpException.Reason.INVALID_OTP, error.reason());
        verify(requests).incrementAttempts(requestId);
    }

    @Test
    void acceptsCorrectOtpOnce() {
        AppointmentCancellationOtpRequest request = pending(0, NOW.plusSeconds(300));
        when(requests.findForUpdate(requestId)).thenReturn(request);
        service.verify(requestId, "123456");
        verify(requests).markVerified(requestId, NOW);
    }

    @Test
    void rejectsExpiredOtp() {
        AppointmentCancellationOtpRequest request = pending(0, NOW);
        when(requests.findForUpdate(requestId)).thenReturn(request);
        CancellationOtpException error = assertThrows(CancellationOtpException.class,
                () -> service.verify(requestId, "123456"));
        assertEquals(CancellationOtpException.Reason.EXPIRED, error.reason());
        verify(requests).invalidate(requestId);
    }

    @Test
    void invalidatesAfterMaximumAttempts() {
        AppointmentCancellationOtpRequest request = pending(4, NOW.plusSeconds(300));
        when(requests.findForUpdate(requestId)).thenReturn(request);
        assertThrows(CancellationOtpException.class, () -> service.verify(requestId, "000000"));
        verify(requests).incrementAttempts(requestId);
        verify(requests).invalidate(requestId);
    }

    @Test
    void preventsReuseOfVerifiedOrConsumedRequest() {
        AppointmentCancellationOtpRequest request = pending(0, NOW.plusSeconds(300));
        request = new AppointmentCancellationOtpRequest(request.id(), request.appointmentSlotId(), request.otpHash(), request.otpSalt(),
                request.expiresAt(), request.attemptCount(), request.lastSentAt(), AppointmentCancellationOtpRequest.Status.VERIFIED, NOW, null);
        when(requests.findForUpdate(requestId)).thenReturn(request);
        CancellationOtpException error = assertThrows(CancellationOtpException.class,
                () -> service.verify(requestId, "123456"));
        assertEquals(CancellationOtpException.Reason.ALREADY_USED, error.reason());
        verify(requests, never()).markVerified(any(), any());
    }

    @Test
    void enforcesResendCooldownAndInvalidatesPreviousCode() {
        AppointmentCancellationOtpRequest tooSoon = pending(0, NOW.plusSeconds(300));
        when(requests.findById(requestId)).thenReturn(tooSoon);
        assertThrows(CancellationOtpException.class, () -> service.resend(requestId));

        AppointmentCancellationOtpRequest eligible = new AppointmentCancellationOtpRequest(requestId, slotId,
                tooSoon.otpHash(), tooSoon.otpSalt(), tooSoon.expiresAt(), 0, NOW.minusSeconds(61),
                AppointmentCancellationOtpRequest.Status.PENDING, null, null);
        when(requests.findById(requestId)).thenReturn(eligible);
        when(requests.findForUpdate(requestId)).thenReturn(eligible);
        CancellationOtpService.IssuedOtp resent = service.resend(requestId);
        verify(requests).invalidateActiveForAppointment(slotId);
        verify(requests).insert(any(AppointmentCancellationOtpRequest.class));
        assertEquals(false, resent.requestId().equals(requestId));
    }

    @Test
    void refusesCancellationWithoutVerifiedRequest() {
        when(requests.findById(requestId)).thenReturn(pending(0, NOW.plusSeconds(300)));
        assertThrows(CancellationOtpException.class, () -> service.cancelVerified(requestId));
        verify(appointments, never()).cancelIfBooked(slotId);
    }

    @Test
    void cancelsOnlyTheVerifiedAppointmentAndConsumesRequest() {
        AppointmentCancellationOtpRequest verified = new AppointmentCancellationOtpRequest(requestId, slotId,
                "hash", "salt", NOW.plusSeconds(300), 0, NOW,
                AppointmentCancellationOtpRequest.Status.VERIFIED, NOW, null);
        AppointmentSlot available = new AppointmentSlot(slotId, booked.date(), booked.startTime(), booked.endTime(),
                AppointmentSlot.Status.AVAILABLE, booked.provider(), null, null);
        when(requests.findById(requestId)).thenReturn(verified);
        when(requests.findForUpdate(requestId)).thenReturn(verified);
        when(appointments.cancelIfBooked(slotId)).thenReturn(Optional.of(available));
        assertEquals(AppointmentSlot.Status.AVAILABLE, service.cancelVerified(requestId).status());
        verify(requests).markConsumed(requestId, NOW);
    }

    @Test
    void concurrentCancellationConflictDoesNotConsumeVerifiedRequest() {
        AppointmentCancellationOtpRequest verified = new AppointmentCancellationOtpRequest(requestId, slotId,
                "hash", "salt", NOW.plusSeconds(300), 0, NOW,
                AppointmentCancellationOtpRequest.Status.VERIFIED, NOW, null);
        AppointmentSlot available = new AppointmentSlot(slotId, booked.date(), booked.startTime(), booked.endTime(),
                AppointmentSlot.Status.AVAILABLE, booked.provider(), null, null);
        when(requests.findById(requestId)).thenReturn(verified);
        when(requests.findForUpdate(requestId)).thenReturn(verified);
        when(appointments.cancelIfBooked(slotId)).thenReturn(Optional.empty());
        when(appointments.findById(slotId)).thenReturn(Optional.of(booked), Optional.of(available));
        assertThrows(CancellationOtpException.class, () -> service.cancelVerified(requestId));
        verify(requests).invalidate(requestId);
        verify(requests, never()).markConsumed(any(), any());
    }

    private AppointmentCancellationOtpRequest pending(int attempts, Instant expiresAt) {
        String salt = "salt";
        return new AppointmentCancellationOtpRequest(requestId, slotId, hash(salt, "123456"), salt, expiresAt,
                attempts, NOW, AppointmentCancellationOtpRequest.Status.PENDING, null, null);
    }

    private AppointmentCancellationOtpRequest captured() {
        var captor = org.mockito.ArgumentCaptor.forClass(AppointmentCancellationOtpRequest.class);
        verify(requests).insert(captor.capture());
        return captor.getValue();
    }

    private static String hash(String salt, String otp) {
        try {
            PBEKeySpec key = new PBEKeySpec(otp.toCharArray(), Base64.getDecoder().decode(salt), 120_000, 256);
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(key).getEncoded());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class FixedSecureRandom extends SecureRandom {
        @Override
        public int nextInt(int bound) {
            return 123456;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            java.util.Arrays.fill(bytes, (byte) 7);
        }
    }
}
