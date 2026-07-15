package com.github.dgavrikov.core.masking.pattern;

import com.github.dgavrikov.core.masking.MaskedPattern;
import com.github.dgavrikov.core.masking.MaskedType;
import com.github.dgavrikov.core.utils.Constants;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;

@Component
public class MaskedPatternIDSeries implements MaskedPattern {
    public static final Double IDENTITY_DOCUMENT_PERCENTAGE = 0.5;

    public static final UnaryOperator<String> masking = value -> {
        if (MaskedPattern.shouldSkipMasking(value)) return value;

        int length = value.length();

        if(length == 1)
            return String.valueOf(Constants.DEFAULT_REPLACEMENT_CHAR);

        int numToMask = (int) Math.ceil(length * IDENTITY_DOCUMENT_PERCENTAGE);
        int openEndIndex = length - numToMask;

        return new StringBuilder(length)
                .append(value, 0, openEndIndex)
                .repeat(Constants.DEFAULT_REPLACEMENT_CHAR, numToMask)
                .toString();
    };

    @Override
    public UnaryOperator<String> masking() {
        return masking;
    }

    @Override
    public MaskedType type() {
        return MaskedType.ID_SERIES;
    }
}
