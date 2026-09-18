package io.github.dgavrikov.core.outbox;

import io.github.dgavrikov.core.config.SchedulerConfigurationBuilder;
import io.github.dgavrikov.core.config.YamlPropertyLoaderFactory;
import io.github.dgavrikov.core.outbox.model.OutboxEvent;
import io.github.dgavrikov.core.outbox.model.OutboxPayloadPlugin;
import io.github.dgavrikov.core.outbox.properties.OutboxProperties;
import io.github.dgavrikov.core.outbox.repository.OutboxRepository;
import io.github.dgavrikov.core.outbox.repository.OutboxRepositoryDefault;
import io.github.dgavrikov.core.outbox.service.OutboxBatchPublisher;
import io.github.dgavrikov.core.outbox.service.OutboxMaintenanceWorker;
import io.github.dgavrikov.core.outbox.service.OutboxPlatformCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

//@Configuration(proxyBeanMethods = false) or @AutoConfiguration
@AutoConfiguration
@RequiredArgsConstructor
@ConditionalOnClass(NamedParameterJdbcTemplate.class)
@EnableConfigurationProperties({OutboxProperties.class})
@PropertySource(value = "classpath:default_outbox.yml", factory = YamlPropertyLoaderFactory.class)
public class OutboxAutoConfiguration {

    private final OutboxProperties outboxProperties;
    private final Environment env;

    @Bean
    @ConditionalOnMissingBean(name = "outboxMemoryQueue")
    public BlockingQueue<OutboxEvent> outboxMemoryQueue(){
        return new ArrayBlockingQueue<>(outboxProperties.inMemoryQueue().capacity());
    }

    // Изолированный планировщик для критического пути (отправка)
    @Bean
    public TaskScheduler outboxPublisherScheduler() {
        boolean isVirtual = env.getProperty("spring.threads.virtual.enabled", Boolean.class, false);
        return new SchedulerConfigurationBuilder("outbox-publisher-")
                .virtual(isVirtual)
                .poolSize(1)
                .build();
    }

    @Bean
    public TaskScheduler outboxScheduler() {
        boolean isVirtual = env.getProperty("spring.threads.virtual.enabled", Boolean.class, false);

        return new SchedulerConfigurationBuilder("outbox-maintenance-")
                .virtual(isVirtual)
                .poolSize(2)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(OutboxRepository.class)
    public OutboxRepository outboxRepository(
            NamedParameterJdbcTemplate namedParameterJdbcTemplate
    ){
        return new OutboxRepositoryDefault(namedParameterJdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(OutboxBatchPublisher.class)
    public OutboxBatchPublisher outboxBatchPublisher(
            TaskScheduler outboxPublisherScheduler,
            BlockingQueue<OutboxEvent> outboxMemoryQueue,
            Collection<OutboxPayloadPlugin> outboxPayloadPluginCollection,
            OutboxProperties outboxProperties,
            OutboxRepository outboxRepository
    ) {
        return new OutboxBatchPublisher(outboxPublisherScheduler, outboxMemoryQueue, outboxPayloadPluginCollection, outboxProperties, outboxRepository);
    }

    @Bean
    @ConditionalOnMissingBean(OutboxMaintenanceWorker.class)
    public OutboxMaintenanceWorker outboxMaintenanceWorker(
            BlockingQueue<OutboxEvent> outboxMemoryQueue,
            TaskScheduler outboxMaintenanceScheduler,
            OutboxProperties outboxProperties,
            OutboxRepository outboxRepository
    ) {
        return new OutboxMaintenanceWorker(outboxMemoryQueue, outboxMaintenanceScheduler, outboxProperties, outboxRepository);
    }

    @Bean
    @ConditionalOnMissingBean(OutboxPlatformCoordinator.class)
    public OutboxPlatformCoordinator outboxPlatformCoordinator(
            BlockingQueue<OutboxEvent> outboxMemoryQueue,
            Collection<OutboxPayloadPlugin> outboxPayloadPluginCollection,
            OutboxRepository outboxRepository
    ){
        return new OutboxPlatformCoordinator(outboxMemoryQueue, outboxPayloadPluginCollection, outboxRepository);
    }
}
