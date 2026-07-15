package com.github.dgavrikov.core.service.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import com.github.dgavrikov.core.masking.MaskedPattern;
import com.github.dgavrikov.core.masking.MaskedType;
import com.github.dgavrikov.core.masking.MaskingMarker;
import com.github.dgavrikov.core.properties.MdcLoggingProperties;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

@Slf4j
public class MdcLoggingWrappingAppender extends AppenderBase<ILoggingEvent> {
    private final Appender<ILoggingEvent> delegate;
    private final List<MdcLoggingProperties.MaskingPatternEntity> masking;
    private final Map<MaskedType, MaskedPattern> patternMap;
    private final long maxSizeBytes;
    private final MaskingLog maskingLog;

    private static final Set<Marker> MASKING_MARKERS = Set.of(
            MaskingMarker.MASKING_OBJECT_MARKER,
            MaskingMarker.MASKING_HEADER,
            MaskingMarker.MASKING_JSON_MARKER,
            MaskingMarker.MASKING_JSON_ALL_MARKER
    );

    public MdcLoggingWrappingAppender(Appender<ILoggingEvent> delegate,
                                      MaskingLog maskingLog,
                                      MdcLoggingProperties props,
                                      Map<MaskedType, MaskedPattern> patternMap) {
        this.delegate = delegate;
        this.masking = props.getMasking().stream()
                .distinct()
                .toList();
        this.patternMap = patternMap;
        this.maxSizeBytes = props.getMessageMaxSize().isNegative() ? -1 : props.getMessageMaxSize().toBytes();
        this.maskingLog = maskingLog;
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (eventObject == null)
            return;
        if (hasAnyMaskingMarker(eventObject) || isTooLong(eventObject))
            delegate.doAppend(new ProcessedLoggingEvent(eventObject, masking, patternMap, maxSizeBytes, maskingLog));
        else
            delegate.doAppend(eventObject);
    }

    @Override
    public void start() {
        super.start();
        if (!delegate.isStarted())
            delegate.start();
    }

    @Override
    public void stop() {
        super.stop();
        if (delegate.isStarted())
            delegate.stop();
    }

    private boolean hasAnyMaskingMarker(ILoggingEvent event) {
        var markers = event.getMarkerList();
        if (CollectionUtils.isEmpty(markers)) return false;

        for (var m : MASKING_MARKERS) {
            if (markers.contains(m)) return true;
        }

        for (Marker x : markers) {
            if (x != null && x.contains(MaskingMarker.MASKING_MARKER)) return true;
        }
        return false;
    }

    private boolean isTooLong(ILoggingEvent event) {
        return maxSizeBytes > 0
                && event.getFormattedMessage() != null
                && event.getFormattedMessage().length() > maxSizeBytes;
    }

    private static class ProcessedLoggingEvent implements ILoggingEvent {
        @Delegate
        private final ILoggingEvent delegate;

        private final List<MdcLoggingProperties.MaskingPatternEntity> masking;
        private final Map<MaskedType, MaskedPattern> patternMap;
        private final long maxSizeBytes;
        private final MaskingLog maskingLog;

        private volatile String cachedFormattedMessage;

        public ProcessedLoggingEvent(
                ILoggingEvent delegate,
                List<MdcLoggingProperties.MaskingPatternEntity> masking,
                Map<MaskedType, MaskedPattern> patternMap,
                long maxSizeBytes,
                MaskingLog maskingLog
        ) {
            this.delegate = delegate;
            this.masking = masking;
            this.patternMap = patternMap;
            this.maxSizeBytes = maxSizeBytes;
            this.maskingLog = maskingLog;
        }

        private UnaryOperator<Object> getMaskingFunction(Marker marker) {
            if (marker.equals(MaskingMarker.MASKING_OBJECT_MARKER)) return maskingLog::maskingObject;
            if (marker.equals(MaskingMarker.MASKING_HEADER)) return maskingLog::filterHeaderMasking;
            if (marker.equals(MaskingMarker.MASKING_JSON_MARKER)) return maskingLog::filterMasking;
            if (marker.equals(MaskingMarker.MASKING_JSON_ALL_MARKER)) return maskingLog::maskingAllJsonObject;
            return UnaryOperator.identity();
        }

        private String trim(String s) {
            if (maxSizeBytes > 0 && s != null && s.length() > maxSizeBytes)
                return s.substring(0, (int) maxSizeBytes) + "...";
            return s;
        }

        private String replace(String acc, MdcLoggingProperties.MaskingPatternEntity entity) {
            if (acc == null) return null;
            try {
                var maskedType = entity.getMaskedType();
                if (maskedType != null) {
                    var maskedPattern = patternMap.get(maskedType);
                    if (maskedPattern != null)
                        return entity.getPattern()
                                .matcher(acc)
                                .replaceAll(matchResult -> maskedPattern.masking().apply(matchResult.group()));
                }

                // Fallback c null-safe replacement
                String replacement = entity.getReplacement();
                return entity.getPattern()
                        .matcher(acc)
                        .replaceAll(Objects.requireNonNullElse(replacement, StringUtils.EMPTY));
            } catch (Exception e) {
                log.debug("Failed to mask pattern {}: {}", entity.getPattern(), e.getLocalizedMessage());
                return acc;
            }
        }

        private String process() {
            var maskedTemplate = getMessage();
            var maskedArgs = getArgumentArray();

            String current;
            if (maskedArgs != null && maskedArgs.length > 0) {
                current = MessageFormatter.arrayFormat(maskedTemplate, maskedArgs).getMessage();
            } else {
                current = maskedTemplate;
            }

            // Masking
            if (!masking.isEmpty()
                    && !CollectionUtils.isEmpty(delegate.getMarkerList())
                    && delegate.getMarkerList().contains(MaskingMarker.MASKING_MARKER)) {
                for (var entity : masking) {
                    current = replace(current, entity);
                }
            }

            current = trim(current);

            return current != null ? current : "";
        }

        private String processArguments(String message) {
            if (StringUtils.isBlank(message)) return message;

            List<Marker> markers = delegate.getMarkerList();
            if (CollectionUtils.isEmpty(markers)
                    || (markers.size() == 1 && markers.getFirst().equals(MaskingMarker.MASKING_MARKER)))
                return message;

            Object current = message;
            for (var m : MASKING_MARKERS) {
                if (markers.contains(m))
                    current = getMaskingFunction(m).apply(current);
            }

            return String.valueOf(current);
        }

        private Object[] processArguments(Object[] args) {
            if (args == null || args.length == 0) return args;

            List<Marker> markers = delegate.getMarkerList();
            if (CollectionUtils.isEmpty(markers)
                    || (markers.size() == 1 && markers.getFirst().equals(MaskingMarker.MASKING_MARKER))) {
                return args;
            }

            var result = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                Object current = args[i];
                if (current == null) {
                    result[i] = null;
                    continue;
                }

                for (var m : MASKING_MARKERS) {
                    if (markers.contains(m))
                        current = getMaskingFunction(m).apply(current);
                }
                result[i] = current;
            }
            return result;
        }


        @Override
        public String getFormattedMessage() {
            // If the message has already been masked, instantly return the cached version
            if (cachedFormattedMessage == null) {
                synchronized (this) { // Thread-safety guard against concurrent masking attempts
                    if (cachedFormattedMessage == null) {
                        cachedFormattedMessage = process(); // Heavy log processing logic (invoked strictly once!)
                    }
                }
            }
            return cachedFormattedMessage;
        }

        @Override
        public Object[] getArgumentArray() {
            return processArguments(delegate.getArgumentArray());
        }

        @Override
        public String getMessage() {
            return processArguments(delegate.getMessage());
        }
    }
}
