package io.github.dgavrikov.core.sm;

public interface SmState {
    String name();

    default String asString(){
        return name();
    }
}
