package io.github.dgavrikov.core.database.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "spring.datasource.properties")
public class DatabaseProperties {
    private List<String> repositoryPackages;
    private int transactionTimeout;
    private Short retryAttempts;
    private Duration retryBackoff = Duration.ofMillis(100);
}
