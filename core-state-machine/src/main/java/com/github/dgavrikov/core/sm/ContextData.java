package com.github.dgavrikov.core.sm;

public interface ContextData <ID, S extends State>{
    ID getId();
    S getState();
    String getTraceInfo();
}
