package io.github.dgavrikov.core.logging;

import io.github.dgavrikov.core.logging.config.WebClientLoggingFilterBeanPostProcessor;
import io.github.dgavrikov.core.logging.service.CoreLogbookSink;
import io.github.dgavrikov.core.properties.MdcLoggingProperties;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.util.AntPathMatcher;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.autoconfigure.LogbookAutoConfiguration;
import org.zalando.logbook.servlet.LogbookFilter;

import java.util.Collection;
import java.util.function.Predicate;

import static org.zalando.logbook.core.Conditions.exclude;

@AutoConfiguration
@EnableConfigurationProperties({MdcLoggingProperties.class})
@AutoConfigureBefore(LogbookAutoConfiguration.class)
public class LogbookLoggingAutoConfiguration {

    @Bean
    public CoreLogbookSink coreLogbookSink(MdcLoggingProperties loggingProperties) {
        return new CoreLogbookSink(loggingProperties);
    }

    @Bean
    public Logbook logbook(CoreLogbookSink coreLogbookSink,
                           MdcLoggingProperties loggingProperties) {
        return Logbook.builder()
                .condition(buildExcludeConditional(loggingProperties.getExcludeUrls()))
                .sink(coreLogbookSink)
                .build();
    }

    @Bean
    public FilterRegistrationBean<LogbookFilter> logbookFilter(Logbook logbook) {
        var filter = new LogbookFilter(logbook);
        var filterRegistration = new FilterRegistrationBean<>(filter);

        filterRegistration.setName("coreLogbookFilter");
        filterRegistration.addUrlPatterns("/*");
        filterRegistration.setOrder(0);
        return filterRegistration;
    }

    @Bean
    public static BeanPostProcessor webClientLoggingBeanPostProcessor() {
        return new WebClientLoggingFilterBeanPostProcessor();
    }

    private static Predicate<HttpRequest> buildExcludeConditional(Collection<String> excludePaths) {
        if(CollectionUtils.isEmpty(excludePaths))
            return  httpRequest -> true;

        var matcher = new AntPathMatcher();
        return exclude(httpRequest -> {
           var path = httpRequest.getPath();
           return excludePaths.stream()
                   .anyMatch(pattern -> matcher.match(pattern, path));
        });
    }
}
