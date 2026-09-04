package io.github.dgavrikov.core.masking.pattern;

import io.github.dgavrikov.core.masking.MaskedPattern;
import io.github.dgavrikov.core.masking.MaskedType;
import io.github.dgavrikov.core.utils.Constants;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;

@Component
public class MaskedPatternIDNumber implements MaskedPattern {
    public static final Double IDENTITY_DOCUMENT_PERCENTAGE = 0.5;

    public static final UnaryOperator<String> masking = value -> {
        if (MaskedPattern.shouldSkipMasking(value)) return value;

        int length = value.length();
        if (length == 1)
            return String.valueOf(Constants.DEFAULT_REPLACEMENT_CHAR);

        int numToMask = (int) Math.ceil(length * IDENTITY_DOCUMENT_PERCENTAGE);
        int openStartIndex = numToMask;

        return new StringBuilder(length)
                .repeat(Constants.DEFAULT_REPLACEMENT_CHAR, numToMask)
                .append(value, openStartIndex, length)
                .toString();
    };

    @Override
    public UnaryOperator<String> masking() {
        return masking;
    }

    @Override
    public MaskedType type() {
        return MaskedType.ID_NUMBER;
    }
}
