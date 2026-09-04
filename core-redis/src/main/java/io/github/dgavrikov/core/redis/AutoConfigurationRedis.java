package io.github.dgavrikov.core.redis;

import io.github.dgavrikov.core.config.YamlPropertyLoaderFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration(proxyBeanMethods = false)
@PropertySource(value = "classpath:default_redis.yml", factory = YamlPropertyLoaderFactory.class)
public class AutoConfigurationRedis {
}
