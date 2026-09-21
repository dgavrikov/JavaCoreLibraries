package io.github.dgavrikov.core.outbox.service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class VirtualThreadRateLimiter {
    private final static long DEFAULT_ANYTHING = 1_000_000_000L;
    private final long nanoDelayBetweenRequests;
    private final AtomicLong nextReleaseTime = new AtomicLong(0L);

    public VirtualThreadRateLimiter(int tps) {
        // Вычисляем задержку в наносекундах между сообщениями для достижения целевого TPS
        this.nanoDelayBetweenRequests = tps > 0 ? DEFAULT_ANYTHING / tps : 0L;
        this.nextReleaseTime.set(System.nanoTime());
    }

    public void acquire() {
        if (nanoDelayBetweenRequests == 0) return;

        // Поток атомарно за ОДИН проход резервирует свой уникальный временной слот
        long allowedTime = nextReleaseTime.getAndAdd(nanoDelayBetweenRequests);
        long now = System.nanoTime();

        // Защита от "накопленного оведрафта" после простоя (холодный старт)
        if (now - allowedTime > DEFAULT_ANYTHING) {
            if (nextReleaseTime.compareAndSet(allowedTime + nanoDelayBetweenRequests, now + nanoDelayBetweenRequests)) {
                allowedTime = now;
            }
        }

        long sleepTimeNanos = allowedTime - now;
        if (sleepTimeNanos > 0) {
            // Виртуальный поток засыпает в памяти, освобождая Carrier-поток ОС
            LockSupport.parkNanos(sleepTimeNanos);
        }
    }
}
