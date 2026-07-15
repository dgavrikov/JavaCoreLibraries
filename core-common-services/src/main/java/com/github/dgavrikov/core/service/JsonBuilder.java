package com.github.dgavrikov.core.service;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class JsonBuilder {
    private final JavaTimeModule javaTimeModule = new JavaTimeModule();

    public <T> JsonBuilder addTimeDeserializer(Class<T> clazz, JsonDeserializer<T> deserializer) {
        javaTimeModule.addDeserializer(clazz, deserializer);
        return this;
    }

    public <T> JsonBuilder addTimeSerializer(Class<T> clazz, JsonSerializer<T> serializer){
        javaTimeModule.addSerializer(clazz, serializer);
        return this;
    }

    public ObjectMapper build(){
        return JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(MapperFeature.DEFAULT_VIEW_INCLUSION, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.USE_DEFAULTS))
                .addModule(javaTimeModule)
                .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .build();
    }
}
