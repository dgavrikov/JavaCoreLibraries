# TraceMicrometer

## Варианты использования сейчас.

### Когда не известен traceInfo

```java
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class BusinessProcessor() {
    private final TraceMicrometer traceMicrometer;

    private void doStart() {
        var observation = traceMicrometer.getObservation(BusinessProcessor.class.getSimpleName()).start();
        var scope = observation.openScope();
        try {
            //TODO: do process;
            
        } catch (RuntimeException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new RuntimeException(e);
        }finally {
            scope.close();
            observation.stop();
            MDCUtil.removeMDCTrace();
        }
    }
}
```

### Когда traceInfo известен.

```java
import io.micrometer.observation.transport.Kind;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class BusinessProcessor() {
    private final TraceMicrometer traceMicrometer;

    private void doStart(String traceInfo) {
        var observation = traceMicrometer.getObservation(
                new W3CTraceData(MDCUtil.traceParent(traceInfo)),
                BusinessProcessor.class.getSimpleName(),
                Kind.SERVER).start();
        var scope = observation.openScope();
        try {
            //TODO: do process;

        } catch (RuntimeException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new RuntimeException(e);
        } finally {
            scope.close();
            observation.stop();
            MDCUtil.removeMDCTrace();
        }
    }
}

```

### Комбинированный вариант. traceInfo появляется в середине процесса.

```java
import io.micrometer.observation.transport.Kind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class BusinessProcessor() {
    private final TraceMicrometer traceMicrometer;
    private final OrderRepository orderRepository;

    private void doStart(Long id) {

        var trace = traceMicrometer.getObservation(BusinessProcessor.class.getSimpleName()).start();
        var scope = trace.openScope();

        try {
        var order = orderRepository.findById(id);

        if (order == null) {
            log.warn("Order not found by id={}", id);
            return;
        }

        var observation = traceMicrometer.getObservation(
                new W3CTraceData(MDCUtil.traceParent(order.getTraceInfo)),
                BusinessProcessor.class.getSimpleName(),
                Kind.SERVER).start();
        try(var ignore = observation.openScope()){
            // TODO: do process;
        } finally{
            observation.stop();
        }

        } catch (RuntimeException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new RuntimeException(e);
        } finally {
            scope.close();
            trace.stop();
            MDCUtil.removeMDCTrace();
        }
    }
}

```

## Что хочется?

добавить методы в TraceMicrometer или сделать обертки для использования трассировки.

### Пример реализации

```java
import io.micrometer.observation.transport.Kind;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;
import java.util.function.Function;

@UtilityClass
public class TraceMicrometerUtils {
    public static void executeWithoutResult(
            @NotNull TraceMicrometer traceMicrometer,
            @NotNull String name,
            @NotNull Consumer<String> executor
    ) {
        var observation = traceMicrometer.getObservation(name).start();
        var scope = observation.openScope();
        try {
            executor.accept(traceMicrometer.getTraceInfo());
        } finally {
            scope.close();
            observation.stop();
            MDCUtil.removeMDCTrace();
        }
    }

    public static <R> R executeWithResult(
            @NotNull TraceMicrometer traceMicrometer,
            @NotNull String name,
            @NotNull Function<String, R> executor
    ) {
        var observation = traceMicrometer.getObservation(name).start();
        var scope = observation.openScope();
        try {
            return executor.apply(traceMicrometer.getTraceInfo());
        } finally {
            scope.close();
            observation.stop();
            MDCUtil.removeMDCTrace();
        }
    }

    public static void executeWithoutResult(
            @NotNull TraceMicrometer traceMicrometer,
            @NotNull String name,
            @NotNull String traceInfo,
            //@NotNull Kind kind, default Kind.SERVER but maybe choose
            @NotNull Consumer<String>executor
    ) {
        var observation = traceMicrometer.getObservation(
                new W3CTraceData(MDCUtil.traceParent(traceInfo)),
                name,
                Kind.SERVER
        ).start();
        var scope = observation.openScope();
        try {
            executor.accept(traceMicrometer.getTraceInfo());
        } finally {
            scope.close();
            observation.stop();
            MDCUtil.removeMDCTrace();
        }
    }

    public static <R> R executeWithResult(
            @NotNull TraceMicrometer traceMicrometer,
            @NotNull String name,
            @NotNull String traceInfo,
            //@NotNull Kind kind, default Kind.SERVER but maybe choose
            @NotNull Function<String, R> executor
    ) {
        var observation = traceMicrometer.getObservation(
                new W3CTraceData(MDCUtil.traceParent(traceInfo)),
                name,
                Kind.SERVER
        ).start();
        var scope = observation.openScope();
        try {
            return executor.apply(traceMicrometer.getTraceInfo());
        } finally {
            scope.close();
            observation.stop();
            MDCUtil.removeMDCTrace();
        }
    }
}
```

### Реализовать по аналогичному принцепу блокировки через одну строку с try-with-resources - наверное наиболее лучший

```java
@Slf4j
@RequiredArgsConstructor
public class BusinessProcessor() {
    private final TraceMicrometer traceMicrometer;
    private final OrderRepository orderRepository;

    private void doStart(Long id) {
        try(var traceObject = traceMicrometer.createTrace(name: BusinessProcessor.class.getSimpleName())) {
            /**
             * <@code>
             *     record traceObject (
             *     Observation observation,
             *     Scope scope,
             *     String traceInfo
             *     ){
             *     }
             * </@code>
             */
        }
        // or
        try(var traceObject = traceMicrometer.createTrace(name: BusinessProcessor.class.getSimpleName(), traceInfo: traceInfo)) {
            /**
             * <@code>
             *     record traceObject (
             *     Observation observation,
             *     Scope scope,
             *     String traceInfo
             *     ){
             *     }
             * </@code>
             */
        }
    }
}
```