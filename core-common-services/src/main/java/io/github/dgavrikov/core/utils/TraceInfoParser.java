package io.github.dgavrikov.core.utils;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@UtilityClass
public class TraceInfoParser {
    private static final char SPLIT_CHARACTER = ':';
    private static final String W3C_PREFIX = "00-";
    private static final String W3C_POSTFIX = "-01";
    private static final String DASH = "-";

    /**
     * Конвертирует твой внутренний формат "traceId:spanId" в валидный W3C traceparent без String.split()
     * Пример: "4bf92f3577b34da6a3ce929d0e0e4736:00f067aa0ba902b7"
     * Результат: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
     */
    @Nullable
    public static String toW3CTraceParent(@Nullable String traceInfo) {
        if (traceInfo == null || traceInfo.isEmpty()) {
            return null;
        }

        int colonIndex = traceInfo.indexOf(SPLIT_CHARACTER);
        // Если двоеточия нет или оно в самом начале/конце — строка невалидна
        if (colonIndex <= 0 || colonIndex == traceInfo.length() - 1) {
            return null;
        }

        // Java 21 использует StringConcatFactory (Indify String Concatenation).
        // Этот код развернется в оптимальный байткод без создания лишних StringBuilder и массивов.
        return W3C_PREFIX + traceInfo.substring(0, colonIndex) + DASH + traceInfo.substring(colonIndex + 1) + W3C_POSTFIX;
    }

    /**
     * Быстрая склейка ID без String.format
     */
    @NotNull
    public static String buildTraceInfo(@NotNull String traceId, @NotNull String spanId) {
        return traceId + SPLIT_CHARACTER + spanId;
    }
}
