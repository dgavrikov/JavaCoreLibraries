package io.github.dgavrikov.core.outbox.service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class VirtualThreadRateLimiter {
    private final long nanoDelayBetweenRequests;
    private final AtomicLong nextReleaseTime = new AtomicLong(0L);

    public VirtualThreadRateLimiter(int tps) {
        // Вычисляем задержку в наносекундах между сообщениями для достижения целевого TPS
        this.nanoDelayBetweenRequests = tps > 0 ? 1_000_000_000L / tps : 0L;
        this.nextReleaseTime.set(System.nanoTime());
    }

    public void acquire() {
        if (nanoDelayBetweenRequests == 0) return;

        long now;
        long allowedTime;
        do {
            now = System.nanoTime();
            long currentReleaseTime = nextReleaseTime.get();
            // Если мы отстали от графика, стартуем от текущего момента
            long baseTime = Math.max(now, currentReleaseTime);
            allowedTime = baseTime + nanoDelayBetweenRequests;

            // Lock-free обновление времени следующего слота
            if (nextReleaseTime.compareAndSet(currentReleaseTime, allowedTime)) {
                break;
            }
        } while (true);

        long sleepTimeNanos = allowedTime - nanoDelayBetweenRequests - now;

        if (sleepTimeNanos > 0) {
            // Для виртуальных потоков LockSupport.parkNanos — это идеальный, native-compliant способ
            // припарковаться в памяти без блокировки Carrier-потока ОС.
            LockSupport.parkNanos(sleepTimeNanos);
        }
    }
}
