package io.github.dgavrikov.core.masking.annotation.impl;

import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import io.github.dgavrikov.core.masking.BaseMasked;
import io.github.dgavrikov.core.masking.annotation.MaskedRegex;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public class MaskedPatternSerializer
extends BaseMasked
implements ContextualSerializer {
    // Record из Java 16/21 идеален в качестве составного ключа для Кэша (автоматом генерит equals и hashCode)
    private record CacheKey(String pattern, String replacement) {}

    // Глобальный потокобезопасный кэш скомпилированных сериализаторов
    private static final Map<CacheKey, MaskedPatternSerializer> SERIALIZER_CACHE = new ConcurrentHashMap<>();

    // Конструктор для создания кэшируемых рабочих инстансов
    private MaskedPatternSerializer(Pattern pattern, String replacement) {
        // Убрали проверку null из рантайма сериализации — здесь данные всегда валидны
        this.replaceFunction = value -> pattern.matcher(value).replaceAll(replacement);
    }

    // Дефолтный конструктор для Jackson (заготовка)
    public MaskedPatternSerializer() {
        // Унарный оператор identity() просто возвращает саму строку без изменений и проверок
        this.replaceFunction = UnaryOperator.identity();
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) throws JsonMappingException {
        if (property == null) {
            return this;
        }

        MaskedRegex annotation = property.getAnnotation(MaskedRegex.class);
        if (annotation == null) {
            return this;
        }

        // Создаем легковесный ключ для проверки в кэше
        CacheKey key = new CacheKey(annotation.pattern(), annotation.replacement());

        // Метод computeIfAbsent гарантирует атомарность: компиляция Pattern и создание сериализатора
        // произойдут ровно ОДИН раз для каждой уникальной аннотации в проекте
        return SERIALIZER_CACHE.computeIfAbsent(key, k -> {
            Pattern compiledPattern = Pattern.compile(k.pattern(), Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            return new MaskedPatternSerializer(compiledPattern, k.replacement());
        });
    }
}
