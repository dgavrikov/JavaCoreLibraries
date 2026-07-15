package com.github.dgavrikov.core.uap.domain;

public enum CustomHeader {
    TYPE_TOKEN("type-token");
    public final String naming;

    CustomHeader(String naming) {
        this.naming = naming;
    }
}
