package com.github.dgavrikov.core.masking.pattern;

import com.github.dgavrikov.core.masking.MaskedPattern;
import com.github.dgavrikov.core.masking.MaskedType;
import com.github.dgavrikov.core.utils.Constants;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;

@Component
public class MaskedPatternPhone implements MaskedPattern {

    public static final UnaryOperator<String> masking = value -> {
        if (MaskedPattern.shouldSkipMasking(value)) return value;

        int length = value.length();
        if (length < 3) {
            return value;
        }

        int openStartLength = (length >= 10) ? 2 : 1;
        int openEndStartIndex = length - 2;

        return new StringBuilder(length)
                .append(value, 0, openStartLength)
                .repeat(Constants.DEFAULT_REPLACEMENT_CHAR, 3)
                .append(value, openEndStartIndex, length)
                .toString();
    };

    @Override
    public UnaryOperator<String> masking() {
        return masking;
    }

    @Override
    public MaskedType type() {
        return MaskedType.PHONE;
    }
}
