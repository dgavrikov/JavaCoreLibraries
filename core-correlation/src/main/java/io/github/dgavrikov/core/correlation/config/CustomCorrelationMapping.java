package io.github.dgavrikov.core.correlation.config;

import java.util.Map;

/**
 * Interface for registering custom correlation mappings within product modules.
 */
public interface CustomCorrelationMapping {
    /**
     * Returns the mapping configuration where the key is the MDC key
     * and the value is the corresponding HTTP header name.
     *
     * @return a map of MDC keys to HTTP headers
     */
    Map<String, String> getMapping();

    /**
     * Defines the application priority. Beans with a higher order value
     * are applied later in the chain and will override previously defined
     * keys (including the BASE_MAPPING).
     *
     * @return the precedence value, defaults to 0
     */
    default int getOrder() {
        return 0;
    }
}
