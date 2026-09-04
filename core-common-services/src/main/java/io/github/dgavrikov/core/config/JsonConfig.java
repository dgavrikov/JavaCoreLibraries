package io.github.dgavrikov.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dgavrikov.core.encoding.OffsetDateTimeDeserializer;
import io.github.dgavrikov.core.encoding.OffsetDateTimeSerializer;
import io.github.dgavrikov.core.utils.JsonBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;

@Configuration
public class JsonConfig {
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper(){
        return new JsonBuilder()
                .addTimeDeserializer(OffsetDateTime.class, new OffsetDateTimeDeserializer())
                .addTimeSerializer(OffsetDateTime.class, new OffsetDateTimeSerializer())
                .build();
    }
}
