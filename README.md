# BookMyShow Clone — Concurrency-Safe Movie Ticket Booking

Full-stack movie ticket booking system: **Spring Boot 3 / Java 21** backend, **PostgreSQL**, **React 19 + Vite** frontend.

This is a backend-hardened redesign of the original prototype. The headline fix is **race-free seat booking** via a per-show seat inventory table with pessimistic locking, DB uniqueness, and idempotent retries — plus JWT auth, Flyway migrations, pagination, and regression tests.

## What changed from the prototype

| Area | Before | Now |
|---|---|---|
| Booking | check-then-act, double-books under load | `show_seats` inventory + `PESSIMISTIC_WRITE` + `UNIQUE(show_id, seat_id)` + `Idempotency-Key` |
| Auth | plaintext password, `GET /users` leaked hashes | BCrypt-12, JWT (access token), no password in any response |
| Movies API | duplicate `@GetMapping("/genre/{x}")` — app failed startup | `/genre/{genre}` + `/language/{language}`, paginated |
| Errors | `500` returned as `400`, stack leaks | RFC 7807 `ProblemDetail` (`409` conflict, `404` not-found, `500` real) |
| DB | `ddl-auto=update` + `TRUNCATE` seed on boot | Flyway `V1__baseline.sql`, `validate`, indexes (incl. `pg_trgm`), Hikari pool |
| Lists | unbounded `findAll()` | `Page<T>` everywhere |
| CORS | `allowedOrigins("*")` | allowlist via `CORS_ALLOWED_ORIGINS` |
| Docker | ran as root, no healthcheck | non-root `app` user, `HEALTHCHECK /actuator/health` |

## Architecture

```mermaid
flowchart LR
    FE[React SPA :5173] --> API[Spring Boot API :8080]
    API --> PG[(PostgreSQL)]
    API -->|JWT| AUTH[Security filter chain]
    subgraph booking [Booking path]
      API -->|SELECT FOR UPDATE| INV[show_seats]
      INV --> BK[bookings + booking_seats]
    end
```

Key tables: `users`, `movies`, `theaters`, `screens`, `seats`, `shows`, **`show_seats(show_id, seat_id UNIQUE, status)`**, `bookings(idempotency_key UNIQUE)`, `booking_seats`.

## API (base `/api` — legacy — and `/api/v1`)

| Method | Endpoint | Auth | Notes |
|---|---|---|---|
| POST | `/users/register` | no | `201`, BCrypt hash, validates email/password≥8 |
| POST | `/users/login` | no | returns `{ accessToken, userId, email, role }` |
| GET | `/users`, `/users/{id}` | — | paginated, **never returns password** |
| GET | `/movies?page=&size=` | no | paginated |
| GET | `/movies/{id}`, `/movies/search?title=`, `/movies/genre/{g}`, `/movies/language/{l}` | no | |
| GET | `/shows`, `/shows/{id}`, `/shows/movie/{id}`, `/shows/movie/{id}/date?date=` | no | |
| GET | `/theaters`, `/theaters/{id}`, `/theaters/city/{cityId}` | no | |
| GET | `/cities`, `/cities/{id}` | no | |
| POST | `/bookings/hold` | **yes** (`Bearer`) | `201`, holds seats for 5 min → `PENDING` booking. Body `{userId, showId, seatIds[]}`; supports `Idempotency-Key` |
| POST | `/bookings/{id}/confirm` | yes | `200` if the hold is valid and unexpired; `409` if expired / cancelled / lost |
| POST | `/bookings` | yes | `201`, hold + confirm in one step (unchanged contract), supports `Idempotency-Key` |
| GET | `/bookings/{id}`, `/bookings/user/{uid}?page=` | yes, **owner only** | history of all statuses, newest first |
| PUT | `/bookings/{id}/cancel` | yes, owner only | cancels a `CONFIRMED` booking or a `PENDING` hold, seats → `AVAILABLE`; `409` if already `EXPIRED` |
| GET | `/bookings/show/{showId}/available-seats` | no | lapsed holds count as available |
| GET | `/bookings/show/{showId}/seats` | no | every seat with `AVAILABLE` / `HELD` / `BOOKED` |

Error shape (RFC 7807): `{ type, title, status, detail, timestamp }`. Double-book → `409 Seat with id X is already booked`.

## Booking concurrency design

1. Client sends `POST /bookings { userId, showId, seatIds, idempotencyKey? }` (or `Idempotency-Key` header).
2. Server: idempotency short-circuit → `SELECT ... FOR UPDATE` on `show_seats` rows → validate `AVAILABLE` + same screen → mark `BOOKED` → insert `bookings` — all in one transaction.
3. Unique constraints (`show_seats`, `bookings.idempotency_key`) make the DB the final arbiter. Retries with the same key return the original row.

Proven by `BookingConcurrencyTest`: 2 threads × same seat → exactly 1 win + 1 `409`.

## Seat holds and booking lifecycle

```
seat   (per show):  AVAILABLE --hold--> HELD --confirm--> BOOKED
                    HELD --expire/cancel--> AVAILABLE      BOOKED --cancel--> AVAILABLE
booking:            PENDING --confirm--> CONFIRMED --cancel--> CANCELLED
                    PENDING --5 min elapse--> EXPIRED      PENDING --cancel--> CANCELLED
```

- **Hold** – `show_seats` rows are locked (`SELECT … FOR UPDATE`, ascending seat id), checked, set to `HELD` with `hold_expires_at = now + 5 min` and linked to a `PENDING` booking, in one transaction. All-or-nothing for multi-seat requests.
- **Confirm** – `@Transactional`; locks the booking's seats, then the booking. Succeeds only if the booking is `PENDING`, `hold_expires_at` is still in the future and every seat is still `HELD` by it. An expired attempt releases the seats and returns `409`.
- **Auto release** – `SeatHoldExpiryJob` runs every 30 s and once at startup. Holds are stored in PostgreSQL, not memory, so nothing is lost on restart: the startup run frees everything that expired meanwhile. It claims rows with `FOR UPDATE SKIP LOCKED`, so it never blocks user requests and is safe with several instances. Correctness does **not** depend on the job: an expired hold is treated as free by hold, confirm, seat listings and history immediately.
- **Concurrency** – lock order is always seats → booking; `UNIQUE(show_id, seat_id)`, `@Version`, and the V2 `CHECK` constraints (a `HELD` seat must have a booking and an expiry, an `AVAILABLE` seat must have neither) are the backstop. Lock/optimistic failures return `409` (retry).
- **History** – every booking row is kept with its final status and `bookedAt`, `holdExpiresAt`, `confirmedAt`, `cancelledAt`.
- **Config** – `app.booking.hold-duration` (default `PT5M`), `app.booking.expiry-job.enabled`, `app.booking.expiry-job.interval-ms`.

Booking endpoints now return a `BookingResponse` (ids, movie, show date/time, seats, price, status and timestamps) instead of the raw JPA entity.

## Quick start

### 1. Backend

Requirements: JDK 21, Maven 3.9+, PostgreSQL 15+.

```bash
createdb BMS
# env (or edit application.properties defaults)
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/BMS
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=123
export APP_JWT_SECRET="<at-least-32-bytes-random-string>"
export CORS_ALLOWED_ORIGINS=http://localhost:5173

mvn spring-boot:run
# Swagger: http://localhost:8080/swagger-ui/index.html
# Health:  http://localhost:8080/actuator/health
```

Flyway migrates automatically (`V1__baseline.sql`, then `V2__seat_holds_and_booking_lifecycle.sql`, which also backfills `show_seats` for existing shows and marks seats of confirmed bookings `BOOKED`). `ddl-auto=validate` — schema changes go in new `db/migration/Vn__*.sql` files.

### 2. Frontend

```bash
cd frontend
npm install
npm run dev
# http://localhost:5173
```

The SPA still calls `/api/...` — both `/api` and `/api/v1` are served, so no frontend change required. Paginated responses are `Page` objects (`content`, `totalElements`, ...); update grids to read `.content` when you adopt `size` params.

### 3. Docker

```bash
docker build -t bms .
docker run -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/BMS \
  -e SPRING_DATASOURCE_USERNAME=postgres \
  -e SPRING_DATASOURCE_PASSWORD=123 \
  -e APP_JWT_SECRET="<64-char-secret>" bms
```

## Tests

```bash
mvn test                                 # all tests (H2, no Postgres needed)
mvn test -Dtest=SeatHoldLifecycleTest    # hold / confirm / expire / cancel / history / concurrent holds
mvn test -Dtest=BookingConcurrencyTest   # concurrent booking + idempotency
mvn package -DskipTests                  # build jar -> target/BMS-*.jar
```

## Configuration reference

| Key | Default | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/BMS` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME/PASSWORD` | `postgres/123` | DB creds |
| `DB_POOL_SIZE` | `20` | Hikari max pool |
| `APP_JWT_SECRET` | dev placeholder | **Must override in prod (≥32 bytes)** |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | comma-separated allowlist |

## Project structure

```text
src/main/java/com/cfs/BMS/
├── config/        # SecurityConfig, CorsConfig, OpenApiConfig
├── security/      # JwtUtil, JwtAuthFilter (stateless, Bearer)
├── controller/    # Booking, Movie, Show, Theater, Screen, Seat, City, User
├── service/       # BookingService (locked), UserService (BCrypt+JWT), ...
├── repository/    # + ShowSeatRepository (pessimistic locks)
├── entity/        # Booking(idempotencyKey, version), ShowSeat, User(role)
├── enums/         # BookingStatus, SeatHoldStatus, SeatType, UserRole
├── exception/     # ProblemDetail handler + typed exceptions
└── dto/           # validated requests, AuthResponse
src/main/resources/db/migration/V1__baseline.sql
src/main/resources/db/migration/V2__seat_holds_and_booking_lifecycle.sql
src/main/java/.../scheduler/SeatHoldExpiryJob.java
src/test/.../BookingConcurrencyTest.java, SeatHoldLifecycleTest.java
frontend/          # React 19 + Vite + TS
```

## Production notes / next steps

- Short-lived JWT (15 min) + refresh rotation, RBAC enforcement on admin routes.
- Kafka outbox → payment saga → notifications.
- Read replica for catalog, `pg_trgm`/FTS tuning for search, rate limiting (Bucket4j), tracing (Micrometer).
