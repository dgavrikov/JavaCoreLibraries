# Redis and Redis Sentinel Interaction Library

Provides pre-configured integration with standalone Redis instances and high-availability Redis Sentinel clusters for data caching and state management.

## Installation

### Add Dependency to pom.xml

```xml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-redis</artifactId>
</dependency>
```

## Configuration

### Enable Redis Auto-configuration

Activate the built-in Redis infrastructure components by including `AutoConfigurationRedis` within your configuration class component scan:

```java
import redis.io.github.dgavrikov.core.AutoConfigurationRedis;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackageClasses = {AutoConfigurationRedis.class})
public class ImportConfig {
}
```
