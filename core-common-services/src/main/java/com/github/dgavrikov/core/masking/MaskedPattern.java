package com.github.dgavrikov.core.masking;

import com.github.dgavrikov.core.utils.Constants;
import org.apache.commons.lang3.StringUtils;

import java.util.function.UnaryOperator;

public interface MaskedPattern {
    UnaryOperator<String> masking();
    MaskedType type();

    static boolean shouldSkipMasking(String value) {
        return StringUtils.isBlank(value) || value.contains(Constants.DEFAULT_REPLACEMENT);
    }
}
