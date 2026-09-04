package io.github.dgavrikov.core.sm;

import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Getter
public final class SmRuntimeContext<ID, S extends SmState, T extends ContextData<ID, S>> {
    private final T data;
    private ExecutionSignal signal;
    private OffsetDateTime deferUntil;
    private String failReason;
    private Map<String, Object> metadata;

    public SmRuntimeContext(T data) {
        this.data = data;
    }

    public void putMetadata(String key, Object value) {
        if (this.metadata == null)
            this.metadata = new HashMap<>(5);
        this.metadata.put(key, value);
    }

    public void success() {
        this.signal = ExecutionSignal.SUCCESS;
    }

    public void skip() {
        this.signal = ExecutionSignal.SKIP;
    }

    public void send() {
        this.signal = ExecutionSignal.SEND;
    }

    public void retry() {
        this.signal = ExecutionSignal.RETRY;
    }

    public void stop() {
        this.signal = ExecutionSignal.STOP;
    }

    public void defer(OffsetDateTime until) {
        this.signal = ExecutionSignal.DEFER;
        this.deferUntil = until;
    }

    public void fail(String reason) {
        this.signal = ExecutionSignal.FAIL;
        this.failReason = reason;
    }

    public void resetSignal() {
        this.signal = null;
        this.deferUntil = null;
        this.failReason = null;
    }

    public static Object getMetadata(@Nullable Map<String, Object> metadata, String key) {
        if (metadata == null)
            return null;
        return metadata.get(key);
    }

    @SuppressWarnings("unchecked")
    public static <M> M getMetadata(@Nullable Map<String, Object> metadata, String key, Class<M> clazz) {
        if (metadata == null)
            return null;
        return (M) metadata.get(key);
    }
}
