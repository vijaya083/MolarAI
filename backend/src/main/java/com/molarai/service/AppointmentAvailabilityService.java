package com.molarai.service;

import com.molarai.dto.AppointmentSlotsResponse;
import com.molarai.model.AppointmentSlot;
import com.molarai.repository.AppointmentSlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AppointmentAvailabilityService {
    private final AppointmentSlotRepository repository;
    private final Clock clock;
    private final ClinicSchedulingPolicy schedule;

    public AppointmentAvailabilityService(AppointmentSlotRepository repository, Clock clock) {
        this(repository, clock, ClinicSchedulingPolicy.defaults());
    }

    @Autowired
    public AppointmentAvailabilityService(
            AppointmentSlotRepository repository, Clock clock, ClinicSchedulingPolicy schedule) {
        this.repository = repository;
        this.clock = clock;
        this.schedule = schedule;
    }

    /**
     * Returns bookable slots for a date. An eligible open day with no rows gets a generated schedule.
     * Existing rows, including booked appointments, are not overwritten.
     */
    @Transactional
    public AppointmentSlotsResponse prepareForBooking(LocalDate date) {
        if (date == null) throw new InvalidAppointmentRequestException("date is required");
        validateNotPast(date);
        LocalDate today = LocalDate.now(clock);
        if (schedule.isOutsideBookingHorizon(date, today)) {
            return new AppointmentSlotsResponse(List.of(), AppointmentSlotsResponse.Availability.OUTSIDE_BOOKING_WINDOW);
        }
        if (schedule.isClosed(date)) {
            return new AppointmentSlotsResponse(List.of(), AppointmentSlotsResponse.Availability.CLOSED);
        }
        repository.lockDate(date);
        try {
            List<AppointmentSlot> stored = repository.findForDate(date);
            if (stored.isEmpty()) {
                for (AppointmentSlot slot : schedule.generate(date, LocalDateTime.now(clock))) {
                    repository.insertIfAbsent(slot);
                }
                stored = repository.findForDate(date);
            }
            return AppointmentSlotsResponse.fromStored(stored);
        } finally {
            repository.unlockDate(date);
        }
    }

    @Transactional(readOnly = true)
    public AppointmentSlot findPublicSlot(UUID slotId) {
        if (slotId == null) throw new InvalidAppointmentRequestException("slotId is required");
        return repository.findById(slotId).orElseThrow(AppointmentSlotNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<AppointmentSlot> availableOn(LocalDate date) {
        if (date == null) throw new InvalidAppointmentRequestException("date is required");
        validateNotPast(date);
        return repository.findAvailableForDate(date);
    }

    /** Every stored slot on the date, including booked rows, so missing data is distinct from a full schedule. */
    @Transactional(readOnly = true)
    public List<AppointmentSlot> allOn(LocalDate date) {
        if (date == null) throw new InvalidAppointmentRequestException("date is required");
        validateNotPast(date);
        return repository.findForDate(date);
    }

    @Transactional(readOnly = true)
    public List<AppointmentSlot> availableBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            throw new InvalidAppointmentRequestException("from and to are required");
        }
        if (!from.isBefore(to)) {
            throw new InvalidAppointmentRequestException("from must be earlier than to");
        }
        LocalDate today = LocalDate.now(clock);
        if (from.toLocalDate().isBefore(today)) {
            throw new AppointmentDateInPastException(from.toLocalDate(), today);
        }
        if (from.isBefore(LocalDateTime.now(clock))) {
            throw new InvalidAppointmentRequestException("from must not be in the past");
        }
        return repository.findAvailableBetween(from, to);
    }

    @Transactional
    public AppointmentSlot book(UUID slotId, String patientName, String patientContact) {
        if (slotId == null) throw new InvalidAppointmentRequestException("slotId is required");
        String normalizedName = normalize(patientName, "patientName");
        String normalizedContact = normalize(patientContact, "patientContact");

        AppointmentSlot current = repository.findById(slotId).orElseThrow(AppointmentSlotNotFoundException::new);
        ensureBookable(current);
        if (current.status() != AppointmentSlot.Status.AVAILABLE) {
            throw new AppointmentSlotUnavailableException();
        }
        return repository.bookIfAvailable(slotId, normalizedName, normalizedContact)
                .orElseThrow(() -> classifyMissingUpdate(slotId, false));
    }

    @Transactional
    public AppointmentSlot cancel(UUID slotId) {
        if (slotId == null) throw new InvalidAppointmentRequestException("slotId is required");
        AppointmentSlot current = repository.findById(slotId).orElseThrow(AppointmentSlotNotFoundException::new);
        if (current.status() != AppointmentSlot.Status.BOOKED) {
            throw new AppointmentSlotUnavailableException();
        }
        return repository.cancelIfBooked(slotId)
                .orElseThrow(() -> classifyMissingUpdate(slotId, true));
    }

    @Transactional(readOnly = true)
    public List<AppointmentSlot> findUpcomingAppointments(String patientName, String patientContact) {
        String normalizedName = normalize(patientName, "patientName");
        String normalizedContact = normalize(patientContact, "patientContact");
        return repository.findUpcomingBookedByPatient(
                normalizedName, normalizedContact, LocalDateTime.now(clock));
    }

    /**
     * Books the new slot first, then releases the old one. Both updates share this transaction,
     * so a failure leaves the original appointment booked.
     */
    @Transactional
    public AppointmentSlot reschedule(UUID currentSlotId, UUID newSlotId) {
        if (currentSlotId == null || newSlotId == null) {
            throw new InvalidAppointmentRequestException("slotId is required");
        }
        if (currentSlotId.equals(newSlotId)) {
            throw new InvalidAppointmentRequestException("choose a different appointment slot");
        }
        AppointmentSlot current = repository.findById(currentSlotId).orElseThrow(AppointmentSlotNotFoundException::new);
        if (current.status() != AppointmentSlot.Status.BOOKED) {
            throw new AppointmentSlotUnavailableException();
        }
        AppointmentSlot target = repository.findById(newSlotId).orElseThrow(AppointmentSlotNotFoundException::new);
        ensureBookable(target);
        if (target.status() != AppointmentSlot.Status.AVAILABLE) {
            throw new AppointmentSlotUnavailableException();
        }
        AppointmentSlot booked = repository.bookIfAvailable(newSlotId, current.patientName(), current.patientContact())
                .orElseThrow(AppointmentSlotUnavailableException::new);
        repository.cancelIfBooked(currentSlotId).orElseThrow(AppointmentSlotUnavailableException::new);
        return booked;
    }

    private void ensureBookable(AppointmentSlot slot) {
        if (slot.date().isBefore(LocalDate.now(clock))
                || !LocalDateTime.of(slot.date(), slot.startTime()).isAfter(LocalDateTime.now(clock))) {
            throw new InvalidAppointmentRequestException("appointment time must be in the future");
        }
        if (!slot.startTime().isBefore(slot.endTime())) {
            throw new InvalidAppointmentRequestException("appointment end time must be after start time");
        }
    }

    private void validateNotPast(LocalDate date) {
        LocalDate today = LocalDate.now(clock);
        if (date.isBefore(today)) {
            throw new AppointmentDateInPastException(date, today);
        }
    }

    private RuntimeException classifyMissingUpdate(UUID slotId, boolean cancellation) {
        AppointmentSlot latest = repository.findById(slotId).orElseThrow(AppointmentSlotNotFoundException::new);
        if (cancellation && latest.status() == AppointmentSlot.Status.AVAILABLE) return latestStateConflict();
        if (!cancellation && latest.status() == AppointmentSlot.Status.BOOKED) {
            return new AppointmentSlotUnavailableException();
        }
        return latestStateConflict();
    }

    private RuntimeException latestStateConflict() {
        return new AppointmentSlotUnavailableException();
    }

    private String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidAppointmentRequestException(field + " must not be blank");
        }
        return value.trim();
    }
}
