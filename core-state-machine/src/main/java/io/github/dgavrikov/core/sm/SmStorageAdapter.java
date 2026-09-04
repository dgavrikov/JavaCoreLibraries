package io.github.dgavrikov.core.sm;

import java.time.OffsetDateTime;

public interface SmStorageAdapter<ID, S extends SmState, T extends ContextData<ID, S>> {
    void changeState(T contextData, S state, String reason, boolean clearDeferTime);
    void changeDeferTime(T contextData, OffsetDateTime nextStart);
    void incrementRetryCount(T contextData);
}
