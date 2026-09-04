package io.github.dgavrikov.core.service.logging;

import com.fasterxml.jackson.databind.ObjectWriter;
import io.github.dgavrikov.core.masking.MaskingMarker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.spi.LoggingEventBuilder;
import org.zalando.logbook.BodyFilter;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class MaskingLog {
    private final ObjectWriter objectMapper;
    private final ObjectWriter maskingObjectMapper;
    private final BodyFilter bodyFilter;
    private final BodyFilter headerFilter;
    private final Boolean enabled;

    private static final String MESSAGE_FORMAT = "{}: {}:\n>>> Body: {}";
    private static final String DEFAULT_MESSAGE = "null";
    private static final String DEFAULT_BODY = "nullBody";

    private final Pattern patternTwoChar = Pattern.compile("(.{2})(.+)(.{2})",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private final Pattern patternAllJsonObject = Pattern.compile("\"([^\"]+)\":\\s*\"(.{1})(.*?)(.{1})(?=\")",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private final Pattern patternAllJsonArray = Pattern.compile("(?<=[\\[,])\\s*\"([^\"])[^\"]+([^\"])\"(?=\\s*[,\\]])",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private enum level {INFO, DEBUG, TRACE, WARN, ERROR}

    public String maskingTwoChar(String data) {
        if (StringUtils.isEmpty(data))
            return null;
        return patternTwoChar.matcher(data).replaceAll("$1****$3");
    }

    public void info(Logger logger, String message) {
        if (logger == null || !logger.isInfoEnabled())
            return;

        innerLog(logger.atInfo(), message);
    }

    public void info(Logger logger, Object object, String message) {
        if (logger == null || !logger.isInfoEnabled())
            return;

        innerLog(logger.atInfo(), object, message);

    }

    public void info(Logger logger, List<Marker> markers, Object object, String message) {
        if (logger == null || !logger.isInfoEnabled())
            return;

        innerLog(logger.atInfo(), markers, object, message);
    }

    public void debug(Logger logger, String message) {
        if (logger == null || !logger.isDebugEnabled())
            return;

        innerLog(logger.atDebug(), message);
    }

    public void debug(Logger logger, Object object, String message) {
        if (logger == null || !logger.isDebugEnabled())
            return;

        innerLog(logger.atDebug(), object, message);
    }

    public void debug(Logger logger, List<Marker> markers, Object object, String message) {
        if (logger == null || !logger.isDebugEnabled())
            return;

        innerLog(logger.atDebug(), markers, object, message);
    }

    public void trace(Logger logger, String message) {
        if (logger == null || !logger.isTraceEnabled())
            return;

        innerLog(logger.atTrace(), message);
    }

    public void trace(Logger logger, Object object, String message) {
        if (logger == null || !logger.isTraceEnabled())
            return;

        innerLog(logger.atTrace(), object, message);
    }

    public void trace(Logger logger, List<Marker> markers, Object object, String message) {
        if (logger == null || !logger.isTraceEnabled())
            return;

        innerLog(logger.atTrace(), markers, object, message);
    }

    public void warn(Logger logger, String message) {
        if (logger == null || !logger.isWarnEnabled())
            return;

        innerLog(logger.atWarn(), message);
    }

    public void warn(Logger logger, Object object, String message) {
        if (logger == null || !logger.isWarnEnabled())
            return;

        innerLog(logger.atWarn(), object, message);
    }

    public void warn(Logger logger, List<Marker> markers, Object object, String message) {
        if (logger == null || !logger.isWarnEnabled())
            return;

        innerLog(logger.atWarn(), markers, object, message);
    }

    public void warn(Logger logger, Throwable throwable, String message) {
        if (logger == null || !logger.isWarnEnabled())
            return;

        innerLog(logger.atWarn(), message, throwable);
    }

    public void error(Logger logger, String message) {
        if (logger == null || !logger.isErrorEnabled())
            return;

        innerLog(logger.atError(), message);
    }

    public void error(Logger logger, Object object, String message) {
        if (logger == null || !logger.isErrorEnabled())
            return;

        innerLog(logger.atError(), object, message);
    }

    public void error(Logger logger, List<Marker> markers, Object object, String message) {
        if (logger == null || !logger.isErrorEnabled())
            return;

        innerLog(logger.atError(), markers, object, message);
    }

    public void error(Logger logger, Throwable throwable, String message) {
        if (logger == null || !logger.isErrorEnabled())
            return;

        innerLog(logger.atError(), message, throwable);
    }

    public void error(Logger logger, Throwable throwable, Object object, String message) {
        if (logger == null || !logger.isErrorEnabled())
            return;

        logger.atError()
                .addMarker(MaskingMarker.MASKING_OBJECT_MARKER)
                .addMarker(MaskingMarker.MASKING_JSON_MARKER)
                .addMarker(MaskingMarker.MASKING_MARKER)
                .log(
                        MESSAGE_FORMAT,
                        getObjectClassName(object),
                        createErrorMessage(throwable, message),
                        Objects.requireNonNullElse(object, DEFAULT_BODY),
                        throwable
                );
    }

    public String maskingAllJsonObject(Object object) {
        String data = maskingObject(object);
        if (StringUtils.isEmpty(data)) return null;

        if (!enabled)
            return data;

        var json = patternAllJsonObject.matcher(data).replaceAll("\"$1\": \"$2***$4");
        return patternAllJsonArray.matcher(json).replaceAll("\"$1***$2\"");
    }

    public String maskingObject(Object object) {
        if (object instanceof String s) return s;
        try {
            return (object != null) ? maskingObjectMapper.writeValueAsString(object) : null;
        } catch (Throwable ex) {
            return String.valueOf(object);
        }
    }

    public String filterMasking(Object data) {
        return innerFilterMasking(
                data,
                (result) -> bodyFilter.filter("application/json", result),
                "filterMasking");
    }

    public String filterHeaderMasking(Object data) {
        return innerFilterMasking(
                data,
                (result) -> headerFilter.filter("application/json", result),
                "filterHeaderMasking");
    }

    private String innerFilterMasking(Object data, Function<String, String> func, String method) {
        var result = getObjectBody(data);
        if (result == null) return null;

        if (!enabled) return result;

        try {
            if (checkJson(result)) {
                var masked = func.apply(result);
                return (masked == null || masked.length() < 3) ? result : masked;
            }
        } catch (Exception e) {
            log.trace("Error in {}: {}", method, e.getLocalizedMessage());
        }
        return result;
    }

    private String getObjectBody(Object object) {
        if (object instanceof String s) return s;
        try {
            return (object != null) ? objectMapper.writeValueAsString(object) : null;
        } catch (Throwable e) {
            return String.valueOf(object);
        }
    }

    private boolean checkJson(String json) {
        if (StringUtils.isEmpty(json)
                || json.length() < 7
                || MESSAGE_FORMAT.equals(json)) {
            return false;
        }

        if (isJsonBounds(json)) {
            return true;
        }

        var replaced = io.github.dgavrikov.core.utils.StringUtils.replaceEscapeCharacter(json.trim());
        if (replaced == null) return false;

        return isJsonBounds(replaced.trim());
    }

    private static boolean isJsonBounds(String str) {
        int start = 0;
        int end = str.length() - 1;
        while (start < end && Character.isWhitespace(str.charAt(start))) start++;
        while (end > start && Character.isWhitespace(str.charAt(end))) end--;

        if (start >= end) return false;
        char first = str.charAt(start);
        char last = str.charAt(end);
        return (first == '{' && last == '}') || (first == '[' && last == ']');
    }

    private static void innerLog(@NotNull LoggingEventBuilder builder, String message) {
        builder.addMarker(MaskingMarker.MASKING_JSON_MARKER)
                .addMarker(MaskingMarker.MASKING_MARKER)
                .log(message);
    }

    private static void innerLog(@NotNull LoggingEventBuilder builder, String message, Throwable throwable) {
        builder.addMarker(MaskingMarker.MASKING_MARKER)
                .addMarker(MaskingMarker.MASKING_JSON_MARKER)
                .log(createErrorMessage(throwable, message), throwable);
    }

    private static void innerLog(@NotNull LoggingEventBuilder builder, Object object, String message) {
        builder.addMarker(MaskingMarker.MASKING_OBJECT_MARKER)
                .addMarker(MaskingMarker.MASKING_JSON_MARKER)
                .addMarker(MaskingMarker.MASKING_MARKER)
                .log(
                        MESSAGE_FORMAT,
                        getObjectClassName(object),
                        Objects.requireNonNullElse(message, DEFAULT_MESSAGE),
                        Objects.requireNonNullElse(object, DEFAULT_BODY)
                );
    }

    private static void innerLog(@NotNull LoggingEventBuilder builder, Iterable<Marker> markers, Object object, String message) {
        if (markers != null) {
            for (var m : markers) {
                builder = builder.addMarker(m);
            }
        }
        builder.log(
                MESSAGE_FORMAT,
                getObjectClassName(object),
                Objects.requireNonNullElse(message, DEFAULT_MESSAGE),
                Objects.requireNonNullElse(object, DEFAULT_BODY)
        );
    }

    private static String getObjectClassName(Object obj) {
        return obj == null ? "" : obj.getClass().getSimpleName();
    }

    private static String createErrorMessage(Throwable throwable, String message) {
        if (throwable == null) {
            return Objects.requireNonNullElse(message, "");
        }

        Throwable root = throwable;
        int depth = 0;

        // Спускаемся по цепочке причин, но не глубже 10 уровней (защита от зацикливания)
        while (root.getCause() != null && depth < 25) {
            root = root.getCause();
            depth++;
        }

        String localizedMessage = root.getLocalizedMessage();
        String cleanErr = (localizedMessage != null)
                ? localizedMessage.trim() :
                root.getClass().getSimpleName();

        if (message == null || message.isBlank())
            return cleanErr;

        return message + " " + cleanErr;
    }
}
