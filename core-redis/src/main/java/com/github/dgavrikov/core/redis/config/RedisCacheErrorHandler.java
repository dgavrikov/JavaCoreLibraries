package com.github.dgavrikov.core.redis.config;

import com.github.dgavrikov.core.service.logging.MaskingLog;
import io.lettuce.core.RedisCommandTimeoutException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
@Component
public class RedisCacheErrorHandler implements CacheErrorHandler {
    private final Map<String, Counter> metricCounters = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private final MaskingLog maskingLog;

    @Override
    public void handleCacheGetError(@NonNull RuntimeException exception, @NonNull Cache cache, @NonNull Object key) {
        internalHandleCacheError(exception, cache, HandleType.GET);
    }

    @Override
    public void handleCachePutError(@NonNull RuntimeException exception, @NonNull Cache cache, @NonNull Object key, @Nullable Object value) {
        internalHandleCacheError(exception, cache, HandleType.PUT);
    }

    @Override
    public void handleCacheEvictError(@NonNull RuntimeException exception, @NonNull Cache cache, @NonNull Object key) {
        internalHandleCacheError(exception, cache, HandleType.EVICT);
    }

    @Override
    public void handleCacheClearError(@NonNull RuntimeException exception, @NonNull Cache cache) {
        internalHandleCacheError(exception, cache, HandleType.CLEAN);
    }

    private void internalHandleCacheError(
            @NotNull RuntimeException exception,
            @NotNull Cache cache,
            HandleType handleType) {
        maskingLog.error(log, exception, handleType.message
                + cache.getName() + " : " + exception.getLocalizedMessage());

        cacheMetric(cache.getName(), handleType.type);

        if (exception instanceof RedisCommandTimeoutException)
            throw new RedisCommandTimeoutException(exception.getLocalizedMessage());
    }

    private void cacheMetric(String name, String type) {
        try {
            var counter = metricCounters.get(name + type);
            if (counter == null) {
                metricCounters.put(name + type, Counter.builder(name)
                        .tag("name", name)
                        .tag("type", type)
                        .description("Redis number error " + name)
                        .register(meterRegistry));
                counter = metricCounters.get(name + type);
            }
            if (counter == null)
                maskingLog.warn(log, "Metric for " + name + " not found.");
            else
                counter.increment();
        } catch (Exception e) {
            maskingLog.error(log, e, "Counter error: " + name);
        }
    }

    @Getter
    private enum HandleType {
        GET("get", "Unable to get from cache "),
        PUT("put", "Unable to put into cache "),
        EVICT("evict", "Unable to evict from cache "),
        CLEAN("clean", "Unable to clean cache ");

        private final String type;
        private final String message;

        HandleType(String type, String message) {
            this.type = type;
            this.message = message;
        }
    }
}
