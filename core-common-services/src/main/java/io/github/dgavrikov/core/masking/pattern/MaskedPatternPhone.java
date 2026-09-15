package io.github.dgavrikov.core.masking.pattern;

import io.github.dgavrikov.core.masking.MaskedPattern;
import io.github.dgavrikov.core.masking.MaskedType;
import io.github.dgavrikov.core.utils.Constants;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

@Component
public class MaskedPatternPhone implements MaskedPattern {
    /* source of truth
        private static final Pattern PATTERN_10 = Pattern.compile("(.{2})(.+)(.{2})",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        private static final Pattern PATTERN_9 = Pattern.compile("(.)(.+)(.{2})",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    */
    public static final UnaryOperator<String> masking = value -> {
        if (MaskedPattern.shouldSkipMasking(value)) return value;

        /* source of truth
        if(value.length() >= 10)
            return PATTERN_10.matcher(value).replaceAll("$1***$3");
        return PATTERN_9.matcher(value).replaceAll("$1***$3");
        */
        int length = value.length();

        int openStartLength = (length >= 10) ? 2 : 1;
        int openEndStartIndex = length - 2;

        if(openStartLength >= openEndStartIndex)
            return value;

        return new StringBuilder(length + 3)
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
