package io.github.dgavrikov.core.encoding;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

public class NullCharSanitizingStringDeserializer extends StdDeserializer<String> {

    public NullCharSanitizingStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        var value = p.getValueAsString();
        if (value == null || value.indexOf('\0') < 0)
            return value;
        return value.replace("\0", "");
    }
}
