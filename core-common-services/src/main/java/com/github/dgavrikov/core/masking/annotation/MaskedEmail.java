package com.github.dgavrikov.core.masking.annotation;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.github.dgavrikov.core.masking.annotation.impl.MaskedEmailSerializer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@JsonSerialize(using = MaskedEmailSerializer.class)
public @interface MaskedEmail {
}
