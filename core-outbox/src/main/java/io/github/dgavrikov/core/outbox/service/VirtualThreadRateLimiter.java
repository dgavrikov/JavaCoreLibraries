package io.github.dgavrikov.core.outbox.service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A high-performance, non-blocking, lock-free rate limiter optimized specifically for Java Virtual Threads.
 * Instead of spinning in a heavy CAS loop or utilizing heavy synchronized constructs that pin Carrier Threads,
 * this implementation uses an atomic time-line shift pattern via {@link AtomicLong#getAndAdd(long)}.
 */
public final class VirtualThreadRateLimiter {

    /**
     * Number of nanoseconds in one single second. Used as the base unit for TPS calculations.
     */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long nanoDelayBetweenRequests;
    private final AtomicLong nextReleaseTime = new AtomicLong(0L);

    /**
     * Constructs a new rate limiter with the specified Transactions Per Second (TPS) limit.
     *
     * @param tps the target maximum number of requests allowed per second; 0 or negative disables rate limiting.
     */
    public VirtualThreadRateLimiter(int tps) {
        // Calculates the mandatory interval in nanoseconds between messages to achieve the target TPS
        this.nanoDelayBetweenRequests = tps > 0 ? NANOS_PER_SECOND / tps : 0L;
        this.nextReleaseTime.set(System.nanoTime());
    }

    /**
     * Acquires a permit to proceed, blocking the execution via non-allocating parking if necessary.
     * This method ensures the thread guarantees its execution slot in a single, safe atomic pass.
     */
    public void acquire() {
        if (nanoDelayBetweenRequests == 0) return;

        // The thread atomically reserves its unique execution timestamp slot in a single pass.
        // This completely eliminates loop-based CPU burn (spin-lock effect) under heavy thread contention.
        long allowedTime = nextReleaseTime.getAndAdd(nanoDelayBetweenRequests);
        long now = System.nanoTime();

        // Prevents "accumulated overdraft" after periods of inactivity (cool-down / cold start state).
        // If the system was idle and the scheduled time falls behind the actual time by more than 1 second,
        // we reset the timeline to the current timestamp to avoid subsequent immediate bursting.
        if (now - allowedTime > NANOS_PER_SECOND) {
            // Only one thread winning this race advances the global timeline, avoiding state distortion.
            if (nextReleaseTime.compareAndSet(allowedTime + nanoDelayBetweenRequests, now + nanoDelayBetweenRequests)) {
                allowedTime = now;
            }
        }

        long sleepTimeNanos = allowedTime - now;
        if (sleepTimeNanos > 0) {
            // Parking the Virtual Thread in memory, detached from the underlying OS Carrier Thread.
            // This represents a native-compliant, zero-allocations way to wait without burning CPU cores.
            LockSupport.parkNanos(sleepTimeNanos);
        }
    }
}
