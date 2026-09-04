# Проект библиотек общего назначения

Репозиторий включает в себя набор переиспользуемых стартеров и базовых утилит, оптимизированных для enterprise-приложений.

## Список библиотек
1. [core-common-services](#1-core-common-services) – библиотека для работы с логированием, мониторингом, трассировкой и маскированием данных. Базовые утилиты.
2. [core-correlation](#2-core-correlation) – библиотека для работы с хедерами запросов и контекстом выполнения.
3. [core-database-postgresql](#3-core-database-postgresql) – библиотека для взаимодействия с СУБД PostgreSQL.
4. [core-kafka](#4-core-kafka) – библиотека для интеграции и взаимодействия с Apache Kafka.
5. [core-logbook-logging](#5-core-logbook-logging) – библиотека логирования входящих и исходящих HTTP-запросов и ответов.
6. [core-redis](#6-core-redis) – библиотека для взаимодействия с Redis и Redis Sentinel.
7. [core-uap-security-web](#7-core-uap-security-web) – библиотека для интеграции с сервисом аутентификации и безопасности.
8. [core-xml](#8-core-xml) – библиотека для обработки XML и сериализации данных.
9. [core-state-machine](#9-core-state-machine) – библиотека легковесной декларативной машины состояний, оптимизированная под High Load.

## Сборка и установка

1. После внесения изменений обновите версию ревизии (`<revision>3.5.0</revision>`) в корневом `pom.xml`.
2. Выполните сборку и локальную установку проекта:
   ```bash
   mvn clean install
   ```
3. Подключите проект в ваше приложение в качестве **parent** зависимости:
   ```xml
   <parent>
       <groupId>io.github.dgavrikov</groupId>
       <artifactId>core-parent</artifactId>
       <version>3.5.0</version>
   </parent> 
   ```

---

## 1. core-common-services

Предоставляет базовую функциональность для работы с логированием, мониторингом метрик, распределенной трассировкой и маскированием данных, а также общие утилитарные классы.

### Основные возможности
* Предопределенный и настроенный бин `ObjectMapper` для работы с JSON.
* Оптимизированные утилиты для работы с коллекциями.
* Утилиты сериализации и десериализации со встроенной поддержкой сжатия данных.
* Высокопроизводительные утилиты для работы со строками.
* Конфигуратор планировщика с поддержкой виртуальных потоков.

### Как подключить

1. Добавьте зависимость в ваш `pom.xml`:
   ```xml
   <dependency>
       <groupId>io.github.dgavrikov</groupId>
       <artifactId>core-common-services</artifactId>
   </dependency>
   ```

2. Включите сканирование компонентов в вашем конфигурационном классе:
   ```java
   @Configuration
   @ComponentScan(
     basePackages = {
             "io.github.dgavrikov.core.service", // Сервисы логирования, мониторинга и трассировки
             "io.github.dgavrikov.core.config"   // Базовые конфигурации JSON и маппинга
     })
   public class ImportConfig {
   }
   ```

3. Создайте файл `logback-spring.xml` в директории `src/main/resources`:
   ```xml
   <?xml version="1.0" encoding="UTF-8" ?>
   <configuration>
       <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
       
       <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
           <encoder>
               <pattern>
                   %clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} %clr(%5p) %clr([%t]){faint} %clr([%X{traceId:-},%X{spanId:-}]) %clr(%-40.40logger{39}){cyan}%clr(:){faint} %m%n%wEx
               </pattern>
           </encoder>
       </appender>
       
       <root level="INFO">
           <appender-ref ref="CONSOLE"/>
       </root>
   </configuration>
   ```

### Конфигуратор планировщика с поддержкой виртуальных потоков.

Пример использования конфигуратора.

**Конфигурация.**

```java
package com.github.dgavrikov.examples.config;

import config.io.github.dgavrikov.core.SchedulerConfigurationBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync@Configuration
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class SchedulerConfig {
    private final Environment env;

    @Bean
    public TaskScheduler metricScheduler() {
        boolean isVirtual = env.getProperty("spring.threads.virtual.enabled", Boolean.class, false);

        return new SchedulerConfigurationBuilder("metric-scheduler-")
                .virtual(isVirtual)
                .poolSize(3)
                .build();
    }

    @Bean
    public TaskScheduler backgroundWorker() {
        boolean isVirtual = env.getProperty("spring.threads.virtual.enabled", Boolean.class, false);

        return new SchedulerConfigurationBuilder("background-worker-")
                .virtual(isVirtual)
                .poolSize(50) // для тяжелых задач пул можно легко увеличить
                .build();
    }
}
```

**Планировщик.**

```java
package com.github.dgavrikov.examples.scheduler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class MetricScheduler {

    @Qualifier("metricScheduler")
    private final TaskScheduler metricScheduler;

    ///@Value("${...}")
    private String cron = "*/15 * * * * *";

    @PostConstruct
    void init(){
        var cronTrigger = new CronTrigger(cron);
        metricScheduler.schedule(this::process, cronTrigger);
    }

    public void process() {
        // do run
    }
}
```

---

## 2. core-correlation

Управляет входящими и исходящими HTTP-заголовками для сохранения сквозного контекста выполнения между микросервисами.
Служит для организации сквозной трассировки (Distributed Tracing), которая связывает логи разных микросервисов в единую 
цепочку, позволяя по одному traceId отследить путь запроса от фронтенда до базы данных.

### Как подключить

1. Добавьте зависимость в ваш `pom.xml`:
   ```xml
   <dependency>
       <groupId>io.github.dgavrikov</groupId>
       <artifactId>core-correlation</artifactId>
   </dependency>
   ```

---

## 3. core-database-postgresql

Библиотека для взаимодействия с СУБД PostgreSQL.

### Как подключить

1. Добавьте зависимость в ваш `pom.xml`:
   ```xml
   <dependency>
       <groupId>io.github.dgavrikov</groupId>
       <artifactId>core-database-postgresql</artifactId>
   </dependency>
   ```

2. Импортируйте `PostgresConfig` в ваш конфигурационный класс:
   ```java
   import io.github.dgavrikov.core.config.database.PostgresConfig;
   import org.springframework.context.annotation.Configuration;
   import org.springframework.context.annotation.Import;

   @Configuration
   @Import(PostgresConfig.class)
   public class ImportConfig {
   }
   ```

3. Переопределите параметры в `application.yml`:
   * `repository-packages` — расположение сканируемых файлов `@Entity` и интерфейсов `JpaRepository`.
   * `change-log` — расположение файлов миграции базы данных Liquibase.

   ```yaml
   spring:
     datasource:
       properties:
         repository-packages: io.github.dgavrikov
     liquibase:
       change-log: classpath:/db/changelog/db.master.yml
   ```

Дополнительные сведения о параметрах настройки можно получить [тут](./core-database-postgresql/README.ru.md).

---

## 4. core-kafka

Библиотека для взаимодействия с брокером сообщений Apache Kafka.

### Как подключить

1. Добавьте зависимость в ваш `pom.xml`:
   ```xml
   <dependency>
       <groupId>io.github.dgavrikov</groupId>
       <artifactId>core-kafka</artifactId>
   </dependency>
   ```
2. Настройте конфигурацию параметров: [Ссылка на руководство по YAML](./core-kafka/README.ru.md)
3. Создайте классы конфигурации: [Ссылка на руководство по Java Config](./core-kafka/README.ru.md)
4. Пример реализации продюсера: [Ссылка на Producer Example](./core-kafka/README.ru.md)
5. Пример реализации подписчика: [Ссылка на Subscriber Example](./core-kafka/README.ru.md)

---

## 5. core-logbook-logging

Библиотека для автоматического логирования входящих и исходящих HTTP-запросов и ответов.

### Как подключить

1. Добавьте зависимость в ваш `pom.xml`:
   ```xml
   <dependency>
       <groupId>io.github.dgavrikov</groupId>
       <artifactId>core-logbook-logging</artifactId>
   </dependency>
   ```

---

## 6. core-redis

Библиотека для взаимодействия с Redis и Redis Sentinel для обеспечения работы кэширования и распределенных данных.

### Как подключить

1. Добавьте зависимость в ваш `pom.xml`:
   ```xml
   <dependency>
       <groupId>io.github.dgavrikov</groupId>
       <artifactId>core-redis</artifactId>
   </dependency>
   ```

2. Подключите конфигурацию Redis через сканирование компонентов:
   ```java
   @Configuration
   @ComponentScan(basePackageClasses = {AutoConfigurationRedis.class})
   public class ImportConfig {
   }
   ```

---

## 7. core-uap-security-web

Библиотека для интеграции с сервисом аутентификации и проверки прав доступа.

### Как подключить

1. Добавьте зависимость в ваш `pom.xml`:
   ```xml
   <dependency>
       <groupId>io.github.dgavrikov</groupId>
       <artifactId>core-uap-security-web</artifactId>
   </dependency>
   ```
2. Настройте конфигурацию параметров: [Ссылка на руководство по YAML](./core-uap-security-web/README.ru.md)
3. Создайте классы конфигурации: [Ссылка на руководство по Java Config](./core-uap-security-web/README.ru.md)

---

## 8. core-xml

Библиотека для обработки XML-документов и сериализации данных.

### Как подключить

1. Добавьте зависимость в ваш `pom.xml`:
   ```xml
   <dependency>
       <groupId>io.github.dgavrikov</groupId>
       <artifactId>core-xml</artifactId>
   </dependency>
   ```

2. Настройте автоконфигурацию в вашем конфигурационном классе:
   ```java
   import xml.io.github.dgavrikov.core.AutoConfigurationXml;
   import org.springframework.context.annotation.ComponentScan;
   import org.springframework.context.annotation.Configuration;

   @Configuration
   @ComponentScan(basePackages = {
           "io.github.dgavrikov.core.xml.config",
           "io.github.dgavrikov.core.xml.service"
   }, basePackageClasses = {AutoConfigurationXml.class})
   public class ImportConfig {
   }
   ```

3. Переопределите параметры базового пакета в `application.yml`:
   ```yaml
   io:
     github:
       dgavrikov:
         core:
           xml:
             properties: io.github.dgavrikov
   ```

## 9. core-state-machine

Предоставляет надежную, высокопроизводительную и полностью Stateless-архитектуру конечного автомата на базе Java Records со встроенным сквозным Run-To-Completion (RTC) выполнением без оверхеда и аллокаций памяти в High Load.

### Как подключить

1. Добавьте зависимость в ваш `pom.xml`:
   ```xml
   <dependency>
       <groupId>io.github.dgavrikov</groupId>
       <artifactId>core-state-machine</artifactId>
   </dependency>
   ```
2. Настройте конфигурацию параметров: [Ссылка на руководство по YAML](./core-state-machine/README.ru.md#конфигурация-yaml)
3. Создайте классы конфигурации: [Ссылка на руководство по Java Config](./core-state-machine/README.ru.md#конфигурация-классов-java)
4. Пример реализации доменной модели и хэндлеров: [Ссылка на примеры](./core-state-machine/README.ru.md#пример-реализации-доменной-модели)
