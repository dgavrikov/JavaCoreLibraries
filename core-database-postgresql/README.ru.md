# Библиотека для взаимодействия с СУБД PostgreSQL

Предоставляет настроенный пул соединений HikariCP, интеграцию с Liquibase, управление транзакциями и специализированные утилиты для жизненного цикла данных JPA.

## Как подключить библиотеку

### Добавить зависимость в pom.xml

```xml
<dependency>
    <groupId>com.github.dgavrikov.core</groupId>
    <artifactId>core-database-postgresql</artifactId>
</dependency>
```

### Импортировать PostgresConfig

```java
import com.github.dgavrikov.core.config.database.PostgresConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(PostgresConfig.class)
public class ImportConfig {
}
```

### Переопределить параметры

* `repository-packages` — базовый пакет для сканирования сущностей `@Entity` и интерфейсов `JpaRepository`.
* `change-log` — расположение главного файла миграции Liquibase в classpath.

```yaml
spring:
  datasource:
    properties:
      repository-packages: com.github.dgavrikov
  liquibase:
    change-log: classpath:/db/changelog/db.master.yml
```

## Параметры конфигурации

Для настройки инфраструктуры базы данных доступны следующие переменные окружения и свойства приложения:

| Имя параметра | Значение по умолчанию | Обязательность | Описание |
| :--- | :--- | :--- | :--- |
| `DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/postgres` | НЕТ | URL-адрес для подключения к базе данных. |
| `DATASOURCE_USERNAME` | — | ДА | Имя пользователя для подключения к БД. |
| `DATASOURCE_PASSWORD` | — | ДА | Пароль для подключения к БД. |
| `DATASOURCE_MAX_POOL_SIZE` | `4` | НЕТ | Максимальный размер пула соединений HikariCP. |
| `DATASOURCE_VALIDATION_TIMEOUT_MS` | `2000` | НЕТ | Время ожидания валидации соединения (в мс). |
| `DATASOURCE_MAX_LIFETIME_MS` | `1800000` | НЕТ | Максимальное время жизни соединения в пуле (в мс). |
| `DATASOURCE_KEEP_ALIVE_TIME_MS` | `40000` | НЕТ | Интервал проверки активности соединения (в мс). |
| `DATASOURCE_MINIMUM_IDLE` | `1` | НЕТ | Минимальное количество резервных соединений в пуле HikariCP. |
| `DATASOURCE_TRANSACTION_TIMEOUT` | `50` | НЕТ | Таймаут транзакций по умолчанию (в секундах). |
| `DATASOURCE_RETRY_MAXATTEMPTS` | `3` | НЕТ | Максимальное количество попыток повторного подключения. |
| `DATASOURCE_RETRY_MAXDELAY` | `100ms` | НЕТ | Задержка между попытками повторного подключения. |
| `LIQUIBASE_ENABLED` | `true` | НЕТ | Флаг включения или выключения миграций Liquibase. |
| `LIQUIBASE_USER` | — | ДА | Пользователь для выполнения миграций Liquibase. |
| `LIQUIBASE_PASSWORD` | — | ДА | Пароль для выполнения миграций Liquibase. |
| `DATABASE_SHOW_SQL` | `false` | НЕТ | Флаг логирования SQL-запросов Hibernate в консоль. |
| `DATABASE_PLAN_CACHE_MAX_SIZE` | `512` | НЕТ | Максимальный размер кэша планов запросов Hibernate. |
| `DATABASE_PLAN_PARAMETER_METADATA_MAX_SIZE` | `128` | НЕТ | Максимальный размер кэша метаданных параметров запроса. |

> 💡 **Примечание:** Доступ к этим параметрам в рантайме можно получить из Spring-бина `DatabaseProperties`.

## Аннотации

### `@TruncateString`

Аннотация уровня поля сущности, предназначенная для автоматического обрезания строки до указанной максимальной длины перед сохранением в базу данных. Это предотвращает ошибки превышения длины данных (data truncation).

```java
import com.github.dgavrikov.core.database.annotation.TruncateString;
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
