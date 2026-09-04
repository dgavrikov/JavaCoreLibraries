package io.github.dgavrikov.core.service.logging.filter;

import io.github.dgavrikov.core.utils.Constants;
import org.jetbrains.annotations.NotNull;
import org.zalando.logbook.BodyFilter;
import org.zalando.logbook.core.BodyFilters;
import org.zalando.logbook.json.JsonPathBodyFilters;

import java.util.List;
import java.util.Objects;

public class LogbookJsonPathHeaderFilter implements BodyFilter {
    private final BodyFilter delegate;

    public LogbookJsonPathHeaderFilter(List<String> listHeaders) {
        var defaultPattern = Constants.DEFAULT_REPLACEMENT;
        this.delegate = listHeaders.stream()
                .map(it -> JsonPathBodyFilters.jsonPath(it).replace(defaultPattern))
                .filter(Objects::nonNull)
                .reduce((acc, bodyFilter) -> Objects.requireNonNullElse(acc.tryMerge(bodyFilter), acc))
                .orElse(BodyFilters.defaultValue());
    }

    @Override
    public String filter(String contentType, @NotNull String body) {
        return body.isBlank() ? body : delegate.filter(contentType, body);
    }
}
