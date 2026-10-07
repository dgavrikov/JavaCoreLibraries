package io.github.dgavrikov.core.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dgavrikov.core.config.SchedulerConfigurationBuilder;
import io.github.dgavrikov.core.config.YamlPropertyLoaderFactory;
import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxPayloadPlugin;
import io.github.dgavrikov.core.inbox.properties.InboxProperties;
import io.github.dgavrikov.core.inbox.repository.InboxRepository;
import io.github.dgavrikov.core.inbox.repository.InboxRepositoryDefault;
import io.github.dgavrikov.core.inbox.service.InboxBatchProcessor;
import io.github.dgavrikov.core.inbox.service.InboxMaintenanceWorker;
import io.github.dgavrikov.core.inbox.service.InboxPlatformCoordinator;
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

@AutoConfiguration
@RequiredArgsConstructor
@ConditionalOnClass(NamedParameterJdbcTemplate.class)
@EnableConfigurationProperties({InboxProperties.class})
@PropertySource(value = "classpath:default_inbox.yml", factory = YamlPropertyLoaderFactory.class)
public class InboxAutoConfiguration {

    private final InboxProperties inboxProperties;
    private final Environment env;

    @Bean
    @ConditionalOnMissingBean(name = "inboxMemoryQueue")
    public BlockingQueue<InboxEvent<?>> inboxMemoryQueue() {
        return new ArrayBlockingQueue<>(inboxProperties.inMemoryQueue().capacity());
    }

    @Bean
    public TaskScheduler inboxWorkerScheduler() {
        boolean isVirtual = env.getProperty("spring.threads.virtual.enabled", Boolean.class, false);
        return new SchedulerConfigurationBuilder("inbox-worker-")
                .virtual(isVirtual)
                .poolSize(1)
                .build();
    }

    @Bean
    public TaskScheduler inboxMaintenanceScheduler() {
        boolean isVirtual = env.getProperty("spring.threads.virtual.enabled", Boolean.class, false);
        return new SchedulerConfigurationBuilder("inbox-maintenance-")
                .virtual(isVirtual)
                .poolSize(2) // Один на Recovery, один на Purge логи
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(InboxRepository.class)
    public InboxRepository inboxRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new InboxRepositoryDefault(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(InboxBatchProcessor.class)
    public InboxBatchProcessor inboxBatchProcessor(
            TaskScheduler inboxWorkerScheduler,
            BlockingQueue<InboxEvent<?>> inboxMemoryQueue,
            Collection<InboxPayloadPlugin<?>> plugins,
            InboxProperties properties,
            InboxRepository repository
    ) {
        return new InboxBatchProcessor(
                inboxWorkerScheduler,
                inboxMemoryQueue,
                plugins,
                properties,
                repository);
    }

    @Bean
    @ConditionalOnMissingBean(InboxMaintenanceWorker.class)
    public InboxMaintenanceWorker inboxMaintenanceWorker(
            BlockingQueue<InboxEvent<?>> inboxMemoryQueue,
            Collection<InboxPayloadPlugin<?>> plugins,
            TaskScheduler inboxMaintenanceScheduler,
            InboxProperties properties,
            InboxRepository repository
    ) {
        return new InboxMaintenanceWorker(
                inboxMemoryQueue,
                plugins,
                inboxMaintenanceScheduler,
                properties,
                repository);
    }

    @Bean
    @ConditionalOnMissingBean(InboxPlatformCoordinator.class)
    public InboxPlatformCoordinator inboxPlatformCoordinator(
            BlockingQueue<InboxEvent<?>> inboxMemoryQueue,
            Collection<InboxPayloadPlugin<?>> plugins,
            InboxRepository repository
    ) {
        return new InboxPlatformCoordinator(
                inboxMemoryQueue,
                plugins,
                repository);
    }
}
