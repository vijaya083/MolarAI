package com.molarai.repository;

import com.molarai.model.AppointmentSlot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAppointmentSlotRepository implements AppointmentSlotRepository {
    private static final String SLOT_COLUMNS = """
            id, slot_date, start_time, end_time, status, provider, patient_name, patient_contact
            """;
    private static final String AVAILABLE_FOR_DATE = """
            SELECT %s FROM appointment_slots
            WHERE status = 'AVAILABLE' AND slot_date = ?
            ORDER BY start_time, provider
            """.formatted(SLOT_COLUMNS);
    private static final String ALL_FOR_DATE = """
            SELECT %s FROM appointment_slots
            WHERE slot_date = ?
            ORDER BY start_time, provider
            """.formatted(SLOT_COLUMNS);
    private static final String AVAILABLE_IN_RANGE = """
            SELECT %s FROM appointment_slots
            WHERE status = 'AVAILABLE'
              AND (slot_date + start_time) >= ?
              AND (slot_date + start_time) < ?
            ORDER BY slot_date, start_time, provider
            """.formatted(SLOT_COLUMNS);
    private static final String FIND_BY_ID = "SELECT %s FROM appointment_slots WHERE id = ?".formatted(SLOT_COLUMNS);
    private static final String FIND_UPCOMING_BOOKED_BY_PATIENT = """
            SELECT %s FROM appointment_slots
            WHERE status = 'BOOKED'
              AND (slot_date + start_time) > ?
              AND lower(trim(patient_name)) = lower(trim(?))
              AND trim(patient_contact) = trim(?)
            ORDER BY slot_date, start_time, provider
            """.formatted(SLOT_COLUMNS);
    private static final String BOOK_IF_AVAILABLE = """
            UPDATE appointment_slots
            SET status = 'BOOKED', patient_name = ?, patient_contact = ?, booked_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 'AVAILABLE'
            RETURNING %s
            """.formatted(SLOT_COLUMNS);
    private static final String CANCEL_IF_BOOKED = """
            UPDATE appointment_slots
            SET status = 'AVAILABLE', patient_name = NULL, patient_contact = NULL, booked_at = NULL
            WHERE id = ? AND status = 'BOOKED'
            RETURNING %s
            """.formatted(SLOT_COLUMNS);
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO appointment_slots (id, slot_date, start_time, end_time, status, provider)
            VALUES (?, ?, ?, ?, 'AVAILABLE', ?)
            ON CONFLICT ON CONSTRAINT uq_appointment_slot DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<AppointmentSlot> rowMapper = this::mapSlot;

    public JdbcAppointmentSlotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AppointmentSlot> findAvailableForDate(LocalDate date) {
        return jdbcTemplate.query(AVAILABLE_FOR_DATE, rowMapper, date);
    }

    @Override
    public List<AppointmentSlot> findForDate(LocalDate date) {
        return jdbcTemplate.query(ALL_FOR_DATE, rowMapper, date);
    }

    @Override
    public List<AppointmentSlot> findAvailableBetween(LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query(AVAILABLE_IN_RANGE, rowMapper, from, to);
    }

    @Override
    public Optional<AppointmentSlot> findById(UUID id) {
        return jdbcTemplate.query(FIND_BY_ID, rowMapper, id).stream().findFirst();
    }

    @Override
    public List<AppointmentSlot> findUpcomingBookedByPatient(String patientName, String patientContact, LocalDateTime from) {
        return jdbcTemplate.query(FIND_UPCOMING_BOOKED_BY_PATIENT, rowMapper, from, patientName, patientContact);
    }

    @Override
    public Optional<AppointmentSlot> bookIfAvailable(UUID id, String patientName, String patientContact) {
        return jdbcTemplate.query(BOOK_IF_AVAILABLE, rowMapper, patientName, patientContact, id)
                .stream().findFirst();
    }

    @Override
    public Optional<AppointmentSlot> cancelIfBooked(UUID id) {
        return jdbcTemplate.query(CANCEL_IF_BOOKED, rowMapper, id).stream().findFirst();
    }

    @Override
    public int insertIfAbsent(AppointmentSlot slot) {
        return jdbcTemplate.update(INSERT_IF_ABSENT,
                slot.id(), slot.date(), slot.startTime(), slot.endTime(), slot.provider());
    }

    @Override
    public void lockDate(LocalDate date) {
        // pg_advisory_xact_lock returns void. Reading that result as a Long fails in the PostgreSQL driver.
        // The lock is held by this connection until the surrounding transaction commits or rolls back.
        jdbcTemplate.execute((Connection connection) -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, date.toEpochDay());
                statement.execute();
            }
            return null;
        });
    }

    @Override
    public void unlockDate(LocalDate date) {
        // The transaction-scoped advisory lock is released when the surrounding transaction ends.
    }

    private AppointmentSlot mapSlot(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AppointmentSlot(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("slot_date", LocalDate.class),
                resultSet.getObject("start_time", LocalTime.class),
                resultSet.getObject("end_time", LocalTime.class),
                AppointmentSlot.Status.valueOf(resultSet.getString("status")),
                resultSet.getString("provider"),
                resultSet.getString("patient_name"),
                resultSet.getString("patient_contact"));
    }
}
