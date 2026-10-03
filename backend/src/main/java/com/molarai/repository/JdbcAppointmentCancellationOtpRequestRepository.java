package com.molarai.repository;

import com.molarai.model.AppointmentCancellationOtpRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcAppointmentCancellationOtpRequestRepository implements AppointmentCancellationOtpRequestRepository {
    private static final String LOCK_BOOKED_APPOINTMENT =
            "SELECT id FROM appointment_slots WHERE id = ? AND status = 'BOOKED' FOR UPDATE";
    private static final String INVALIDATE_ACTIVE = """
            UPDATE appointment_cancellation_otp_requests
            SET status = 'INVALIDATED'
            WHERE appointment_slot_id = ? AND status IN ('PENDING', 'VERIFIED')
            """;
    private static final String INSERT = """
            INSERT INTO appointment_cancellation_otp_requests
                (id, appointment_slot_id, otp_hash, otp_salt, expires_at, attempt_count, last_sent_at, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String FIND_FOR_UPDATE = """
            SELECT id, appointment_slot_id, otp_hash, otp_salt, expires_at, attempt_count,
                   last_sent_at, status, verified_at, consumed_at
            FROM appointment_cancellation_otp_requests
            WHERE id = ? FOR UPDATE
            """;
    private static final String FIND_BY_ID = FIND_FOR_UPDATE.replace(" FOR UPDATE", "");

    private final JdbcTemplate jdbcTemplate;

    public JdbcAppointmentCancellationOtpRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean lockBookedAppointment(UUID appointmentSlotId) {
        return !jdbcTemplate.queryForList(LOCK_BOOKED_APPOINTMENT, UUID.class, appointmentSlotId).isEmpty();
    }

    @Override
    public void invalidateActiveForAppointment(UUID appointmentSlotId) {
        jdbcTemplate.update(INVALIDATE_ACTIVE, appointmentSlotId);
    }

    @Override
    public void insert(AppointmentCancellationOtpRequest request) {
        jdbcTemplate.update(INSERT, request.id(), request.appointmentSlotId(), request.otpHash(), request.otpSalt(),
                toTimestamp(request.expiresAt()), request.attemptCount(), toTimestamp(request.lastSentAt()),
                request.status().name());
    }

    @Override
    public AppointmentCancellationOtpRequest findById(UUID requestId) {
        return jdbcTemplate.query(FIND_BY_ID, (rs, rowNum) -> map(rs), requestId)
                .stream().findFirst().orElse(null);
    }

    @Override
    public AppointmentCancellationOtpRequest findForUpdate(UUID requestId) {
        return jdbcTemplate.query(FIND_FOR_UPDATE, (rs, rowNum) -> map(rs), requestId)
                .stream().findFirst().orElse(null);
    }

    @Override
    public void incrementAttempts(UUID requestId) {
        jdbcTemplate.update("UPDATE appointment_cancellation_otp_requests SET attempt_count = attempt_count + 1 WHERE id = ?", requestId);
    }

    @Override
    public void markVerified(UUID requestId, Instant verifiedAt) {
        jdbcTemplate.update("UPDATE appointment_cancellation_otp_requests SET status = 'VERIFIED', verified_at = ? WHERE id = ?",
                toTimestamp(verifiedAt), requestId);
    }

    @Override
    public void markConsumed(UUID requestId, Instant consumedAt) {
        jdbcTemplate.update("UPDATE appointment_cancellation_otp_requests SET status = 'CONSUMED', consumed_at = ? WHERE id = ?",
                toTimestamp(consumedAt), requestId);
    }

    @Override
    public void invalidate(UUID requestId) {
        jdbcTemplate.update("UPDATE appointment_cancellation_otp_requests SET status = 'INVALIDATED' WHERE id = ?", requestId);
    }

    private AppointmentCancellationOtpRequest map(ResultSet rs) throws SQLException {
        return new AppointmentCancellationOtpRequest(
                rs.getObject("id", UUID.class), rs.getObject("appointment_slot_id", UUID.class),
                rs.getString("otp_hash"), rs.getString("otp_salt"), toInstant(rs.getTimestamp("expires_at")),
                rs.getInt("attempt_count"), toInstant(rs.getTimestamp("last_sent_at")),
                AppointmentCancellationOtpRequest.Status.valueOf(rs.getString("status")),
                toInstant(rs.getTimestamp("verified_at")), toInstant(rs.getTimestamp("consumed_at")));
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
