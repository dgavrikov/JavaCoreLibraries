package com.github.dgavrikov.core.redis.service;

import com.github.dgavrikov.core.lock.LockManager;
import com.github.dgavrikov.core.lock.LockObject;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

public abstract class AbstractRedisLockManager<T extends Enum<T>> implements LockManager<T> {
    private final static String COUNTER_READS_SUCCESS_TOTAL = "redis_reads_success_total";
    private final static String COUNTER_READS_FAILURE_TOTAL = "redis_reads_failure_total";
    private final static String COUNTER_WRITES_SUCCESS_TOTAL = "redis_writes_success_total";
    private final static String COUNTER_WRITES_FAILURE_TOTAL = "redis_writes_failure_total";
    private final static String COUNTER_DELETE_SUCCESS_TOTAL = "redis_delete_success_total";
    private final static String COUNTER_DELETE_FAILURE_TOTAL = "redis_delete_failure_total";
    private final static long SLEEP_MS = 1000L;
    private final static long DEFAULT_TIME_TO_LIVE = 60L;

    private final MeterRegistry meterRegistry;
    private final RedisTemplate<String, String> redisTemplate;
    private final Map<T, Long> timeToLifeProperties;
    private final Logger log;

    public AbstractRedisLockManager(
            RedisTemplate<String, String> redisTemplate,
            Map<T, Long> timeToLifeProperties,
            MeterRegistry meterRegistry,
            Logger log) {
        this.meterRegistry = meterRegistry;
        this.redisTemplate = redisTemplate;
        this.timeToLifeProperties = timeToLifeProperties;
        this.log = log;
    }

    @PostConstruct
    private void init() {
        registerCounters();
    }

    private void registerCounters() {
        if (this.meterRegistry == null)
            return;
        Counter.builder(COUNTER_READS_SUCCESS_TOTAL)
                .tag("type", "get")
                .description("Redis total success read " + COUNTER_READS_SUCCESS_TOTAL)
                .register(meterRegistry);

        Counter.builder(COUNTER_READS_FAILURE_TOTAL)
                .tag("type", "get")
                .description("Redis total error read " + COUNTER_READS_FAILURE_TOTAL)
                .register(meterRegistry);

        Counter.builder(COUNTER_WRITES_SUCCESS_TOTAL)
                .tag("type", "put")
                .description("Redis total success write " + COUNTER_WRITES_SUCCESS_TOTAL)
                .register(meterRegistry);

        Counter.builder(COUNTER_WRITES_FAILURE_TOTAL)
                .tag("type", "put")
                .description("Redis total error write " + COUNTER_WRITES_FAILURE_TOTAL)
                .register(meterRegistry);

        Counter.builder(COUNTER_DELETE_SUCCESS_TOTAL)
                .tag("type", "delete")
                .description("Redis total success delete " + COUNTER_DELETE_SUCCESS_TOTAL)
                .register(meterRegistry);

        Counter.builder(COUNTER_DELETE_FAILURE_TOTAL)
                .tag("type", "delete")
                .description("Redis total error delete " + COUNTER_DELETE_FAILURE_TOTAL)
                .register(meterRegistry);
    }

    @Override
    public LockObject<T> keyLock(T lockType, String key) {
        var resultStatus = LockObject.Status.RELEASED;

        if(!StringUtils.isEmpty(key)) {
            try{
                boolean locked = false;
                while (!locked) {
                    locked = lock(lockType, key);
                    if(!locked) {
                        log.debug("Waiting for the lock to be released for key = {}", generateKey(lockType, key));
                        incrementMeterCounter(COUNTER_READS_SUCCESS_TOTAL);
                        Thread.sleep(SLEEP_MS);
                    }
                }
                resultStatus = LockObject.Status.RECEIVED;
                incrementMeterCounter(COUNTER_WRITES_SUCCESS_TOTAL);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
                incrementMeterCounter(COUNTER_WRITES_FAILURE_TOTAL);
                log.warn("enterLock exception:", e);
            }
        }
        return new LockObject<>(this, lockType, key, resultStatus);
    }

    @Override
    public LockObject<T> keyLockImmediately(T lockType, String key) {
        var resultStatus = LockObject.Status.RELEASED;
        if(!StringUtils.isEmpty(key)) {
            try{
                var locked = lock(lockType, key);
                if(locked) {
                    resultStatus = LockObject.Status.RECEIVED;
                    incrementMeterCounter(COUNTER_WRITES_SUCCESS_TOTAL);
                } else {
                    resultStatus = LockObject.Status.EXISTS;
                    incrementMeterCounter(COUNTER_READS_SUCCESS_TOTAL);
                }
            } catch (Exception e) {
                incrementMeterCounter(COUNTER_READS_FAILURE_TOTAL);
                log.warn("tryLock exception:", e);
            }
        }
        return new LockObject<>(this, lockType, key, resultStatus);
    }

    @Override
    public void releaseLock(LockObject<T> object) {
        if (object == null || StringUtils.isEmpty(object.getKey()))
            return;
        try {
            redisTemplate.delete(generateKey(object.getType(), object.getKey()));
            incrementMeterCounter(COUNTER_DELETE_SUCCESS_TOTAL);
        } catch (Exception e) {
            incrementMeterCounter(COUNTER_DELETE_FAILURE_TOTAL);
            log.warn("Release Lock exception: ", e);
        }
    }

    private void incrementMeterCounter(@NotNull String counterName){
        if(this.meterRegistry == null)
            return;
        meterRegistry.get(counterName)
                .counter()
                .increment();
    }

    private Boolean lock(T lockType, String key) {
        final var value = OffsetDateTime.now().toString();
        final var timeToLive = Duration.ofSeconds(
                Optional.ofNullable(timeToLifeProperties.get(lockType)).orElse(DEFAULT_TIME_TO_LIVE));
        final var realKey = generateKey(lockType, key);
        return redisTemplate.opsForValue().setIfAbsent(realKey, value, timeToLive);
    }

    protected abstract @NotNull String generateKey(@Nullable T lockType, @NotNull String key);
}
