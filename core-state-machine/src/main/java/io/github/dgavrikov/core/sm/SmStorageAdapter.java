package io.github.dgavrikov.core.sm;

import java.time.OffsetDateTime;

public interface SmStorageAdapter<ID, S extends SmState, T extends ContextData<ID, S>> {
    void changeState(T contextData, S state, String reason);
    void changeDeferTime(T contextData, S state, OffsetDateTime nextStart);
    void incrementRetryCount(T contextData);
}
