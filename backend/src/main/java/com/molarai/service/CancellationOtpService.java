package com.molarai.service;

import com.molarai.model.AppointmentCancellationOtpRequest;
import com.molarai.model.AppointmentSlot;
import com.molarai.repository.AppointmentCancellationOtpRequestRepository;
import com.molarai.repository.AppointmentSlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class CancellationOtpService {
    private final AppointmentSlotRepository appointmentRepository;
    private final AppointmentCancellationOtpRequestRepository otpRepository;
    private final AppointmentAvailabilityService appointmentService;
    private final OtpDeliveryProvider deliveryProvider;
    private final CancellationOtpPolicy policy;
    private final Clock clock;
    private final SecureRandom random;

    public CancellationOtpService(
            AppointmentSlotRepository appointmentRepository,
            AppointmentCancellationOtpRequestRepository otpRepository,
            AppointmentAvailabilityService appointmentService,
            OtpDeliveryProvider deliveryProvider,
            CancellationOtpPolicy policy,
            Clock clock,
            SecureRandom random) {
        this.appointmentRepository = appointmentRepository;
        this.otpRepository = otpRepository;
        this.appointmentService = appointmentService;
        this.deliveryProvider = deliveryProvider;
        this.policy = policy;
        this.clock = clock;
        this.random = random;
    }

    @Transactional
    public IssuedOtp issue(UUID appointmentSlotId, String patientName, String patientContact) {
        AppointmentSlot slot = matchingBookedSlot(appointmentSlotId, patientName, patientContact);
        if (!otpRepository.lockBookedAppointment(slot.id())) throw genericRequestError();
        return issueLocked(slot);
    }

    @Transactional
    public IssuedOtp resend(UUID requestId) {
        AppointmentCancellationOtpRequest previous = readableRequest(requestId);
        if (previous.status() != AppointmentCancellationOtpRequest.Status.PENDING) {
            throw new CancellationOtpException(CancellationOtpException.Reason.ALREADY_USED,
                    "This cancellation verification is no longer active.");
        }
        Instant now = Instant.now(clock);
        if (previous.lastSentAt().plus(policy.resendCooldown()).isAfter(now)) {
            throw new CancellationOtpException(CancellationOtpException.Reason.COOLDOWN,
                    "Please wait before requesting another code.");
        }
        AppointmentSlot slot = appointmentRepository.findById(previous.appointmentSlotId()).orElseThrow(this::genericRequestError);
        if (slot.status() != AppointmentSlot.Status.BOOKED) throw conflict();
        if (!otpRepository.lockBookedAppointment(slot.id())) throw conflict();
        previous = requiredRequest(requestId);
        if (previous.status() != AppointmentCancellationOtpRequest.Status.PENDING) {
            throw new CancellationOtpException(CancellationOtpException.Reason.ALREADY_USED,
                    "This cancellation verification is no longer active.");
        }
        return issueLocked(slot);
    }

    @Transactional
    public void verify(UUID requestId, String otp) {
        AppointmentCancellationOtpRequest request = requiredRequest(requestId);
        Instant now = Instant.now(clock);
        if (request.status() != AppointmentCancellationOtpRequest.Status.PENDING) {
            throw new CancellationOtpException(CancellationOtpException.Reason.ALREADY_USED,
                    "This cancellation verification is no longer active.");
        }
        if (!now.isBefore(request.expiresAt())) {
            otpRepository.invalidate(requestId);
            throw new CancellationOtpException(CancellationOtpException.Reason.EXPIRED,
                    "This verification code has expired. Please request a new code.");
        }
        if (request.attemptCount() >= policy.maxAttempts()) {
            otpRepository.invalidate(requestId);
            throw new CancellationOtpException(CancellationOtpException.Reason.TOO_MANY_ATTEMPTS,
                    "Too many incorrect codes. Please request a new code.");
        }
        if (!secureEquals(request.otpHash(), hash(request.otpSalt(), otp))) {
            otpRepository.incrementAttempts(requestId);
            if (request.attemptCount() + 1 >= policy.maxAttempts()) otpRepository.invalidate(requestId);
            throw new CancellationOtpException(CancellationOtpException.Reason.INVALID_OTP,
                    "That verification code is incorrect.");
        }
        otpRepository.markVerified(requestId, now);
    }

    @Transactional
    public AppointmentSlot cancelVerified(UUID requestId) {
        AppointmentCancellationOtpRequest request = readableRequest(requestId);
        if (request.status() != AppointmentCancellationOtpRequest.Status.VERIFIED) {
            throw new CancellationOtpException(CancellationOtpException.Reason.INVALID_REQUEST,
                    "The appointment has not been verified for cancellation.");
        }
        if (!Instant.now(clock).isBefore(request.expiresAt())) {
            otpRepository.invalidate(requestId);
            throw new CancellationOtpException(CancellationOtpException.Reason.EXPIRED,
                    "This verification has expired. Please start again.");
        }
        if (!otpRepository.lockBookedAppointment(request.appointmentSlotId())) throw conflict();
        request = requiredRequest(requestId);
        if (request.status() != AppointmentCancellationOtpRequest.Status.VERIFIED) {
            throw new CancellationOtpException(CancellationOtpException.Reason.ALREADY_USED,
                    "This cancellation verification is no longer active.");
        }
        AppointmentSlot cancelled;
        try {
            cancelled = appointmentService.cancel(request.appointmentSlotId());
        } catch (AppointmentSlotUnavailableException exception) {
            otpRepository.invalidate(requestId);
            throw conflict();
        }
        otpRepository.markConsumed(requestId, Instant.now(clock));
        return cancelled;
    }

    private IssuedOtp issueLocked(AppointmentSlot slot) {
        Instant now = Instant.now(clock);
        String otp = String.format("%06d", random.nextInt(1_000_000));
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        String salt = Base64.getEncoder().encodeToString(saltBytes);
        UUID requestId = UUID.randomUUID();
        Instant expiresAt = now.plus(policy.expiry());
        otpRepository.invalidateActiveForAppointment(slot.id());
        otpRepository.insert(new AppointmentCancellationOtpRequest(requestId, slot.id(), hash(salt, otp), salt,
                expiresAt, 0, now, AppointmentCancellationOtpRequest.Status.PENDING, null, null));
        OtpDeliveryProvider.DeliveryResult delivery = deliveryProvider.send(slot.patientContact(), otp);
        return new IssuedOtp(requestId, expiresAt, now.plus(policy.resendCooldown()), delivery.developmentCode());
    }

    private AppointmentSlot matchingBookedSlot(UUID id, String name, String contact) {
        String normalizedName = normalize(name);
        String normalizedContact = normalize(contact);
        AppointmentSlot slot = appointmentRepository.findById(id).orElseThrow(this::genericRequestError);
        if (slot.status() != AppointmentSlot.Status.BOOKED
                || slot.patientName() == null || !slot.patientName().trim().equalsIgnoreCase(normalizedName)
                || slot.patientContact() == null || !slot.patientContact().trim().equals(normalizedContact)) {
            throw genericRequestError();
        }
        return slot;
    }

    private AppointmentCancellationOtpRequest requiredRequest(UUID id) {
        if (id == null) throw genericRequestError();
        AppointmentCancellationOtpRequest request = otpRepository.findForUpdate(id);
        if (request == null) throw genericRequestError();
        return request;
    }

    private AppointmentCancellationOtpRequest readableRequest(UUID id) {
        if (id == null) throw genericRequestError();
        AppointmentCancellationOtpRequest request = otpRepository.findById(id);
        if (request == null) throw genericRequestError();
        return request;
    }

    private CancellationOtpException genericRequestError() {
        return new CancellationOtpException(CancellationOtpException.Reason.INVALID_REQUEST,
                "We could not start cancellation verification for that appointment.");
    }

    private CancellationOtpException conflict() {
        return new CancellationOtpException(CancellationOtpException.Reason.CONFLICT,
                "That appointment is no longer available for cancellation.");
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) throw genericRequestError();
        return value.trim();
    }

    private String hash(String salt, String otp) {
        try {
            PBEKeySpec key = new PBEKeySpec(otp.toCharArray(), Base64.getDecoder().decode(salt), 120_000, 256);
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(key).getEncoded());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("OTP hashing is unavailable", exception);
        }
    }

    private boolean secureEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    public record IssuedOtp(UUID requestId, Instant expiresAt, Instant resendAvailableAt, String developmentCode) {
    }
}
