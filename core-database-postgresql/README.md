# PostgreSQL Database Interaction Library

Provides pre-configured HikariCP connection pooling, Liquibase integration, transaction management, and specialized JPA data lifecycle utilities.

## Installation

### Add Dependency to pom.xml

```xml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-database-postgresql</artifactId>
</dependency>
```

### Import PostgresConfig

```java
import io.github.dgavrikov.core.config.database.PostgresConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(PostgresConfig.class)
public class ImportConfig {
}
```

### Override Application Properties

* `repository-packages` – Base package path for scanning `@Entity` classes and `JpaRepository` interfaces.
* `change-log` – Classpath location of the Liquibase master migration file.

```yaml
spring:
  datasource:
    properties:
      repository-packages: io.github.dgavrikov
  liquibase:
    change-log: classpath:/db/changelog/db.master.yml
```

## Configuration Reference

The following environment variables and application properties are available for configuring the database infrastructure:

| Parameter Name | Default Value | Required | Description |
| :--- | :--- | :--- | :--- |
| `DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/postgres` | NO | Database connection URL. |
| `DATASOURCE_USERNAME` | — | YES | Database connection username. |
| `DATASOURCE_PASSWORD` | — | YES | Database connection password. |
| `DATASOURCE_MAX_POOL_SIZE` | `4` | NO | HikariCP connection pool maximum size. |
| `DATASOURCE_VALIDATION_TIMEOUT_MS` | `2000` | NO | Connection validation timeout (in milliseconds). |
| `DATASOURCE_MAX_LIFETIME_MS` | `1800000` | NO | Maximum lifetime of a connection in the pool (in milliseconds). |
| `DATASOURCE_KEEP_ALIVE_TIME_MS` | `40000` | NO | Interval to check connection liveliness (in milliseconds). |
| `DATASOURCE_MINIMUM_IDLE` | `1` | NO | Minimum number of idle connections maintained by HikariCP. |
| `DATASOURCE_TRANSACTION_TIMEOUT` | `50` | NO | Default transaction timeout (in seconds). |
| `DATASOURCE_RETRY_MAXATTEMPTS` | `3` | NO | Maximum reconnection retry attempts. |
| `DATASOURCE_RETRY_MAXDELAY` | `100ms` | NO | Delay between reconnection retry attempts. |
| `LIQUIBASE_ENABLED` | `true` | NO | Flag to enable or disable Liquibase database migrations. |
| `LIQUIBASE_USER` | — | YES | Database user dedicated to executing Liquibase migrations. |
| `LIQUIBASE_PASSWORD` | — | YES | Password for the Liquibase migration user. |
| `DATABASE_SHOW_SQL` | `false` | NO | Flag to enable Hibernate SQL query logging to the console. |
| `DATABASE_PLAN_CACHE_MAX_SIZE` | `512` | NO | Maximum size of the Hibernate query plan cache. |
| `DATABASE_PLAN_PARAMETER_METADATA_MAX_SIZE` | `128` | NO | Maximum size of the Hibernate query parameter metadata cache. |

> 💡 **Tip:** Programmatic access to these resolved parameters is available at runtime via the `DatabaseProperties` Spring bean.

## Annotations

### `@TruncateString`

An entity field-level annotation designed to automatically truncate string values to a specified maximum length before persisting them to the database, preventing data truncation errors.

```java
import io.github.dgavrikov.core.database.annotation.TruncateString;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "person")
public class PersonEntity {
    
    @Column(name = "name", length = 50)
    @TruncateString(maxLength = 50)
    private String name;
}
```
