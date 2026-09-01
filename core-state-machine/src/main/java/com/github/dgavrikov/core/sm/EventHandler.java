package com.github.dgavrikov.core.sm;

public interface EventHandler <C> {
    void handle(C context);
    String getName();
}
