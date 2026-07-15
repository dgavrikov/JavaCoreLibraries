package com.github.dgavrikov.core.masking.annotation.impl;

import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.github.dgavrikov.core.masking.BaseMasked;
import com.github.dgavrikov.core.masking.MaskedDateType;
import com.github.dgavrikov.core.masking.annotation.MaskedDate;
import com.github.dgavrikov.core.masking.pattern.MaskedPatternDateMM_YYYY;
import com.github.dgavrikov.core.masking.pattern.MaskedPatternDateYYYY_MM;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.UnaryOperator;

public class MaskedDateSerializer
        extends BaseMasked
        implements ContextualSerializer {
    // ОПТИМИЗАЦИЯ: Быстрая EnumMap для хранения синглтонов. Работает на битовых масках/массивах рантайма.
    private static final Map<MaskedDateType, MaskedDateSerializer> SERIALIZERS = new EnumMap<>(MaskedDateType.class);

    static {
        SERIALIZERS.put(MaskedDateType.DATE_MM_YYYY, new MaskedDateSerializer(MaskedPatternDateMM_YYYY.masking));
        SERIALIZERS.put(MaskedDateType.DATE_YYYY_MM, new MaskedDateSerializer(MaskedPatternDateYYYY_MM.masking));
    }

    // Приватный конструктор для наполнения карты
    private MaskedDateSerializer(UnaryOperator<String> replaceFunction) {
        this.replaceFunction = replaceFunction;
    }

    // Дефолтный конструктор для Jackson
    public MaskedDateSerializer() {
        this.replaceFunction = MaskedPatternDateYYYY_MM.masking;
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) throws JsonMappingException {
        if (property == null) {
            return this;
        }

        var annotation = property.getAnnotation(MaskedDate.class);
        if (annotation == null) {
            return this;
        }

        // Извлекаем синглтон из карты за O(1) времени без выделения памяти (Zero-Allocation)
        MaskedDateSerializer cachedSerializer = SERIALIZERS.get(annotation.value());

        // Защита: если вдруг в Enum что-то добавили, но забыли зарегистрировать в статик-блоке,
        // возвращаем дефолтный текущий инстанс, чтобы не упасть по NullPointerException
        return cachedSerializer != null ? cachedSerializer : this;
    }
}
