package com.github.dgavrikov.core.redis.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@Component
@ReadingConverter
public class BytesToOffsetDateTimeConverter implements Converter<byte[], OffsetDateTime> {
    @Override
    public @Nullable OffsetDateTime convert(byte @NonNull [] source) {
        return OffsetDateTime.parse(new String(source), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
