package io.github.dgavrikov.core.encoding;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

public class NullCharSanitizingStringSerializer extends StdSerializer<String> {

    public NullCharSanitizingStringSerializer() {
        super(String.class);
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeString(sanitize(value));
    }

    private static String sanitize(String value) {
        if (value == null || value.indexOf('\0') < 0)
            return value;
        return value.replace("\0", "");
    }

    public static class KeySerializer extends StdSerializer<String> {
        public KeySerializer() {
            super(String.class);
        }

        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeFieldName(sanitize(value));
        }
    }
}
