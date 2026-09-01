package com.github.dgavrikov.core.sm;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record SmWorkflowRegistry<S extends Enum<S>>(
        String workflowName,
        Map<S, StepDefinition<S>> steps
) {
    public boolean supports (S state) { return steps.containsKey(state); }

    public StepDefinition<S> getStep(S state) { return steps.get(state); }

    public Set<S> getSupportStates() {return steps.keySet(); }

    public String getSupportStatesString() {
        return steps.keySet().stream()
                .map(S::name)
                .collect(Collectors.joining(","));
    }
}
