package io.github.dgavrikov.core.masking.pattern;

import io.github.dgavrikov.core.masking.MaskedPattern;
import io.github.dgavrikov.core.masking.MaskedType;
import io.github.dgavrikov.core.utils.Constants;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

@Component
public class MaskedPatternDateYYYY_MM implements MaskedPattern {
    private static final Pattern pattern = Pattern.compile("(.{4})(.+)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final int HEAD_LENGTH = 4;

    private static final String MASK_HEAD = String.valueOf(Constants.DEFAULT_REPLACEMENT_CHAR).repeat(HEAD_LENGTH);

    public static UnaryOperator<String> masking = value -> {
        if (MaskedPattern.shouldSkipMasking(value)) return value;

        int length = value.length();

        if (length <= HEAD_LENGTH) {
            return MASK_HEAD;
        }

        return MASK_HEAD + value.substring(HEAD_LENGTH);
    };

    @Override
    public UnaryOperator<String> masking() {
        return masking;
    }

    @Override
    public MaskedType type() {
        return MaskedType.DATE_YYYY_MM;
    }
}
