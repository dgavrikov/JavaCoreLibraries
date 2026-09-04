package io.github.dgavrikov.core.config;

import io.opentelemetry.context.Context;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
public class SchedulerConfigurationBuilder {
    private final String prefix;
    private boolean isVirtual = false;
    private int poolSize = 3;
    private TaskDecorator customDecorator;
    private RejectedExecutionHandler rejectedHandler = new ThreadPoolExecutor.DiscardPolicy();

    public SchedulerConfigurationBuilder(String prefix) {
        this.prefix = prefix;
    }

    public SchedulerConfigurationBuilder virtual(boolean isVirtual) {
        this.isVirtual = isVirtual;
        return this;
    }

    public SchedulerConfigurationBuilder poolSize(int poolSize) {
        this.poolSize = poolSize;
        return this;
    }

    public SchedulerConfigurationBuilder decorator(TaskDecorator customDecorator) {
        this.customDecorator = customDecorator;
        return this;
    }

    public SchedulerConfigurationBuilder rejectedHandler(RejectedExecutionHandler rejectedHandler) {
        this.rejectedHandler = rejectedHandler;
        return this;
    }

    public TaskScheduler build() {
        TaskDecorator decorator = (customDecorator != null)
                ? customDecorator
                : SchedulerConfigurationBuilder::defaultOtelDecorator;

        if (isVirtual) {
            var scheduler = new SimpleAsyncTaskScheduler();
            scheduler.setConcurrencyLimit(poolSize);
            scheduler.setThreadNamePrefix(prefix);
            scheduler.setVirtualThreads(true);
            scheduler.setTaskDecorator(decorator);
            return scheduler;
        } else {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setThreadNamePrefix(prefix);
            scheduler.setPoolSize(poolSize);
            scheduler.setTaskDecorator(decorator);
            scheduler.setRejectedExecutionHandler(rejectedHandler);
            scheduler.initialize();
            return scheduler;
        }
    }

    private static Runnable defaultOtelDecorator(Runnable runnable) {
        var context = Context.current();
        return () -> {
            try (var ignored = context.makeCurrent()) {
                runnable.run();
            } catch (Throwable t) {
                log.error("Unhandled error in task: {}", t.getMessage(), t);
            }
        };
    }
}
