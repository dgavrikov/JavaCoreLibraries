package com.github.dgavrikov.core.sm;

import java.time.OffsetDateTime;

public interface SmStorageAdapter<ID, S extends Enum<S>> {
    ContextData.ChangeSet<S> changeState(ID id, S state, String reason, boolean clearDeferTime);
    ContextData.ChangeSet<S> changeDeferTime(ID id, OffsetDateTime nextStart);
    ContextData.ChangeSet<S> incrementRetryCount(ID id);
}
