package com.github.dgavrikov.core.sm;

public interface State {
    String name();

    default String asString(){
        return name();
    }
}
