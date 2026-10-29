package io.github.dgavrikov.core.service.tracing;

import io.micrometer.observation.Observation;
import lombok.Getter;

/**
 * Легковесный контейнер для try-with-resources.
 * Инкапсулирует scope и observation, исключая утечки контекста.
 */
@Getter
public class TraceScope implements AutoCloseable {
    private final Observation observation;
    private final Observation.Scope scope;
    private final String traceInfo;

    public TraceScope(Observation observation, Observation.Scope scope, String traceInfo) {
        this.observation = observation;
        this.scope = scope;
        this.traceInfo = traceInfo;
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
