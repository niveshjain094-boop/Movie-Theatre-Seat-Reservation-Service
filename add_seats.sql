-- Adds a 10 x 12 seat layout (rows A-J, seats 1-12) to EVERY screen.
-- Safe to run more than once: it never duplicates a seat that already exists
-- (existing seats like A1-A5 are kept as they are).

INSERT INTO seats (seat_number, seat_row, seat_col, seat_type, screen_id)
SELECT r.rname || c.cnum::text,
       r.rname,
       c.cnum,
       CASE WHEN r.rname IN ('I', 'J') THEN 'VIP'
            WHEN r.rname IN ('G', 'H') THEN 'PREMIUM'
            ELSE 'REGULAR' END,
       sc.id
FROM screens sc
CROSS JOIN (SELECT unnest(ARRAY['A','B','C','D','E','F','G','H','I','J']) AS rname) r
CROSS JOIN (SELECT generate_series(1, 12) AS cnum) c
WHERE NOT EXISTS (
    SELECT 1 FROM seats s
    WHERE s.screen_id = sc.id AND s.seat_row = r.rname AND s.seat_col = c.cnum
);

-- Keep the screens' seat count in sync
UPDATE screens sc
SET total_seats = (SELECT COUNT(*) FROM seats s WHERE s.screen_id = sc.id);
