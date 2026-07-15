package com.github.dgavrikov.core.service.logging.filter.web;

import com.github.dgavrikov.core.service.logging.converter.LoggingHttpMessageConverter;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(@NonNull List<HttpMessageConverter<?>> converters) {
        // Standard Jackson converter is required
        logMessageReplaceConverter(converters);
    }

    public static void logMessageReplaceConverter(List<HttpMessageConverter<?>> converters) {
        var iterator = converters.listIterator();
        while (iterator.hasNext()) {
            var converter = iterator.next();
            if (converter instanceof MappingJackson2HttpMessageConverter jacksonConverter) {
                // Remove the standard converter from the list
                iterator.remove();
                // Add our custom LoggingHttpMessageConverter that wraps the standard one
                iterator.add(new LoggingHttpMessageConverter(jacksonConverter));
                break;
            }
        }
    }
}
