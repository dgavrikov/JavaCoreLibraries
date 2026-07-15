package com.github.dgavrikov.core.masking.pattern;

import com.github.dgavrikov.core.masking.MaskedPattern;
import com.github.dgavrikov.core.masking.MaskedType;
import com.github.dgavrikov.core.utils.Constants;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

@Component
public class MaskedPatternDateYYYY_MM implements MaskedPattern {
    private static final Pattern pattern = Pattern.compile("(.{4})(.+)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final String REPLACEMENT_TAIL = Constants.DEFAULT_REPLACEMENT_CHAR
            + Constants.DEFAULT_REPLACEMENT_CHAR
            + Constants.DEFAULT_REPLACEMENT_CHAR
            + Constants.DEFAULT_REPLACEMENT_CHAR
            + "$2";

    public static UnaryOperator<String> masking = value -> {
        if (MaskedPattern.shouldSkipMasking(value)) return value;

        return pattern.matcher(value).replaceAll(REPLACEMENT_TAIL);
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
