package com.github.dgavrikov.core.sm;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

public interface SmNotifyService<ID, S extends Enum<S>, T extends ContextData<ID, S>> {
    void notifyBefore(T contextData, String workflowName, String handlerName);
    void notifyAfter(T contextData, String workflowName, String handlerName, ExecutionSignal signal, @Nullable Map<String, Object> metadata);
}
