package com.github.dgavrikov.core.redis.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.github.dgavrikov.core.redis.AutoConfigurationRedis;
import com.github.dgavrikov.core.service.logging.MaskingLog;
import io.lettuce.core.api.StatefulConnection;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.convert.RedisCustomConversions;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(RedisAutoConfiguration.class)
@EnableCaching
@RequiredArgsConstructor
@Import(AutoConfigurationRedis.class)
public class RedisConfig implements CachingConfigurer {
    private final MeterRegistry meterRegistry;
    private final MaskingLog maskingLog;

    private static final String NODES_SEPARATOR = ",";

    @Bean
    public RedisCustomConversions redisCustomConversions(OffsetDateTimeToBytesConverter offsetToBytes,
                                                         BytesToOffsetDateTimeConverter bytesToOffset) {
        return new RedisCustomConversions(Arrays.asList(offsetToBytes, bytesToOffset));
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring", name = "data.redis.sentinel.enabled", havingValue = "true")
    public LettuceConnectionFactory sentinelLettuceConnectionFactory(RedisProperties redisProperties) {
        var clusterConfiguration = new RedisSentinelConfiguration();
        for (var node : getNodes(redisProperties.getSentinel().getNodes())) {
            clusterConfiguration.addSentinel(crearteRedisNode(node));
        }
        clusterConfiguration.setMaster(redisProperties.getSentinel().getMaster());
        clusterConfiguration.setUsername(redisProperties.getUsername().trim());
        clusterConfiguration.setPassword(redisProperties.getPassword().trim());
        clusterConfiguration.setDatabase(redisProperties.getDatabase());
        clusterConfiguration.setSentinelUsername(redisProperties.getSentinel().getUsername().trim());
        clusterConfiguration.setSentinelPassword(redisProperties.getSentinel().getPassword().trim());

        var clientConfig = lettuceClientConfig(redisProperties);
        return new LettuceConnectionFactory(clusterConfiguration, clientConfig);
    }

    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    public LettuceConnectionFactory standaloneLettuceConnectionFactory(RedisProperties redisProperties) {
        var standaloneConnection = new RedisStandaloneConfiguration();
        standaloneConnection.setHostName(redisProperties.getHost().trim());
        standaloneConnection.setPort(redisProperties.getPort());
        standaloneConnection.setUsername(redisProperties.getUsername().trim());
        standaloneConnection.setPassword(redisProperties.getPassword().trim());
        standaloneConnection.setDatabase(redisProperties.getDatabase());

        var clientConfig = lettuceClientConfig(redisProperties);

        return new LettuceConnectionFactory(standaloneConnection, clientConfig);
    }

    @Bean
    public CacheManager cacheManager(LettuceConnectionFactory factory, ObjectMapper objectMapper) {
        objectMapper = objectMapper.copy();
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();
        objectMapper = objectMapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        var config = RedisCacheConfiguration.defaultCacheConfig();
        var redisCacheConfiguration = config
                .serializeKeysWith(RedisSerializationContext
                        .SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext
                        .SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(objectMapper)))
                .disableCachingNullValues();
        return RedisCacheManager.builder(factory)
                .cacheDefaults(redisCacheConfiguration)
                .build();
    }

    @Bean
    public RedisTemplate<?, ?> redisTemplate(LettuceConnectionFactory factory) {
        var template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        return template;
    }

    private static <T> LettucePoolingClientConfiguration lettuceClientConfig(RedisProperties redisProperties) {
        return LettucePoolingClientConfiguration.builder()
                .poolConfig(redisGenericObjectPoolConfig(redisProperties))
                .commandTimeout(redisProperties.getTimeout())
                .build();
    }

    private static GenericObjectPoolConfig<StatefulConnection<?, ?>> redisGenericObjectPoolConfig(RedisProperties redisProperties) {
        var poolConfig = new GenericObjectPoolConfig<StatefulConnection<?,?>>();
        poolConfig.setMinIdle(redisProperties.getLettuce().getPool().getMinIdle());
        poolConfig.setMaxIdle(redisProperties.getLettuce().getPool().getMaxIdle());
        poolConfig.setMaxTotal(redisProperties.getLettuce().getPool().getMaxActive());
        poolConfig.setMaxWait(redisProperties.getLettuce().getPool().getMaxWait());
        return poolConfig;
    }

    private static RedisNode crearteRedisNode(String node) {
        var preparedNodes = node.trim().split(":");
        return new RedisNode(preparedNodes[0], Integer.parseInt(preparedNodes[1]));
    }

    @Override
    public @Nullable CacheErrorHandler errorHandler() {
        return new RedisCacheErrorHandler(meterRegistry, maskingLog);
    }

    private static List<String> getNodes(List<String> nodes) {
        if(CollectionUtils.isEmpty(nodes)) return Collections.emptyList();
        return nodes.stream()
                .map(x -> Arrays.stream(x.split(NODES_SEPARATOR)).toList())
                .flatMap(List::stream)
                .toList();
    }
}
