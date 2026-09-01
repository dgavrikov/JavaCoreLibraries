package com.github.dgavrikov.core.sm;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

public interface SmNotifyService<ID, S extends Enum<S>> {
    void notifyBefore(ContextData<ID, S> contextData, String workflowName, String handlerName);
    void notifyAfter(ContextData<ID, S> contextData, String workflowName, String handlerName, ExecutionSignal signal, @Nullable Map<String, Object> metadata);
}
