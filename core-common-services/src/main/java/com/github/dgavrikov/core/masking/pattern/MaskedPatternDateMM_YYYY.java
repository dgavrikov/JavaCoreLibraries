package com.github.dgavrikov.core.masking.pattern;

import com.github.dgavrikov.core.masking.MaskedPattern;
import com.github.dgavrikov.core.masking.MaskedType;
import com.github.dgavrikov.core.utils.Constants;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

@Component
public class MaskedPatternDateMM_YYYY implements MaskedPattern {

    private static final Pattern pattern = Pattern.compile("(.+)(.{4})");
    private static final int TAIL_LENGTH = 4;
    private static final String MASK_TAIL = String.valueOf(Constants.DEFAULT_REPLACEMENT_CHAR).repeat(TAIL_LENGTH);


    public static UnaryOperator<String> masking = value -> {
        if (MaskedPattern.shouldSkipMasking(value)) return value;

        int length = value.length();

        if (length <= TAIL_LENGTH) {
            return MASK_TAIL;
        }

        return value.substring(0, length - TAIL_LENGTH) + MASK_TAIL;
    };

    @Override
    public UnaryOperator<String> masking() {
        return masking;
    }

    @Override
    public MaskedType type() {
        return MaskedType.DATE_MM_YYYY;
    }
}
