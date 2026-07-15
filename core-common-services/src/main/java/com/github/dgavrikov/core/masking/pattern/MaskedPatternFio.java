package com.github.dgavrikov.core.masking.pattern;

import com.github.dgavrikov.core.masking.MaskedPattern;
import com.github.dgavrikov.core.masking.MaskedType;
import com.github.dgavrikov.core.utils.Constants;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;

@Component
public class MaskedPatternFio implements MaskedPattern {
    public static final String WHITESPACE_DELIMITER = " ";
    public static final String DASH_DELIMITER = "-";

    public static final UnaryOperator<String> masking = value -> {
        if (MaskedPattern.shouldSkipMasking(value)) return value;

        int length = value.length();
        if (length < 8) {
            return new StringBuilder(length)
                    .append(value.charAt(0))
                    .repeat(Constants.DEFAULT_REPLACEMENT_CHAR, length - 1)
                    .toString();
        }

        if (value.contains(WHITESPACE_DELIMITER) || value.contains(DASH_DELIMITER)) {
            return maskIfContainsDelimiters(value);
        }

        return value;
    };

    private static String maskIfContainsDelimiters(String value) {
        int length = value.length();
        StringBuilder result = new StringBuilder(length + 9);
        boolean inWord = false;
        int wordStart = 0;

        for (int i = 0; i < length; i++) {
            if (Character.isLetter(value.charAt(i))) {
                if (!inWord) {
                    wordStart = i;
                    inWord = true;
                }
            } else {
                if (inWord) {
                    processWord(result, value, wordStart, i);
                    inWord = false;
                }
                result.append(value.charAt(i));
            }
        }
        if (inWord) {
            processWord(result, value, wordStart, length);
        }
        return result.toString();
    }

    private static void processWord(StringBuilder sb, String originalValue, int start, int end) {
        if (start < end) {
            sb.append(originalValue.charAt(start));
            int starsToAdd = 3;
            sb.repeat(Constants.DEFAULT_REPLACEMENT_CHAR, starsToAdd);
        }
    }

    @Override
    public UnaryOperator<String> masking() {
        return masking;
    }

    @Override
    public MaskedType type() {
        return MaskedType.FIO;
    }
}
