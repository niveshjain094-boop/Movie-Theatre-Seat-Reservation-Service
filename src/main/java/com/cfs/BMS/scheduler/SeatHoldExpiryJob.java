package com.cfs.BMS.scheduler;

import com.cfs.BMS.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically returns expired, unconfirmed seat holds to AVAILABLE.
 * Holds live in PostgreSQL (hold_expires_at), not in memory, so a restart loses nothing:
 * the run on ApplicationReadyEvent releases everything that expired while the app was down.
 * Safe with several app instances: rows are claimed with SELECT ... FOR UPDATE SKIP LOCKED.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.booking.expiry-job.enabled", havingValue = "true", matchIfMissing = true)
public class SeatHoldExpiryJob {

    private static final int MAX_BATCHES_PER_RUN = 100;

    private final BookingService bookingService;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        run();
    }

    @Scheduled(fixedDelayString = "${app.booking.expiry-job.interval-ms:30000}",
               initialDelayString = "${app.booking.expiry-job.interval-ms:30000}")
    public void run() {
        try {
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                if (bookingService.releaseExpiredHolds() < BookingService.EXPIRY_BATCH_SIZE) return;
            }
        } catch (Exception e) {
            // Never let one failed run kill the schedule; the next tick retries.
            log.error("Seat hold expiry run failed", e);
        }
    }
}
