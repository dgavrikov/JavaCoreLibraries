package io.github.dgavrikov.core.kafka.util;

import lombok.experimental.UtilityClass;
import org.apache.kafka.common.header.Headers;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@UtilityClass
public class KafkaHeaderUtils {
    public static Map<String, String> extractHeaders(@NotNull Headers header) {
        Map<String, String> metaData = new HashMap<>();
        header.forEach(h -> metaData.put(h.key(), new String(h.value(), StandardCharsets.UTF_8)));
        return metaData;
    }
}
