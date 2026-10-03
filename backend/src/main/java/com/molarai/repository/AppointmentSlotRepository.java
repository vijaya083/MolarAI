package com.molarai.repository;

import com.molarai.model.AppointmentSlot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentSlotRepository {
    List<AppointmentSlot> findAvailableForDate(LocalDate date);

    List<AppointmentSlot> findForDate(LocalDate date);

    List<AppointmentSlot> findAvailableBetween(LocalDateTime from, LocalDateTime to);

    Optional<AppointmentSlot> findById(UUID id);

    List<AppointmentSlot> findUpcomingBookedByPatient(String patientName, String patientContact, LocalDateTime from);

    Optional<AppointmentSlot> bookIfAvailable(UUID id, String patientName, String patientContact);

    Optional<AppointmentSlot> cancelIfBooked(UUID id);

    /**
     * Inserts a generated slot. Existing rows for the same date, time, and provider are left unchanged.
     */
    int insertIfAbsent(AppointmentSlot slot);

    void lockDate(LocalDate date);

    void unlockDate(LocalDate date);
}
