package com.github.dgavrikov.core.sm;

import java.util.Map;

public record StepDefinition<S extends Enum<S>> (
        S currentState,
        EventHandler<SmRuntimeContext> eventHandler,
        Map<ExecutionSignal, S> transmissions,
        boolean notifyBefore,
        boolean notifyAfter
){
    public S getTransmission(ExecutionSignal signal) {return transmissions.get(signal); }
}
