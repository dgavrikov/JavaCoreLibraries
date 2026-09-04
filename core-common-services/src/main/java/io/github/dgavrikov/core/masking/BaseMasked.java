package io.github.dgavrikov.core.masking;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

public abstract class BaseMasked extends JsonSerializer<Object> {
    protected UnaryOperator<String> replaceFunction;

    private final static DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private final static DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private final static DateTimeFormatter OFFSET_DATETIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private final static DateTimeFormatter ZONED_DATE_TIME_FORMAT = DateTimeFormatter.ISO_ZONED_DATE_TIME;

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        maskingObject(value, gen, provider, replaceFunction);
    }

    protected void maskingObject(Object value, JsonGenerator gen, SerializerProvider provider, UnaryOperator<String> replace) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }

        switch (value) {
            case Map<?, ?> map -> {
                gen.writeStartObject();
                for (var entry : map.entrySet()) {
                    gen.writeFieldName(String.valueOf(entry.getKey()));
                    maskingObject(entry.getValue(), gen, provider, replace);
                }
                gen.writeEndObject();
            }
            case List<?> list -> {
                if (list.isEmpty()) {
                    gen.writeNull();
                } else {
                    gen.writeStartArray();
                    for (var item : list) {
                        if (item != null) {
                            gen.writeString(replace.apply(formatValue(item)));
                        } else {
                            gen.writeNull();
                        }
                    }
                    gen.writeEndArray();
                }
            }
            default -> {
                // For all single types (String, Date, LocalDate, Boolean, etc.)
                if (isComplexObject(value)) {
                    // Forward serialization to the standard Jackson mechanism
                    // to break down the DTO by fields, while continuing to mask nested properties
                    provider.defaultSerializeValue(value, gen);
                } else {
                    // Mask scalar types (String, Long, Boolean, Dates) as a string
                    gen.writeString(replace.apply(formatValue(value)));
                }
            }
        }
    }

    private static boolean isComplexObject(Object value) {
        if (value == null) {
            return false;
        }

        return switch (value) {
            case String ignored -> false;
            case Number ignored -> false;
            case Boolean ignored -> false;
            case Character ignored -> false;
            case LocalDate ignored -> false;
            case LocalDateTime ignored -> false;
            case OffsetDateTime ignored -> false;
            case ZonedDateTime ignored -> false;
            case Date ignored -> false;
            default -> true;
        };
    }

    /**
     * Converts an object of any supported type into a string for masking purposes.
     *
     * @param value the object value
     * @return the formatted string representation
     */
    private static String formatValue(Object value) {
        if (value == null) return "";

        return switch (value) {
            case String s -> s;
            case LocalDate ld -> ld.format(DATE_FORMAT);
            case LocalDateTime ldt -> ldt.format(DATE_TIME_FORMAT);
            case OffsetDateTime odt -> odt.format(OFFSET_DATETIME_FORMAT);
            case ZonedDateTime zdt -> zdt.format(ZONED_DATE_TIME_FORMAT);
            case Date d -> new SimpleDateFormat("yyyy-MM-dd").format(d);
            default -> String.valueOf(value);
        };
    }
}
