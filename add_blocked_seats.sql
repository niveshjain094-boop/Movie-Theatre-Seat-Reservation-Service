-- Pre-books a diagonal pattern of seats on every show, as if another customer
-- had already bought them. Same pattern as the old demo grid.
-- Safe to run more than once, and safe for shows that already have real bookings:
--   * only seats that are currently AVAILABLE are taken
--   * a show that already has a demo booking is skipped
-- Run it again after you add new shows.

DO $$
DECLARE
    sh  RECORD;
    uid BIGINT;
    bid BIGINT;
    cnt INT;
BEGIN
    -- A demo customer that owns the blocked seats (its password is not a valid hash,
    -- so nobody can log in as it).
    INSERT INTO users (name, email, password, role)
    VALUES ('Demo Customer', 'demo@bms.local', '!login-disabled!', 'USER')
    ON CONFLICT (email) DO NOTHING;
    SELECT id INTO uid FROM users WHERE email = 'demo@bms.local';

    FOR sh IN SELECT id, screen_id, ticket_price FROM shows LOOP
        -- make sure every seat of the show has an inventory row
        INSERT INTO show_seats (show_id, seat_id, status, version)
        SELECT sh.id, s.id, 'AVAILABLE', 0 FROM seats s WHERE s.screen_id = sh.screen_id
        ON CONFLICT (show_id, seat_id) DO NOTHING;

        IF EXISTS (SELECT 1 FROM bookings WHERE user_id = uid AND show_id = sh.id) THEN
            CONTINUE;
        END IF;

        SELECT COUNT(*) INTO cnt
        FROM show_seats ss JOIN seats s ON s.id = ss.seat_id
        WHERE ss.show_id = sh.id AND ss.status = 'AVAILABLE'
          AND (ascii(s.seat_row) + s.seat_col) % 6 = 0;
        IF cnt = 0 THEN CONTINUE; END IF;

        INSERT INTO bookings (user_id, show_id, total_price, status, idempotency_key, booked_at, confirmed_at)
        VALUES (uid, sh.id, (COALESCE(sh.ticket_price, 0) * cnt)::numeric, 'CONFIRMED',
                gen_random_uuid(), now(), now())
        RETURNING id INTO bid;

        INSERT INTO booking_seats (booking_id, seat_id)
        SELECT bid, s.id
        FROM show_seats ss JOIN seats s ON s.id = ss.seat_id
        WHERE ss.show_id = sh.id AND ss.status = 'AVAILABLE'
          AND (ascii(s.seat_row) + s.seat_col) % 6 = 0;

        UPDATE show_seats ss
        SET status = 'BOOKED', booking_id = bid, hold_expires_at = NULL
        FROM seats s
        WHERE s.id = ss.seat_id AND ss.show_id = sh.id AND ss.status = 'AVAILABLE'
          AND (ascii(s.seat_row) + s.seat_col) % 6 = 0;
    END LOOP;
END $$;
