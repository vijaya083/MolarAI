CREATE TABLE appointment_cancellation_otp_requests (
    id UUID PRIMARY KEY,
    appointment_slot_id UUID NOT NULL REFERENCES appointment_slots(id),
    otp_hash VARCHAR(128) NOT NULL,
    otp_salt VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_sent_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'VERIFIED', 'INVALIDATED', 'CONSUMED')),
    verified_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_appointment_cancellation_otp_state CHECK (
        (status = 'PENDING' AND verified_at IS NULL AND consumed_at IS NULL)
        OR (status = 'VERIFIED' AND verified_at IS NOT NULL AND consumed_at IS NULL)
        OR (status = 'INVALIDATED' AND consumed_at IS NULL)
        OR (status = 'CONSUMED' AND consumed_at IS NOT NULL)
    )
);

CREATE INDEX idx_cancellation_otp_slot_status
    ON appointment_cancellation_otp_requests (appointment_slot_id, status);
