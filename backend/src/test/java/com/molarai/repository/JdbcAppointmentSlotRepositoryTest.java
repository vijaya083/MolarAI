package com.molarai.repository;

import com.molarai.model.AppointmentSlot;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JdbcAppointmentSlotRepositoryTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void findsOnlyAvailableSlotsAndMapsRows() throws SQLException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet row = row("AVAILABLE");
        when(jdbcTemplate.query(contains("status = 'AVAILABLE'"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<AppointmentSlot> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
        JdbcAppointmentSlotRepository repository = new JdbcAppointmentSlotRepository(jdbcTemplate);

        var date = LocalDate.of(2030, 6, 10);
        List<AppointmentSlot> slots = repository.findAvailableForDate(date);

        assertEquals(1, slots.size());
        assertEquals(AppointmentSlot.Status.AVAILABLE, slots.getFirst().status());
        assertEquals(date, slots.getFirst().date());
        verify(jdbcTemplate).query(contains("ORDER BY start_time"), any(RowMapper.class), eq(date));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void bookingUsesAtomicAvailableStatusUpdate() throws SQLException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet row = row("BOOKED");
        when(jdbcTemplate.query(contains("UPDATE appointment_slots"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<AppointmentSlot> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
        JdbcAppointmentSlotRepository repository = new JdbcAppointmentSlotRepository(jdbcTemplate);

        UUID id = UUID.randomUUID();
        assertEquals(AppointmentSlot.Status.BOOKED,
                repository.bookIfAvailable(id, "Sam", "555-0100").orElseThrow().status());
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq("Sam"), eq("555-0100"), eq(id));
        assertTrue(sql.getValue().contains("WHERE id = ? AND status = 'AVAILABLE'"));
        assertTrue(sql.getValue().contains("RETURNING"));
    }

    @Test
    void generatedSlotsUseConflictSafeInsertion() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(), any(), any(), any(), any())).thenReturn(1);
        JdbcAppointmentSlotRepository repository = new JdbcAppointmentSlotRepository(jdbcTemplate);
        AppointmentSlot slot = new AppointmentSlot(UUID.randomUUID(), LocalDate.of(2026, 10, 15),
                LocalTime.of(10, 0), LocalTime.of(10, 30), AppointmentSlot.Status.AVAILABLE, "Dr. Maya Chen", null, null);

        assertEquals(1, repository.insertIfAbsent(slot));
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(slot.id()), eq(slot.date()), eq(slot.startTime()), eq(slot.endTime()), eq(slot.provider()));
        assertTrue(sql.getValue().contains("ON CONFLICT ON CONSTRAINT uq_appointment_slot DO NOTHING"));
    }

    @Test
    void dateLockExecutesWithoutMappingTheVoidResultToLong() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet voidResult = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(voidResult);
        when(voidResult.next()).thenReturn(true);
        when(voidResult.getLong(anyInt())).thenThrow(new SQLException("Bad value for type long : "));
        when(voidResult.getObject(anyInt())).thenThrow(new SQLException("Bad value for type long : "));
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        LocalDate date = LocalDate.of(2026, 10, 15);
        JdbcAppointmentSlotRepository repository = new JdbcAppointmentSlotRepository(new JdbcTemplate(dataSource));

        assertDoesNotThrow(() -> repository.lockDate(date));
        verify(connection).prepareStatement("SELECT pg_advisory_xact_lock(?)");
        verify(statement).setLong(1, date.toEpochDay());
        verify(statement).execute();
        verify(statement, never()).executeQuery();
        verify(voidResult, never()).getLong(anyInt());
    }

    @Test
    void dateUnlockLeavesTheTransactionScopedLockInPlace() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        new JdbcAppointmentSlotRepository(jdbcTemplate).unlockDate(LocalDate.of(2026, 10, 15));
        verifyNoInteractions(jdbcTemplate);
    }

    private ResultSet row(String status) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(UUID.fromString("6d9802e8-6fce-4e1c-9d47-5c6b1f8d1001"));
        when(row.getObject("slot_date", LocalDate.class)).thenReturn(LocalDate.of(2030, 6, 10));
        when(row.getObject("start_time", LocalTime.class)).thenReturn(LocalTime.of(9, 0));
        when(row.getObject("end_time", LocalTime.class)).thenReturn(LocalTime.of(9, 30));
        when(row.getString("status")).thenReturn(status);
        when(row.getString("provider")).thenReturn("Dr. Maya Chen");
        when(row.getString("patient_name")).thenReturn(status.equals("BOOKED") ? "Sam" : null);
        when(row.getString("patient_contact")).thenReturn(status.equals("BOOKED") ? "555-0100" : null);
        return row;
    }
}
