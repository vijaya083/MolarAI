package com.molarai.service;

import com.molarai.controller.ApiExceptionHandler;
import com.molarai.controller.AppointmentController;
import com.molarai.dto.AppointmentSlotsResponse;
import com.molarai.model.AppointmentSlot;
import com.molarai.repository.AppointmentSlotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppointmentScheduleGenerationTest {
    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T17:00:00Z"), ZONE);
    private static final LocalDate OCTOBER_15 = LocalDate.of(2026, 10, 15);
    private static final UUID SEED_ID = UUID.fromString("6d9802e8-6fce-4e1c-9d47-5c6b1f8d1001");

    @Test
    void eligibleDateGeneratesSlotsOnceAndKeepsThemAvailable() {
        MemorySlots repository = new MemorySlots();
        AppointmentAvailabilityService service = new AppointmentAvailabilityService(repository, CLOCK);

        AppointmentSlotsResponse first = service.prepareForBooking(OCTOBER_15);
        assertEquals(AppointmentSlotsResponse.Availability.AVAILABLE, first.availability());
        assertEquals(ClinicSchedulingPolicy.MAX_DAILY_SLOTS, first.slots().size());
        assertEquals(List.of(
                LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0),
                LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(13, 30)),
                first.slots().stream().map(slot -> slot.startTime()).toList());
        assertTrue(first.slots().stream().allMatch(slot -> slot.status() == AppointmentSlot.Status.AVAILABLE));
        assertTrue(first.slots().stream().allMatch(slot ->
                !slot.startTime().isBefore(LocalTime.of(9, 0)) && !slot.endTime().isAfter(LocalTime.of(14, 0))
                        && slot.endTime().equals(slot.startTime().plusMinutes(30))));
        assertEquals(List.of(
                "Dr. Maya Chen", "Dr. Jordan Lee", "Dr. Maya Chen",
                "Dr. Jordan Lee", "Dr. Maya Chen", "Dr. Jordan Lee"),
                first.slots().stream().map(slot -> slot.provider()).toList());
        assertEquals(6, first.slots().stream().map(slot -> slot.startTime() + "|" + slot.provider()).distinct().count());
        assertEquals(repository.count(OCTOBER_15), first.slots().size());

        AppointmentSlotsResponse second = service.prepareForBooking(OCTOBER_15);
        assertEquals(first.slots().stream().map(slot -> slot.id()).toList(),
                second.slots().stream().map(slot -> slot.id()).toList());
        assertEquals(6, repository.count(OCTOBER_15));

        AppointmentSlotsResponse saturday = service.prepareForBooking(LocalDate.of(2026, 10, 17));
        assertEquals(6, saturday.slots().size());
        assertEquals(6, repository.count(LocalDate.of(2026, 10, 17)));
    }

    @Test
    void bookingAllSixSlotsIsFullyBookedAndCancellationReopensOne() {
        MemorySlots repository = new MemorySlots();
        AppointmentAvailabilityService service = new AppointmentAvailabilityService(repository, CLOCK);
        var slots = service.prepareForBooking(OCTOBER_15).slots();
        assertEquals(6, slots.size());

        var first = slots.getFirst();
        service.book(first.id(), "Sam Patient", "555-0100");
        assertThrows(AppointmentSlotUnavailableException.class,
                () -> service.book(first.id(), "Other Patient", "555-0199"));
        AppointmentSlotsResponse afterOne = service.prepareForBooking(OCTOBER_15);
        assertEquals(AppointmentSlotsResponse.Availability.AVAILABLE, afterOne.availability());
        assertEquals(5, afterOne.slots().size());
        assertTrue(afterOne.slots().stream().noneMatch(slot -> slot.id().equals(first.id())));

        for (var slot : slots.subList(1, slots.size())) {
            service.book(slot.id(), "Sam Patient", "555-0100");
        }
        AppointmentSlotsResponse full = service.prepareForBooking(OCTOBER_15);
        assertEquals(AppointmentSlotsResponse.Availability.FULLY_BOOKED, full.availability());
        assertTrue(full.slots().isEmpty());
        assertEquals(6, repository.count(OCTOBER_15));

        assertEquals(AppointmentSlot.Status.AVAILABLE, service.cancel(slots.get(3).id()).status());
        AppointmentSlotsResponse reopened = service.prepareForBooking(OCTOBER_15);
        assertEquals(AppointmentSlotsResponse.Availability.AVAILABLE, reopened.availability());
        assertEquals(1, reopened.slots().size());
        assertEquals(slots.get(3).id(), reopened.slots().getFirst().id());
        assertEquals(LocalTime.of(11, 0), reopened.slots().getFirst().startTime());
        assertEquals(6, repository.count(OCTOBER_15));
    }

    @Test
    void availabilityEndpointReturnsGeneratedSlotsForAnEmptyDate() throws Exception {
        MemorySlots repository = new MemorySlots();
        MockMvc mockMvc = mockMvc(new AppointmentAvailabilityService(repository, CLOCK));

        mockMvc.perform(get("/api/appointments/slots").param("date", "2026-10-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.slots").isNotEmpty());
        assertTrue(repository.count(OCTOBER_15) > 0);
        assertNotNull(AppointmentAvailabilityService.class
                .getMethod("prepareForBooking", LocalDate.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void existingSlotCanBeBookedOnceAndCancellationRestoresIt() throws Exception {
        MemorySlots repository = new MemorySlots();
        UUID id = UUID.randomUUID();
        repository.insertIfAbsent(new AppointmentSlot(
                id, OCTOBER_15, LocalTime.of(10, 0), LocalTime.of(10, 30),
                AppointmentSlot.Status.AVAILABLE, "Dr. Maya Chen", null, null));
        MockMvc mockMvc = mockMvc(new AppointmentAvailabilityService(repository, CLOCK));

        mockMvc.perform(get("/api/appointments/slots").param("date", "2026-10-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.slots.length()").value(1))
                .andExpect(jsonPath("$.slots[0].id").value(id.toString()))
                .andExpect(jsonPath("$.slots[0].status").value("AVAILABLE"));
        assertEquals(1, repository.count(OCTOBER_15));

        String body = """
                {"slotId":"%s","patientName":"Sam Patient","patientContact":"555-0100"}
                """.formatted(id);
        mockMvc.perform(post("/api/appointments/book").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.id").value(id.toString()));
        mockMvc.perform(post("/api/appointments/book").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/appointments/slots/{slotId}/booking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        AppointmentSlot restored = repository.findById(id).orElseThrow();
        assertEquals(AppointmentSlot.Status.AVAILABLE, restored.status());
        assertNull(restored.patientName());
        assertNull(restored.patientContact());
    }

    private static MockMvc mockMvc(AppointmentAvailabilityService service) {
        return MockMvcBuilders.standaloneSetup(new AppointmentController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void closedHolidayPastAndDistantDatesDoNotGenerateOrDeleteSeedRows() {
        MemorySlots repository = new MemorySlots();
        repository.insertIfAbsent(new AppointmentSlot(
                SEED_ID, LocalDate.of(2030, 6, 10), LocalTime.of(9, 0), LocalTime.of(9, 30),
                AppointmentSlot.Status.AVAILABLE, "Dr. Maya Chen", null, null));
        AppointmentAvailabilityService service = new AppointmentAvailabilityService(repository, CLOCK);
        AppointmentAvailabilityService holidayService = new AppointmentAvailabilityService(
                repository, CLOCK, ClinicSchedulingPolicy.defaults().withHolidays(java.util.Set.of(LocalDate.of(2026, 10, 16))));

        assertEquals(AppointmentSlotsResponse.Availability.CLOSED,
                service.prepareForBooking(LocalDate.of(2026, 10, 18)).availability());
        assertEquals(AppointmentSlotsResponse.Availability.CLOSED,
                holidayService.prepareForBooking(LocalDate.of(2026, 10, 16)).availability());
        assertEquals(AppointmentSlotsResponse.Availability.OUTSIDE_BOOKING_WINDOW,
                service.prepareForBooking(LocalDate.of(2030, 6, 10)).availability());
        assertThrows(AppointmentDateInPastException.class,
                () -> service.prepareForBooking(LocalDate.of(2026, 9, 1)));

        assertEquals(0, repository.count(LocalDate.of(2026, 10, 18)));
        assertEquals(0, repository.count(LocalDate.of(2026, 10, 16)));
        assertEquals(AppointmentSlot.Status.AVAILABLE, repository.findById(SEED_ID).orElseThrow().status());
        assertEquals(1, repository.count(LocalDate.of(2030, 6, 10)));
    }

    @Test
    void anExistingBookedSlotIsLeftUnchanged() {
        MemorySlots repository = new MemorySlots();
        LocalDate date = OCTOBER_15;
        repository.insertIfAbsent(new AppointmentSlot(
                UUID.randomUUID(), date, LocalTime.of(10, 0), LocalTime.of(10, 30),
                AppointmentSlot.Status.BOOKED, "Dr. Maya Chen", "Existing Patient", "555-0199"));
        AppointmentAvailabilityService service = new AppointmentAvailabilityService(repository, CLOCK);

        AppointmentSlotsResponse response = service.prepareForBooking(date);

        assertEquals(AppointmentSlotsResponse.Availability.FULLY_BOOKED, response.availability());
        assertTrue(response.slots().isEmpty());
        assertEquals(1, repository.count(date));
        assertEquals(AppointmentSlot.Status.BOOKED, repository.findForDate(date).getFirst().status());
        assertEquals("Existing Patient", repository.findForDate(date).getFirst().patientName());
    }

    @Test
    void concurrentGenerationInsertsEachSlotOnce() throws Exception {
        MemorySlots repository = new MemorySlots();
        AppointmentAvailabilityService service = new AppointmentAvailabilityService(repository, CLOCK);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AppointmentSlotsResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(pool.submit(() -> {
                start.await();
                return service.prepareForBooking(OCTOBER_15);
            }));
        }
        start.countDown();
        int expected = calls.getFirst().get().slots().size();
        for (Future<AppointmentSlotsResponse> call : calls) {
            assertEquals(expected, call.get().slots().size());
        }
        assertEquals(expected, repository.count(OCTOBER_15));
        pool.shutdownNow();
    }

    @Test
    void rescheduleKeepsTheOriginalWhenTheNewSlotCannotBeBooked() {
        MemorySlots repository = new MemorySlots();
        UUID original = UUID.randomUUID();
        UUID taken = UUID.randomUUID();
        LocalDate date = OCTOBER_15;
        repository.insertIfAbsent(booked(original, date, LocalTime.of(9, 0), "Ada", "555-0100"));
        repository.insertIfAbsent(new AppointmentSlot(
                taken, date, LocalTime.of(10, 0), LocalTime.of(10, 30),
                AppointmentSlot.Status.BOOKED, "Dr. Jordan Lee", "Other", "555-0111"));
        AppointmentAvailabilityService service = new AppointmentAvailabilityService(repository, CLOCK);

        assertThrows(AppointmentSlotUnavailableException.class, () -> service.reschedule(original, taken));
        AppointmentSlot unchanged = repository.findById(original).orElseThrow();
        assertEquals(AppointmentSlot.Status.BOOKED, unchanged.status());
        assertEquals("Ada", unchanged.patientName());
    }

    @Test
    void rescheduleMovesTheBookingAndReleasesTheOldSlot() {
        MemorySlots repository = new MemorySlots();
        UUID original = UUID.randomUUID();
        UUID open = UUID.randomUUID();
        repository.insertIfAbsent(booked(original, OCTOBER_15, LocalTime.of(9, 0), "Ada", "555-0100"));
        repository.insertIfAbsent(new AppointmentSlot(
                open, OCTOBER_15, LocalTime.of(10, 0), LocalTime.of(10, 30),
                AppointmentSlot.Status.AVAILABLE, "Dr. Jordan Lee", null, null));
        AppointmentAvailabilityService service = new AppointmentAvailabilityService(repository, CLOCK);

        AppointmentSlot moved = service.reschedule(original, open);

        assertEquals(open, moved.id());
        assertEquals(AppointmentSlot.Status.BOOKED, moved.status());
        assertEquals("Ada", moved.patientName());
        assertEquals(AppointmentSlot.Status.AVAILABLE, repository.findById(original).orElseThrow().status());
        assertEquals(null, repository.findById(original).orElseThrow().patientName());
    }

    private static AppointmentSlot booked(UUID id, LocalDate date, LocalTime start, String name, String contact) {
        return new AppointmentSlot(id, date, start, start.plusMinutes(30),
                AppointmentSlot.Status.BOOKED, "Dr. Maya Chen", name, contact);
    }

    private static final class MemorySlots implements AppointmentSlotRepository {
        private final ConcurrentHashMap<String, AppointmentSlot> byKey = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, String> keysById = new ConcurrentHashMap<>();

        @Override
        public List<AppointmentSlot> findAvailableForDate(LocalDate date) {
            return findForDate(date).stream().filter(slot -> slot.status() == AppointmentSlot.Status.AVAILABLE).toList();
        }

        @Override
        public List<AppointmentSlot> findForDate(LocalDate date) {
            return byKey.values().stream().filter(slot -> slot.date().equals(date)).toList();
        }

        @Override
        public List<AppointmentSlot> findAvailableBetween(LocalDateTime from, LocalDateTime to) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AppointmentSlot> findById(UUID id) {
            String key = keysById.get(id);
            return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key));
        }

        @Override
        public List<AppointmentSlot> findUpcomingBookedByPatient(String patientName, String patientContact, LocalDateTime from) {
            return byKey.values().stream()
                    .filter(slot -> slot.status() == AppointmentSlot.Status.BOOKED)
                    .filter(slot -> LocalDateTime.of(slot.date(), slot.startTime()).isAfter(from))
                    .filter(slot -> slot.patientName().equalsIgnoreCase(patientName.trim()))
                    .filter(slot -> slot.patientContact().trim().equals(patientContact.trim()))
                    .toList();
        }

        @Override
        public Optional<AppointmentSlot> bookIfAvailable(UUID id, String patientName, String patientContact) {
            String key = keysById.get(id);
            if (key == null) return Optional.empty();
            AppointmentSlot[] updated = new AppointmentSlot[1];
            byKey.compute(key, (ignored, current) -> {
                if (current == null || current.status() != AppointmentSlot.Status.AVAILABLE) return current;
                updated[0] = new AppointmentSlot(current.id(), current.date(), current.startTime(), current.endTime(),
                        AppointmentSlot.Status.BOOKED, current.provider(), patientName, patientContact);
                return updated[0];
            });
            return Optional.ofNullable(updated[0]);
        }

        @Override
        public Optional<AppointmentSlot> cancelIfBooked(UUID id) {
            String key = keysById.get(id);
            if (key == null) return Optional.empty();
            AppointmentSlot[] updated = new AppointmentSlot[1];
            byKey.compute(key, (ignored, current) -> {
                if (current == null || current.status() != AppointmentSlot.Status.BOOKED) return current;
                updated[0] = new AppointmentSlot(current.id(), current.date(), current.startTime(), current.endTime(),
                        AppointmentSlot.Status.AVAILABLE, current.provider(), null, null);
                return updated[0];
            });
            return Optional.ofNullable(updated[0]);
        }

        @Override
        public int insertIfAbsent(AppointmentSlot slot) {
            String key = slot.date() + "|" + slot.startTime() + "|" + slot.endTime() + "|" + slot.provider();
            AppointmentSlot prior = byKey.putIfAbsent(key, slot);
            if (prior != null) return 0;
            keysById.put(slot.id(), key);
            return 1;
        }

        int count(LocalDate date) {
            return findForDate(date).size();
        }

        private final ConcurrentHashMap<LocalDate, ReentrantLock> locks = new ConcurrentHashMap<>();

        @Override
        public void lockDate(LocalDate date) {
            locks.computeIfAbsent(date, ignored -> new ReentrantLock()).lock();
        }

        @Override
        public void unlockDate(LocalDate date) {
            ReentrantLock lock = locks.get(date);
            if (lock != null && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
