package com.github.dgavrikov.core.sm;

public interface ContextData <ID, S extends Enum<S>>{
    ID getId();
    S getState();
    String getTraceInfo();

    void applyChanges(ChangeSet<S> changeSet);

    interface ChangeSet<S> {
        S state();
    }
}
