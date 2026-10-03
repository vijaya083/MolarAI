CREATE TABLE appointment_slots (
    id UUID PRIMARY KEY,
    slot_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (status IN ('AVAILABLE', 'BOOKED')),
    provider VARCHAR(128),
    patient_name VARCHAR(120),
    patient_contact VARCHAR(255),
    booked_at TIMESTAMPTZ,
    CONSTRAINT chk_appointment_slot_time CHECK (end_time > start_time),
    CONSTRAINT chk_appointment_booking_state CHECK (
        (status = 'AVAILABLE' AND patient_name IS NULL AND patient_contact IS NULL AND booked_at IS NULL)
        OR
        (status = 'BOOKED' AND patient_name IS NOT NULL AND patient_contact IS NOT NULL AND booked_at IS NOT NULL)
    ),
    CONSTRAINT uq_appointment_slot UNIQUE (slot_date, start_time, end_time, provider)
);

CREATE INDEX idx_appointment_slots_available_date_time
    ON appointment_slots (slot_date, start_time)
    WHERE status = 'AVAILABLE';

INSERT INTO appointment_slots (id, slot_date, start_time, end_time, provider) VALUES
    ('6d9802e8-6fce-4e1c-9d47-5c6b1f8d1001', DATE '2030-06-10', TIME '09:00', TIME '09:30', 'Dr. Maya Chen'),
    ('6d9802e8-6fce-4e1c-9d47-5c6b1f8d1002', DATE '2030-06-10', TIME '10:00', TIME '10:30', 'Dr. Maya Chen'),
    ('6d9802e8-6fce-4e1c-9d47-5c6b1f8d1003', DATE '2030-06-10', TIME '13:00', TIME '13:30', 'Dr. Jordan Lee'),
    ('6d9802e8-6fce-4e1c-9d47-5c6b1f8d1004', DATE '2030-06-11', TIME '09:30', TIME '10:00', 'Dr. Maya Chen'),
    ('6d9802e8-6fce-4e1c-9d47-5c6b1f8d1005', DATE '2030-06-11', TIME '11:00', TIME '11:30', 'Dr. Jordan Lee'),
    ('6d9802e8-6fce-4e1c-9d47-5c6b1f8d1006', DATE '2030-06-12', TIME '14:00', TIME '14:30', 'Dr. Jordan Lee')
ON CONFLICT (id) DO NOTHING;
