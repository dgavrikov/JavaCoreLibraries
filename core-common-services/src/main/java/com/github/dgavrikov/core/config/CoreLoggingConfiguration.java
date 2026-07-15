package com.github.dgavrikov.core.config;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.github.dgavrikov.core.masking.MaskedPattern;
import com.github.dgavrikov.core.masking.MaskedType;
import com.github.dgavrikov.core.masking.MaskingAnnotationIntrospector;
import com.github.dgavrikov.core.properties.MdcLoggingProperties;
import com.github.dgavrikov.core.service.logging.MaskingLog;
import com.github.dgavrikov.core.service.logging.MdcLoggingWrappingAppender;
import com.github.dgavrikov.core.service.logging.filter.LogbookJsonPathBodyFilter;
import com.github.dgavrikov.core.service.logging.filter.LogbookJsonPathHeaderFilter;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.zalando.logbook.BodyFilter;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MdcLoggingProperties.class)
@ComponentScan(basePackages = "com.github.dgavrikov.core.masking.pattern")
public class CoreLoggingConfiguration {

    @Bean
    public Map<MaskedType, MaskedPattern> patternMap(Collection<MaskedPattern> maskedPatterns) {
        return maskedPatterns.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableMap(MaskedPattern::type, Function.identity()));
    }

    @Bean("logbookBodyFilter")
    public BodyFilter logbookBodyFilter(
            MdcLoggingProperties mdcLoggingProperties,
            Map<MaskedType, MaskedPattern> patternMap) {
        return new LogbookJsonPathBodyFilter(mdcLoggingProperties.getJsonPathBodyFilter(), patternMap);
    }

    @Bean("logbookHeaderFilter")
    public BodyFilter logbookHeaderFilter(
            MdcLoggingProperties mdcLoggingProperties) {
        return new LogbookJsonPathHeaderFilter(mdcLoggingProperties.getMaskedHeaders());
    }

    private ObjectWriter maskingObjectMapper(Jackson2ObjectMapperBuilder builder) {
        var factory = JsonFactory.builder().build();
        var mapper = builder.factory(factory).build();

        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        Class<?>[] classes = findAnnotationClassesWithReflections("com.github.dgavrikov.core.masking.annotation")
                .toArray(Class[]::new);
        mapper.setAnnotationIntrospector(new MaskingAnnotationIntrospector(classes));

        return mapper.writer();
    }

    private ObjectWriter logObjectMapper(Jackson2ObjectMapperBuilder builder) {
        var factory = JsonFactory.builder().build();
        var mapper = builder.factory(factory).build();

        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return mapper.writer();
    }

    @Bean
    public MaskingLog maskingLog(Jackson2ObjectMapperBuilder builder,
                                 @Qualifier("logbookBodyFilter") BodyFilter logbookBodyFilter,
                                 @Qualifier("logbookHeaderFilter") BodyFilter logbookHeaderFilter,
                                 MdcLoggingProperties mdcLoggingProperties,
                                 Map<MaskedType, MaskedPattern> patternMap) {
        var objectMapper = logObjectMapper(builder);
        var maskingObjectMapper = mdcLoggingProperties.getEnabled() ? maskingObjectMapper(builder) : objectMapper;
        var maskingLog = new MaskingLog(
                objectMapper,
                maskingObjectMapper,
                logbookBodyFilter,
                logbookHeaderFilter,
                mdcLoggingProperties.getEnabled());

        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext) {
            loggerContext.getLoggerList().forEach(logger -> {
                // Collect a copy of the list to prevent ConcurrentModificationException
                List<Appender<ILoggingEvent>> originalAppenders = new ArrayList<>();
                logger.iteratorForAppenders().forEachRemaining(originalAppenders::add);

                for (var originalAppender : originalAppenders) {
                    // Skip if the Appender is already part of our new chain
                    if (originalAppender instanceof AsyncAppender || originalAppender instanceof MdcLoggingWrappingAppender)
                        continue;

                    // 1. Detach the original appender
                    logger.detachAppender(originalAppender);

                    // 2. Create a custom masking wrapping appender (it will sit inside the async appender)
                    var wrappingAppender = new MdcLoggingWrappingAppender(
                            originalAppender,
                            maskingLog,
                            mdcLoggingProperties,
                            patternMap
                    );
                    wrappingAppender.setContext(loggerContext);
                    // Critical to provide a unique name for the internal appender
                    wrappingAppender.setName(originalAppender.getName());
                    wrappingAppender.start();

                    // 3. Create a standard Logback AsyncAppender
                    var asyncAppender = new AsyncAppender();
                    asyncAppender.setContext(loggerContext);
                    asyncAppender.setName(originalAppender.getName()); // Retain the original name for configuration mapping

                    // CRITICAL PARAMETERS FOR MDC, TRACING, AND HIGH-PERFORMANCE
                    asyncAppender.setQueueSize(mdcLoggingProperties.getLogQueueSize()); // In-memory queue size bound
                    asyncAppender.setDiscardingThreshold(0); // Do not discard DEBUG/TRACE logs under heavy load
                    asyncAppender.setNeverBlock(true); // Non-blocking strategy if the in-memory queue reaches full capacity
                    asyncAppender.setIncludeCallerData(true); // Disables expensive stack frame lookups to drastically reduce latency

                    // 4. Link the infrastructure pipeline: AsyncAppender -> MdcLoggingWrappingAppender -> OriginalAppender
                    asyncAppender.addAppender(wrappingAppender);
                    asyncAppender.start();

                    // 5. Register the finalized async-wrapped structural stack back to the logger context
                    logger.addAppender(asyncAppender);
                }
            });
        }
        return maskingLog;
    }

    public static Set<Class<? extends Annotation>> findAnnotationClassesWithReflections(String annotationPackage) {
        var reflections = new Reflections(new ConfigurationBuilder()
                .forPackages(annotationPackage)
                .addScanners(Scanners.SubTypes)
                .filterInputsBy(new FilterBuilder().includePackage(annotationPackage)));
        return reflections.getSubTypesOf(Annotation.class);
    }
}
