package io.github.dgavrikov.core.logging.config;

import io.github.dgavrikov.core.logging.converter.LoggingHttpMessageConverter;
import io.github.dgavrikov.core.logging.service.PathExclusion;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final PathExclusion exclusionService;

    @Override
    public void extendMessageConverters(@NonNull List<HttpMessageConverter<?>> converters) {
        logMessageReplaceConverter(converters, exclusionService);
    }

    public static void logMessageReplaceConverter(List<HttpMessageConverter<?>> converters) {
        logMessageReplaceConverter(converters, null);
    }

    public static void logMessageReplaceConverter(List<HttpMessageConverter<?>> converters, PathExclusion exclusionService) {
        var iterator = converters.listIterator();
        while (iterator.hasNext()) {
            var converter = iterator.next();
            iterator.remove();
            iterator.add(new LoggingHttpMessageConverter((MappingJackson2HttpMessageConverter) converter, exclusionService));
        }
    }
}
