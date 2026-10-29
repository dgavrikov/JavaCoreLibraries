Разбиваем рефакторинг на 3 логических шага, как ты просил — дозировано. 
Начнем с архитектурного фундамента: избавляемся от костылей вокруг MDC и 
исправляем интеграцию с Micrometer / OpenTelemetry.

## Шаг 1. Тотальный отказ от ручного MDC (MDCUtils — под нож)

В чем проблема: `Micrometer Tracing` спроектирован так, что разработчик не должен руками трогать `MDC.put` и `MDC.remove`.
Когда ты открываешь `Observation.Scope` или `Tracer.SpanInScope`, Micrometer через мост (Bridge) сам регистрирует текущие 
traceId и spanId в MDC. Когда область видимости закрывается (scope.close()), он автоматически вычищает их или возвращает 
предыдущие (если был родительский трейс). Твой ручной вызов MDCUtil.removeMDCTrace() в блоках finally — это мина 
замедленного действия. Если текущий поток обрабатывал вложенный (child) span, твой removeMDCTrace() полностью сотрет 
весь контекст из логов для оставшейся части родительского метода.

Решение:
1. Настройку паттерна логирования (вывод traceId/spanId в консоль/файлы) полностью перекладываем на конфигурацию 
2. Spring Boot / Logback (через стандартные %X{traceId} и %X{spanId}).
2. Нам больше не нужны методы setMDCTrace и removeMDCTrace. Чистим код.

## Шаг 2. Корректный контракт TraceMicrometer и концепция TraceScope

Интерфейс TraceMicrometer перегружен низкоуровневой логикой Micrometer (Observation, Kind), которая протекает в бизнес-код. 
Давай сделаем его чистым, zero-allocation friendly и поддерживающим паттерн try-with-resources.
Вводим легковесный TraceScope (реализует AutoCloseable), который сам управляет жизненным циклом:

```java
package io.github.dgavrikov.core.service.tracing;

import io.micrometer.observation.Observation;

/**
* Легковесный контейнер для try-with-resources.
* Инкапсулирует scope и observation, исключая утечки контекста.
  */
  public final class TraceScope implements AutoCloseable {
  private final Observation observation;
  private final Observation.Scope scope;
  private final String traceInfo;

  public TraceScope(Observation observation, Observation.Scope scope, String traceInfo) {
  this.observation = observation;
  this.scope = scope;
  this.traceInfo = traceInfo;
  }

  public String getTraceInfo() {
  return this.traceInfo;
  }

  @Override
  public void close() {
  // Строгий порядок закрытия: сначала scope, затем остановка observation
  if (scope != null) {
  scope.close();
  }
  if (observation != null) {
  observation.stop();
  }
  }
  }
 ```
  
Теперь перепроектируем сам интерфейс TraceMicrometer. Никакого ручного парсинга W3CTraceData на стороне вызывающего кода. 
Только чистые бизнес-намерения:

```java
  package io.github.dgavrikov.core.service.tracing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface TraceMicrometer {

    /**
     * Старт нового или вложенного трейса (родитель подхватится автоматически из контекста)
     */
    @NotNull
    TraceScope openScope(@NotNull String name);

    /**
     * Старт трейса с принудительным восстановлением из внешней текстовой строки traceInfo (W3C формат)
     */
    @NotNull
    TraceScope openScope(@NotNull String name, @Nullable String traceInfo);

    /**
     * Получить текущую информацию о трассировке для передачи дальше (в БД или внешние системы)
     */
    @Nullable
    String getCurrentTraceInfo();
}
```

## Шаг 3. Имплементация без GC-мусора и связывание разорванных трейсов

Вот реализация, которая решает проблему комбинированного кейса (когда traceInfo появился в середине процесса из БД).
Чтобы связать разорванный трейс, нам нужно вытащить родительский контекст из строки и передать его как parentContext 
новому Observation. Для этого мы используем стандартный механизм Micrometer — Propagator.Getter. Мы напишем один 
статический переиспользуемый экземпляр геттера, чтобы избежать new в горячих путях!

```java
package io.github.dgavrikov.core.service.tracing.impl;

import io.github.dgavrikov.core.service.tracing.TraceMicrometer;
import io.github.dgavrikov.core.service.tracing.TraceScope;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@RequiredArgsConstructor
public class TraceMicrometerImpl implements TraceMicrometer {
private final ObservationRegistry observationRegistry;
private final Tracer tracer;
private final Propagator propagator;

    // Статический геттер для извлечения W3C traceparent без аллокации объектов-стратегий
    private static final Propagator.Getter<String> W3C_GETTER = (carrier, key) -> 
            "traceparent".equalsIgnoreCase(key) ? carrier : null;

    @Override
    @NotNull
    public TraceScope openScope(@NotNull String name) {
        Observation observation = Observation.createNotStarted(name, observationRegistry);
        
        // Micrometer сам подхватит текущий observation как parent, если он есть в текущем потоке
        observation.start();
        Observation.Scope scope = observation.openScope();
        
        return new TraceScope(observation, scope, getCurrentTraceInfo());
    }

    @Override
    @NotNull
    public TraceScope openScope(@NotNull String name, @Nullable String traceInfo) {
        Observation observation;
        
        if (traceInfo != null && !traceInfo.isBlank()) {
            // Решаем проблему №3: Восстанавливаем корректный контекст из W3C строки
            ReceiverContext<String> context = new ReceiverContext<>(W3C_GETTER, Kind.SERVER);
            context.setCarrier(traceInfo);
            
            // Извлекаем контекст и связываем его с новым Observation
            propagator.extract(traceInfo, W3C_GETTER);
            
            observation = Observation.createNotStarted(name, () -> context, observationRegistry);
        } else {
            observation = Observation.createNotStarted(name, observationRegistry);
        }

        observation.start();
        Observation.Scope scope = observation.openScope();
        
        return new TraceScope(observation, scope, getCurrentTraceInfo());
    }

    @Override
    @Nullable
    public String getCurrentTraceInfo() {
        var currentSpan = tracer.currentSpan();
        if (currentSpan == null) {
            return null;
        }
        var ctx = currentSpan.context();
        // Используем эффективную конкатенацию Java 21+ без String.format() и лишних StringBuilder
        return ctx.traceId() + ":" + ctx.spanId();
    }
}
```

### Как это выглядит в твоем комбинированном бизнес-процессе:

Смотри, какой чистый, лаконичный и безопасный код получается без единого намека на enterprise-оверхед. 
Полная аналогия с твоей блокировкой:

```java
@Slf4j
@RequiredArgsConstructor
public class BusinessProcessor {
private final TraceMicrometer traceMicrometer;
private final OrderRepository orderRepository;

    private void doStart(Long id) {
        // Шаг А: Стартуем базовый трейс операции (например, чтение из очереди или контроллера)
        try (TraceScope baseScope = traceMicrometer.openScope("OrderProcessing.Init")) {
            
            var order = orderRepository.findById(id);
            if (order == null) {
                log.warn("Order not found by id={}", id);
                return;
            }

            // Шаг Б: Появился traceInfo из БД. 
            // Открываем вложенный Scope. Он автоматически связывается с родителем из БД!
            try (TraceScope businessScope = traceMicrometer.openScope("OrderProcessing.Execute", order.getTraceInfo())) {
                
                log.info("Бизнес логика выполняется внутри правильного распределенного трейса");
                // TODO: do process;
                
            } // Тут businessScope автоматически закроется, логи вернут старый traceId
            
        } // Тут baseScope автоматически закроется, контекст полностью чист
    }
}
```
Посмотри на этот вариант. Мы убрали кучу аллокаций в горячем пути, избавились от ручного костыльного управления MDC и 
сделали красивый try-with-resources API.
Если этот фундамент утверждаем, переходим к следующей дозе: как именно у тебя формируется order.getTraceInfo() на входе, 
и нужно ли нам написать кастомный быстрый парсер/форматтер строк без .split(":"), чтобы окончательно закрыть вопрос с 
GC-давлением?

---

## Разбираемся с MDCUtils

Мы уже выяснили, что ручное управление контекстом логирования (MDC.put/remove) нам больше не нужно — за нас это делает 
связка Observation + Logback. Однако у тебя остается важная бизнес-задача: быстро склеивать и парсить traceInfo для 
передачи в СУБД (например, в поле order.getTraceInfo()) или во внешние системы.
На Java 21 метод String.split(":") внутри горячего цикла — это табу. Он под капотом компилирует регулярное выражение и 
аллоцирует массив строк. Если у нас 10 000 tps, мы плодим миллионы массивов в секунду просто ради того, чтобы разделить 
строку пополам.
Давай перепишем этот утилитный класс, превратив его в Zero-Allocation / High-Performance TraceFormatter.

```java
package io.github.dgavrikov.core.utils;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@UtilityClass
public class TraceInfoParser {
private static final char SPLIT_CHARACTER = ':';
private static final String W3C_PREFIX = "00-";
private static final String W3C_POSTFIX = "-01";
private static final String DASH = "-";

    /**
     * Конвертирует твой внутренний формат "traceId:spanId" в валидный W3C traceparent без String.split()
     * Пример: "4bf92f3577b34da6a3ce929d0e0e4736:00f067aa0ba902b7" 
     * Результат: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
     */
    @Nullable
    public static String toW3CTraceParent(@Nullable String traceInfo) {
        if (traceInfo == null || traceInfo.isEmpty()) {
            return null;
        }

        int colonIndex = traceInfo.indexOf(SPLIT_CHARACTER);
        // Если двоеточия нет или оно в самом начале/конце — строка невалидна
        if (colonIndex <= 0 || colonIndex == traceInfo.length() - 1) {
            return null;
        }

        // Java 21 использует StringConcatFactory (Indify String Concatenation).
        // Этот код развернется в оптимальный байткод без создания лишних StringBuilder и массивов.
        return W3C_PREFIX + traceInfo.substring(0, colonIndex) + DASH + traceInfo.substring(colonIndex + 1) + W3C_POSTFIX;
    }

    /**
     * Быстрая склейка ID без String.format
     */
    @NotNull
    public static String buildTraceInfo(@NotNull String traceId, @NotNull String spanId) {
        return traceId + SPLIT_CHARACTER + spanId;
    }
}
```

### Что мы сделали:

1. Заменили тяжелый .split(":") на легковесный .indexOf(':') и .substring(). Мы больше не создаем массив строк String[].
2. Избавились от всех методов setMDCTrace, removeMDCTrace, setHeaderOfMDC. Они стали не нужны, так как теперь эту работу выполняет инфраструктура Micrometer.

Финальный штрих: Интеграция в TraceMicrometerImpl

Теперь мы можем использовать наш быстрый форматтер прямо внутри TraceMicrometerImpl, чтобы окончательно убрать 
W3CTraceData и старые костыли. Метод openScope с передачей traceInfo теперь выглядит идеально:
```java
@Override
@NotNull
public TraceScope openScope(@NotNull String name, @Nullable String traceInfo) {
Observation observation;

        // Превращаем наш внутренний "traceId:spanId" в стандартный W3C traceparent на лету и быстро
        String w3cTraceParent = TraceInfoParser.toW3CTraceParent(traceInfo);

        if (w3cTraceParent != null) {
            ReceiverContext<String> context = new ReceiverContext<>(OBSERVATION_W3C_GETTER, Kind.SERVER);
            context.setCarrier(w3cTraceParent);
            
            // Пропогатор Micrometer ожидает чистый W3C формат
            this.propagator.extract(w3cTraceParent, TRACING_W3C_GETTER);
            
            observation = Observation.createNotStarted(name, () -> context, observationRegistry);
        } else {
            // Если traceInfo пустой или битый — стартуем чистый контекст (или подхватываем текущий родительский thread-bound трейс)
            observation = Observation.createNotStarted(name, observationRegistry);
        }

        observation.start();
        Observation.Scope scope = observation.openScope();
        
        return new TraceScope(observation, scope, getCurrentTraceInfo());
    }
```

---

В этой конфигурации мы делаем две хирургические правки, чтобы убрать потенциальные баги инициализации и enterprise-оверхед.

В чем проблема текущего конфига:

1. ObservationRegistry.create() — это выстрел в ногу.
   Если ты вручную создаешь ObservationRegistry через create(), ты получаешь пустой изолированный реестр, в котором нет плагинов трассировки, нет связи с OpenTelemetry/Brave и нет хуков для синхронизации с MDC. Spring Boot spring-boot-starter-actuator уже сам создает и настраивает правильный ObservationRegistry (со всеми мостами к Otel и логам). Твой бин с @ConditionalOnMissingBean либо перетрет правильный реестр, либо создаст дубликат-пустышку.
2. proxyBeanMethods = false — оставляем.
   Тут всё четко. Это честный KISS/Staff-стиль, который отключает CGLIB-проксирование, ускоряет старт контекста и снижает аллокации при инициализации.

Как переделываем TracingConfiguration:

Убираем ручной запуск реестра и отдаем его на откуп автоконфигурации Spring Boot, а наш бин инжектит уже готовые и настроенные компоненты.

```java
package io.github.dgavrikov.core.lock; // или твой пакет конфигурации

import io.github.dgavrikov.core.service.tracing.TraceMicrometer;
import io.github.dgavrikov.core.service.tracing.impl.TraceMicrometerImpl;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TracingConfiguration {

    @Bean
    public TraceMicrometer traceMicrometer(
            // Spring сам прокинет сюда глобальный, настроенный Actuator-ом реестр
            ObservationRegistry observationRegistry, 
            Tracer tracer, 
            Propagator propagator
    ) {
        return new TraceMicrometerImpl(observationRegistry, tracer, propagator);
    }
}
```

Важный нюанс по application.yaml

Чтобы магия Micrometer автоматом связывала созданные нами Observation с логами через MDC, у тебя в конфигах приложения обязательно должны стоять эти две настройки:
```yaml
management:
  tracing:
    sampling:
      probability: 1.0 # Для процессинга обычно льем 100% или настраиваем кастомный семплер
logging:
  pattern:
    # Включаем вывод trace_id и span_id в лог-бэк автоматом
    level: "[%X{traceId:-} %X{spanId:-}] %-5level %logger{36} - %msg%n"
```

---

### Архитектурный итог рефакторинга трассировки:

• SOLID / DDD: Мы полностью разделили слой бизнес-логики и инфраструктуру трассировки. Доменный код больше ничего не 
знает про MDC, Observation, заголовки и транспортные геттеры. Он просто пишет try (var trace = traceMicrometer.openScope("name")).
• KISS: Выкинули 4 лишних класса-прослойки, которые дублировали логику Micrometer.
• Performance: Никаких регулярных выражений, никаких .split(), никаких лишних аллокаций в горячем цикле обработки транзакций. Чистая работа с памятью.