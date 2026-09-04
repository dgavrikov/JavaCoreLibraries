package io.github.dgavrikov.core.masking.annotation;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.dgavrikov.core.masking.MaskedDateType;
import io.github.dgavrikov.core.masking.annotation.impl.MaskedDateSerializer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@JsonSerialize(using = MaskedDateSerializer.class)
public @interface MaskedDate {
    MaskedDateType value() default MaskedDateType.DATE_YYYY_MM;
}
