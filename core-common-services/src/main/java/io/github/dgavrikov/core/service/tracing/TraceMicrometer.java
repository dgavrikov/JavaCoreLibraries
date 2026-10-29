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
