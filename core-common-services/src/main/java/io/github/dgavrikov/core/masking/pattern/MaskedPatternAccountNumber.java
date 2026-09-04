package io.github.dgavrikov.core.masking.pattern;

import io.github.dgavrikov.core.masking.MaskedPattern;
import io.github.dgavrikov.core.masking.MaskedType;
import io.github.dgavrikov.core.utils.Constants;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

@Component
public class MaskedPatternAccountNumber implements MaskedPattern {
    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile("[\\d]{20}");

    public static final UnaryOperator<String> masking = value -> {
        if (MaskedPattern.shouldSkipMasking(value)) return value;

        if (ACCOUNT_NUMBER_PATTERN.matcher(value).matches()) {
            return maskAccount(value);
        }

        return value;
    };

    @Override
    public UnaryOperator<String> masking() {
        return masking;
    }

    @Override
    public MaskedType type() {
        return MaskedType.ACCOUNT_NUMBER;
    }

    private static String maskAccount(String account) {
        StringBuilder sb = new StringBuilder(account);

        sb.setCharAt(10, Constants.DEFAULT_REPLACEMENT_CHAR);
        sb.setCharAt(11, Constants.DEFAULT_REPLACEMENT_CHAR);
        sb.setCharAt(12, Constants.DEFAULT_REPLACEMENT_CHAR);
        sb.setCharAt(13, Constants.DEFAULT_REPLACEMENT_CHAR);
        sb.setCharAt(14, Constants.DEFAULT_REPLACEMENT_CHAR);
        sb.setCharAt(15, Constants.DEFAULT_REPLACEMENT_CHAR);

        return sb.toString();
    }
}
