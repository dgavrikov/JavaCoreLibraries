package io.github.dgavrikov.core.service.logging.filter;

import io.github.dgavrikov.core.masking.MaskedPattern;
import io.github.dgavrikov.core.masking.MaskedType;
import io.github.dgavrikov.core.properties.MdcLoggingProperties;
import org.jetbrains.annotations.NotNull;
import org.zalando.logbook.BodyFilter;
import org.zalando.logbook.core.BodyFilters;
import org.zalando.logbook.json.JsonPathBodyFilters;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class LogbookJsonPathBodyFilter implements BodyFilter {
    private final BodyFilter delegate;

    public LogbookJsonPathBodyFilter(
            List<MdcLoggingProperties.JsonPathBodyFilter> jsonPathBodyFilters,
            Map<MaskedType, MaskedPattern> patternMap) {
        this.delegate = jsonPathBodyFilters.stream()
                .map(it -> {
                    var jsonPathString = it.getJsonPath();
                    var pattern = it.getReplacementPattern();
                    var value = it.getReplaceValue();
                    var maskedType = it.getMaskedType();
                    var jsonPathObj = JsonPathBodyFilters.jsonPath(jsonPathString);

                    if (maskedType != null) {
                        var maskedPattern = patternMap.get(maskedType);
                        if (maskedPattern == null) return null;
                        return jsonPathObj.replace(maskedPattern.masking());
                    } else if (pattern == null && value == null) {
                        return jsonPathObj.delete();
                    } else if (value != null && pattern == null) {
                        return jsonPathObj.replace(value);
                    } else if (value != null) {
                        return jsonPathObj.replace(pattern, value);
                    } else {
                        return null;
                    }
                }).filter(Objects::nonNull)
                .reduce((acc, bodyFilter) -> Objects.requireNonNullElse(acc.tryMerge(bodyFilter), acc))
                .orElse(BodyFilters.defaultValue());
    }

    @Override
    public String filter(String contentType, @NotNull String body) {
        return body.isBlank() ? body : delegate.filter(contentType, body);
    }
}
