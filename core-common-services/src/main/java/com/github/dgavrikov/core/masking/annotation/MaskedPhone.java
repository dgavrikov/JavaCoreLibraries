package com.github.dgavrikov.core.masking.annotation;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.github.dgavrikov.core.masking.annotation.impl.MaskedPhoneSerializer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@JsonSerialize(using = MaskedPhoneSerializer.class)
public @interface MaskedPhone {
}
