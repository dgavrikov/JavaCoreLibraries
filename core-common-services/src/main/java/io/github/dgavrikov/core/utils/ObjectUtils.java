package io.github.dgavrikov.core.utils;

import io.github.dgavrikov.core.properties.MdcLoggingProperties;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ObjectUtils {
    public static String replaceCRLFWithUnderscore(String value) {
        return value != null
                ? value.replace('\n', '_').replace('\r', '_')
                : null;
    }

    public static boolean isLogBody(MdcLoggingProperties mdcLoggingProperties, String path) {
        return mdcLoggingProperties.getSkipBodyDebugLogPaths().stream().noneMatch(path::contains);
    }

    public static Long parseLong(String s) {
        return replaceCRLFWithUnderscore(s) != null ? Long.parseLong(s) : null;
    }

    public static Short parseIntegerToShort(Integer i) {
        return i != null ? i.shortValue() : null;
    }
}
