package io.github.dgavrikov.core.sm;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record SmWorkflowRegistry<ID, S extends SmState, T extends ContextData<ID, S>>(
        String workflowName,
        Map<S, StepDefinition<ID, S, T>> steps
) {
    public boolean supports (S state) { return steps.containsKey(state); }

    public StepDefinition<ID, S, T> getStep(S state) { return steps.get(state); }

    public Set<S> getSupportStates() {return steps.keySet(); }

    public String getSupportStatesString() {
        return steps.keySet().stream()
                .map(S::name)
                .collect(Collectors.joining(","));
    }
}
