package com.github.dgavrikov.core.properties;

import com.github.dgavrikov.core.masking.MaskedType;
import com.github.dgavrikov.core.utils.Constants;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Getter
@Setter
@ConfigurationProperties(prefix = "logging.mdc-properties")
public class MdcLoggingProperties {
    private Boolean enabled = true;

    private static final Set<String> DEFAULT_EXCLUDE_URLS = Set.of(
            "/actuator/**",
            "/webjars/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/favicon**"
    );

    private Set<String> excludeUrls;

    private final List<String> defaultMaskedHeaders = List.of(
            "$.api.key",
            "$.jwt",
            "$.set-cookie",
            "$.authorization",
            "$.cookie",
            "$.ig_jsessionid",
            "$.iplanetdirectorypro",
            "$.masked-cache-key",
            "$.opt",
            "$.x-initiator-ip",
            "$.host"
    );

    private List<String> maskingHeaders = Collections.emptyList();

    public List<String> getExcludeUrls() {
        Set<String> result = new HashSet<>(DEFAULT_EXCLUDE_URLS);
        if (this.excludeUrls != null)
            result.addAll(this.excludeUrls);
        return result.stream().toList();
    }

    public List<String> getMaskedHeaders() {
        return Stream.concat(
                        defaultMaskedHeaders.stream(),
                        Optional.ofNullable(maskingHeaders).orElse(Collections.emptyList()).stream()
                ).filter(Objects::nonNull)
                .map(String::trim)
                .toList();
    }

    private List<String> skipBodyDebugLogPaths = Collections.emptyList();

    private DataSize messageMaxSize = DataSize.ofBytes(-1);

    private List<JsonPathBodyFilter> jsonPathBodyFilter = Collections.emptyList();

    private List<MaskingPatternEntity> masking = Collections.emptyList();

    private int logQueueSize = 1024;

    @Data
    public static class JsonPathBodyFilter {
        private String jsonPath;
        private Pattern replacementPattern;
        private String replaceValue;
        private MaskedType maskedType;
    }

    @Data
    @EqualsAndHashCode(exclude = {"replacement", "maskedType"})
    public static class MaskingPatternEntity {
        private Pattern pattern;
        private String replacement = Constants.DEFAULT_REPLACEMENT;
        private MaskedType maskedType;
    }
}
