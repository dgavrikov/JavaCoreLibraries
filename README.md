# General-Purpose Shared Libraries Project

A monorepo containing a set of reusable corporate starter libraries and core utilities optimized for enterprise applications.

## Modules Overview
1. [core-common-services](#1-core-common-services) – Logging, monitoring, distributed tracing, data masking, and core utility classes.
2. [core-correlation](#2-core-correlation) – Request HTTP header management and context correlation.
3. [core-database-postgresql](#3-core-database-postgresql) – PostgreSQL database interaction and configurations.
4. [core-kafka](#4-core-kafka) – Apache Kafka integration and messaging utilities.
5. [core-logbook-logging](#5-core-logbook-logging) – Inbound and outbound HTTP request/response logging.
6. [core-redis](#6-core-redis) – Redis and Redis Sentinel interaction module.
7. [core-uap-security-web](#7-core-uap-security-web) – Authentication and security service integration.
8. [core-xml](#8-core-xml) – XML processing and data serialization utilities.

## Build and Installation

1. Increment the revision version (`<revision>3.5.0</revision>`) in the root `pom.xml` after making changes.
2. Build and install the project locally:
   ```bash
   mvn clean install
   ```
3. Include the project as a **parent** dependency in your application's `pom.xml`:
   ```xml
   <parent>
       <groupId>com.github.dgavrikov.core</groupId>
       <artifactId>core-parent</artifactId>
       <version>3.5.0</version>
   </parent> 
   ```

---

## 1. core-common-services

Provides foundational functionality for logging, metrics monitoring, distributed tracing, and data masking, along with common utility classes.

### Key Features
* Pre-configured JSON `ObjectMapper` bean.
* Optimized collection manipulation utilities.
* Serialization and deserialization utilities with built-in compression.
* Performance-optimized string manipulation utilities.

### Quick Start

1. Add the dependency to your `pom.xml`:
   ```xml
   <dependency>
       <groupId>com.github.dgavrikov.core</groupId>
       <artifactId>core-common-services</artifactId>
   </dependency>
   ```

2. Enable component scanning in your configuration class:
   ```java
   @Configuration
   @ComponentScan(
     basePackages = {
             "com.github.dgavrikov.core.service", // Logging, Monitoring, and Tracing services
             "com.github.dgavrikov.core.config"   // Core JSON and mapping configurations
     })
   public class ImportConfig {
   }
   ```

3. Create a `logback-spring.xml` file inside your `src/main/resources` directory:
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

---

## 2. core-correlation

Manages incoming and outgoing HTTP request headers to maintain execution context across microservices.

### Quick Start

1. Add the dependency to your `pom.xml`:
   ```xml
   <dependency>
       <groupId>com.github.dgavrikov.core</groupId>
       <artifactId>core-correlation</artifactId>
   </dependency>
   ```
   
---

## 3. core-database-postgresql

Provides seamless interaction with PostgreSQL relational databases.

### Quick Start

1. Add the dependency to your `pom.xml`:
   ```xml
   <dependency>
       <groupId>com.github.dgavrikov.core</groupId>
       <artifactId>core-database-postgresql</artifactId>
   </dependency>
   ```

2. Import `PostgresConfig` into your configuration class:
   ```java
   import com.github.dgavrikov.core.config.database.PostgresConfig;
   import org.springframework.context.annotation.Configuration;
   import org.springframework.context.annotation.Import;

   @Configuration
   @Import(PostgresConfig.class)
   public class ImportConfig {
   }
   ```

3. Configure the required properties in your `application.yml`:
   * `repository-packages` – Specifies the base packages for scanning `@Entity` classes and `JpaRepository` interfaces.
   * `change-log` – Specifies the location of Liquibase database migration files.

   ```yaml
   spring:
     datasource:
       properties:
         repository-packages: com.github.dgavrikov
     liquibase:
       change-log: classpath:/db/changelog/db.master.yml
   ```

For detailed configuration parameters, refer to the [Postgres Module Documentation](./core-database-postgresql/README.md).

---

## 4. core-kafka

Provides out-of-the-box integration with Apache Kafka for event-driven messaging.

### Quick Start

1. Add the dependency to your `pom.xml`:
   ```xml
   <dependency>
       <groupId>com.github.dgavrikov.core</groupId>
       <artifactId>core-kafka</artifactId>
   </dependency>
   ```
2. Configure application properties: [YAML Configuration Guide](./core-kafka/README.md)
3. Set up custom configuration classes: [Java Config Guide](./core-kafka/README.md)
4. Implement a message producer: [Producer Example](./core-kafka/README.md)
5. Implement a message subscriber: [Subscriber Example](./core-kafka/README.md)

---

## 5. core-logbook-logging

Automates high-performance logging for inbound and outbound HTTP requests and responses using Zalando Logbook integration.

### Quick Start

1. Add the dependency to your `pom.xml`:
   ```xml
   <dependency>
       <groupId>com.github.dgavrikov.core</groupId>
       <artifactId>core-logbook-logging</artifactId>
   </dependency>
   ```

---

## 6. core-redis

Provides integration with Redis and Redis Sentinel clusters for high-performance caching and state management.

### Quick Start

1. Add the dependency to your `pom.xml`:
   ```xml
   <dependency>
       <groupId>com.github.dgavrikov.core</groupId>
       <artifactId>core-redis</artifactId>
   </dependency>
   ```

2. Enable the Redis configuration using component scanning:
   ```java
   @Configuration
   @ComponentScan(basePackageClasses = {AutoConfigurationRedis.class})
   public class ImportConfig {
   }
   ```

---

## 7. core-uap-security-web

Provides seamless integration with the centralized authentication and Identity Provider (IdP) service.

### Quick Start

1. Add the dependency to your `pom.xml`:
   ```xml
   <dependency>
       <groupId>com.github.dgavrikov.core</groupId>
       <artifactId>core-uap-security-web</artifactId>
   </dependency>
   ```
2. Configure application properties: [YAML Configuration Guide](./core-uap-security-web/README.md)
3. Set up custom configuration classes: [Java Config Guide](./core-uap-security-web/README.md)

---

## 8. core-xml

Provides utilities for advanced XML processing, parsing, and data serialization.

### Quick Start

1. Add the dependency to your `pom.xml`:
   ```xml
   <dependency>
       <groupId>com.github.dgavrikov.core</groupId>
       <artifactId>core-xml</artifactId>
   </dependency>
   ```

2. Enable the XML configuration in your configuration class:
   ```java
   import com.github.dgavrikov.core.xml.AutoConfigurationXml;
   import org.springframework.context.annotation.ComponentScan;
   import org.springframework.context.annotation.Configuration;

   @Configuration
   @ComponentScan(basePackages = {
           "com.github.dgavrikov.core.xml.config",
           "com.github.dgavrikov.core.xml.service"
   }, basePackageClasses = {AutoConfigurationXml.class})
   public class ImportConfig {
   }
   ```

3. Configure the base package properties in your `application.yml`:
   ```yaml
   com:
     github:
       dgavrikov:
         core:
           xml:
             properties: com.github.dgavrikov
   ```
