package io.github.dgavrikov.core.sm;

public interface ContextData <ID, S extends SmState>{
    ID getId();
    S getState();
    String getTraceInfo();
}
