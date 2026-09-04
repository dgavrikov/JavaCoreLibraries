package io.github.dgavrikov.core.sm;

import java.util.Map;

public record StepDefinition<ID, S extends SmState, T extends ContextData<ID, S>> (
        S currentState,
        EventHandler<SmRuntimeContext<ID, S, T>> eventHandler,
        Map<ExecutionSignal, S> transmissions,
        boolean notifyBefore,
        boolean notifyAfter
){
    public S getTransmission(ExecutionSignal signal) {return transmissions.get(signal); }
}
