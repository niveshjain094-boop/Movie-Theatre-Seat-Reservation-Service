-- V2: seat holds (AVAILABLE -> HELD -> BOOKED), booking lifecycle timestamps,
--     and DB-level guards for concurrent seat access.
-- Safe to run on a database that already contains V1 data.

-- ---------------------------------------------------------------------------
-- 1. Vocabulary: seat status HOLD -> HELD; a held booking is PENDING.
-- ---------------------------------------------------------------------------
UPDATE show_seats SET status = 'HELD'    WHERE status = 'HOLD';
UPDATE bookings   SET status = 'PENDING' WHERE status = 'HOLD';

-- ---------------------------------------------------------------------------
-- 2. New columns
-- ---------------------------------------------------------------------------
ALTER TABLE bookings
  ADD COLUMN IF NOT EXISTS hold_expires_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS confirmed_at    TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS cancelled_at    TIMESTAMPTZ;

-- The booking that currently owns the seat for this show (held or booked).
-- A show_seat row points at one booking at most, so a seat can never belong
-- to two active bookings at once.
ALTER TABLE show_seats
  ADD COLUMN IF NOT EXISTS booking_id BIGINT REFERENCES bookings(id);

-- ---------------------------------------------------------------------------
-- 3. Backfill existing data
-- ---------------------------------------------------------------------------
-- 3a. Every show gets an inventory row per seat of its screen
--     (previously nothing created them, so legacy shows had none).
INSERT INTO show_seats (show_id, seat_id, status, version)
SELECT sh.id, s.id, 'AVAILABLE', 0
FROM shows sh
JOIN seats s ON s.screen_id = sh.screen_id
ON CONFLICT (show_id, seat_id) DO NOTHING;

-- 3b. Seats of CONFIRMED bookings become BOOKED and point at their booking.
UPDATE show_seats ss
SET status = 'BOOKED', booking_id = b.id, hold_expires_at = NULL
FROM bookings b
JOIN booking_seats bs ON bs.booking_id = b.id
WHERE b.status = 'CONFIRMED'
  AND ss.show_id = b.show_id
  AND ss.seat_id = bs.seat_id;

-- 3c. Rows that cannot be tied to a booking are reset (legacy holds never had
--     an owner; BOOKED rows without a confirmed booking are orphans).
UPDATE show_seats
SET status = 'AVAILABLE', hold_expires_at = NULL, booking_id = NULL
WHERE booking_id IS NULL AND status <> 'AVAILABLE';

UPDATE show_seats
SET hold_expires_at = NULL
WHERE status = 'AVAILABLE' AND hold_expires_at IS NOT NULL;

-- 3d. Booking timestamps. booked_at was "creation time"; for confirmed
--     bookings that is also the confirmation time. Legacy cancellations have
--     no recorded time.
UPDATE bookings SET confirmed_at = booked_at::timestamptz
WHERE status = 'CONFIRMED' AND confirmed_at IS NULL AND booked_at IS NOT NULL;

UPDATE bookings SET status = 'EXPIRED'
WHERE status = 'PENDING' AND hold_expires_at IS NULL;

-- ---------------------------------------------------------------------------
-- 4. Constraints: invalid states cannot be written, whatever the app does.
-- ---------------------------------------------------------------------------
ALTER TABLE show_seats
  ADD CONSTRAINT ck_show_seats_status
    CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
  ADD CONSTRAINT ck_show_seats_state
    CHECK (
         (status = 'AVAILABLE' AND booking_id IS NULL     AND hold_expires_at IS NULL)
      OR (status = 'HELD'      AND booking_id IS NOT NULL AND hold_expires_at IS NOT NULL)
      OR (status = 'BOOKED'    AND booking_id IS NOT NULL)
    );

ALTER TABLE bookings
  ADD CONSTRAINT ck_bookings_status
    CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
  ADD CONSTRAINT ck_bookings_pending_has_expiry
    CHECK (status <> 'PENDING' OR hold_expires_at IS NOT NULL);

-- ---------------------------------------------------------------------------
-- 5. Indexes for the hold/expiry/history paths
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_show_seats_booking ON show_seats(booking_id);
CREATE INDEX IF NOT EXISTS idx_show_seats_hold_expiry
  ON show_seats(hold_expires_at) WHERE status = 'HELD';
CREATE INDEX IF NOT EXISTS idx_bookings_pending_expiry
  ON bookings(hold_expires_at) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_bookings_user_booked_at
  ON bookings(user_id, booked_at DESC);
