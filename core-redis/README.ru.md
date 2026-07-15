# Библиотека для взаимодействия с Redis/Sentinel

Предоставляет настроенную интеграцию с изолированными экземплярами Redis и отказоустойчивыми кластерами Redis Sentinel для кэширования данных и управления состоянием.

## Как подключить библиотеку

### Добавить зависимость в pom.xml

```xml
<dependency>
    <groupId>com.github.dgavrikov.core</groupId>
    <artifactId>core-redis</artifactId>
</dependency>
```

## Настройка

### Настроить автоконфигурацию

Активируйте встроенные компоненты инфраструктуры Redis, добавив класс `AutoConfigurationRedis` в область сканирования компонентов вашего конфигурационного класса:

```java
import com.github.dgavrikov.core.redis.AutoConfigurationRedis;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackageClasses = {AutoConfigurationRedis.class})
public class ImportConfig {
}
```
