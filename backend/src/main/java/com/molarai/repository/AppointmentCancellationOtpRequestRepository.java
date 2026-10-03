package com.molarai.repository;

import com.molarai.model.AppointmentCancellationOtpRequest;

import java.time.Instant;
import java.util.UUID;

public interface AppointmentCancellationOtpRequestRepository {
    boolean lockBookedAppointment(UUID appointmentSlotId);

    void invalidateActiveForAppointment(UUID appointmentSlotId);

    void insert(AppointmentCancellationOtpRequest request);

    AppointmentCancellationOtpRequest findById(UUID requestId);

    AppointmentCancellationOtpRequest findForUpdate(UUID requestId);

    void incrementAttempts(UUID requestId);

    void markVerified(UUID requestId, Instant verifiedAt);

    void markConsumed(UUID requestId, Instant consumedAt);

    void invalidate(UUID requestId);
}
