package com.molarai.repository;

import com.molarai.model.AppointmentCancellationOtpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcAppointmentCancellationOtpRequestRepositoryTest {
    private static final UUID REQUEST_ID = UUID.fromString("6d9802e8-6fce-4e1c-9d47-5c6b1f8d1001");
    private static final UUID SLOT_ID = UUID.fromString("10f035a4-f522-4283-86df-b75ec0a7b495");
    private static final Instant EXPIRES_AT = Instant.parse("2030-06-01T12:05:00.123456Z");
    private static final Instant LAST_SENT_AT = Instant.parse("2030-06-01T12:00:00.654321Z");

    @Test
    void insertBindsExpiryAndLastSentAsJdbcTimestamps() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcAppointmentCancellationOtpRequestRepository repository =
                new JdbcAppointmentCancellationOtpRequestRepository(jdbcTemplate);
        AppointmentCancellationOtpRequest request = pending(EXPIRES_AT, LAST_SENT_AT, null, null);

        repository.insert(request);

        org.mockito.ArgumentCaptor<Object[]> parameters = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO appointment_cancellation_otp_requests"), parameters.capture());
        Object[] values = parameters.getValue();
        assertEquals(Timestamp.from(EXPIRES_AT), values[4]);
        assertEquals(Timestamp.from(LAST_SENT_AT), values[6]);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void findByIdMapsExpiryResendAndNullableTimestamps() throws SQLException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet row = row(EXPIRES_AT, LAST_SENT_AT, null, null);
        when(jdbcTemplate.query(contains("FROM appointment_cancellation_otp_requests"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<AppointmentCancellationOtpRequest> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
        JdbcAppointmentCancellationOtpRequestRepository repository =
                new JdbcAppointmentCancellationOtpRequestRepository(jdbcTemplate);

        AppointmentCancellationOtpRequest request = repository.findById(REQUEST_ID);

        assertEquals(EXPIRES_AT, request.expiresAt());
        assertEquals(LAST_SENT_AT, request.lastSentAt());
        assertNull(request.verifiedAt());
        assertNull(request.consumedAt());
        assertEquals(0, request.attemptCount());
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(REQUEST_ID));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void findForUpdateMapsVerifiedAndConsumedTimestamps() throws SQLException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Instant verifiedAt = Instant.parse("2030-06-01T12:01:00Z");
        Instant consumedAt = Instant.parse("2030-06-01T12:02:00Z");
        ResultSet row = row(EXPIRES_AT, LAST_SENT_AT, verifiedAt, consumedAt);
        when(jdbcTemplate.query(contains("FOR UPDATE"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<AppointmentCancellationOtpRequest> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
        JdbcAppointmentCancellationOtpRequestRepository repository =
                new JdbcAppointmentCancellationOtpRequestRepository(jdbcTemplate);

        AppointmentCancellationOtpRequest request = repository.findForUpdate(REQUEST_ID);

        assertEquals(verifiedAt, request.verifiedAt());
        assertEquals(consumedAt, request.consumedAt());
    }

    @Test
    void statusUpdatesBindJdbcTimestamps() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcAppointmentCancellationOtpRequestRepository repository =
                new JdbcAppointmentCancellationOtpRequestRepository(jdbcTemplate);
        Instant verifiedAt = Instant.parse("2030-06-01T12:01:00.123456Z");
        Instant consumedAt = Instant.parse("2030-06-01T12:02:00.654321Z");

        repository.markVerified(REQUEST_ID, verifiedAt);
        repository.markConsumed(REQUEST_ID, consumedAt);

        verify(jdbcTemplate).update(contains("verified_at = ?"), eq(Timestamp.from(verifiedAt)), eq(REQUEST_ID));
        verify(jdbcTemplate).update(contains("consumed_at = ?"), eq(Timestamp.from(consumedAt)), eq(REQUEST_ID));
    }

    private static AppointmentCancellationOtpRequest pending(
            Instant expiresAt, Instant lastSentAt, Instant verifiedAt, Instant consumedAt) {
        return new AppointmentCancellationOtpRequest(REQUEST_ID, SLOT_ID, "hash", "salt", expiresAt,
                0, lastSentAt, AppointmentCancellationOtpRequest.Status.PENDING, verifiedAt, consumedAt);
    }

    private static ResultSet row(
            Instant expiresAt, Instant lastSentAt, Instant verifiedAt, Instant consumedAt) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(REQUEST_ID);
        when(row.getObject("appointment_slot_id", UUID.class)).thenReturn(SLOT_ID);
        when(row.getString("otp_hash")).thenReturn("hash");
        when(row.getString("otp_salt")).thenReturn("salt");
        when(row.getTimestamp("expires_at")).thenReturn(Timestamp.from(expiresAt));
        when(row.getInt("attempt_count")).thenReturn(0);
        when(row.getTimestamp("last_sent_at")).thenReturn(Timestamp.from(lastSentAt));
        when(row.getString("status")).thenReturn("PENDING");
        when(row.getTimestamp("verified_at")).thenReturn(verifiedAt == null ? null : Timestamp.from(verifiedAt));
        when(row.getTimestamp("consumed_at")).thenReturn(consumedAt == null ? null : Timestamp.from(consumedAt));
        return row;
    }
}
