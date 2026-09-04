package io.github.dgavrikov.core.masking.pattern;

import io.github.dgavrikov.core.masking.MaskedPattern;
import io.github.dgavrikov.core.masking.MaskedType;
import io.github.dgavrikov.core.utils.Constants;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;

@Component
public class MaskedPatternString implements MaskedPattern {

    public static final UnaryOperator<String> masking = value -> {
        if (MaskedPattern.shouldSkipMasking(value)) return value;

        int length = value.length();
        var replacement = Constants.DEFAULT_REPLACEMENT_CHAR;
        switch (length) {
            case 0 -> {
                return value;
            }
            case 1 -> {
                return Character.toString(replacement);
            }
            case 2 -> {
                return replaceInRange(value, 1, 1, replacement);
            }
            case 3 -> {
                return replaceInRange(value, 1, 2, replacement);
            }
            case 4 -> {
                return replaceInRange(value, 1, 3, replacement);
            }
            case 5, 6, 7, 8 ,9 -> {
                return replaceInRange(value, 1, (int) Math.ceil(length * 0.6), replacement);
            }
            case 10, 11, 12, 13 ,14, 15 -> {
                return replaceInRange(value, 2, (int) (2 + Math.ceil(length * 0.6)), replacement);
            }
            default -> {
                var middleIndex = (int) Math.floor(length / 2.0);
                var maskedAmount = (int) Math.ceil(length * 0.6);
                var maskedToTheLeft = (int) Math.ceil(maskedAmount / 2.0);
                if (length % 2 == 0){
                    maskedToTheLeft = (int) Math.floor(maskedAmount / 2.0);
                }
                int maskedToTheRight = maskedAmount - maskedToTheLeft;
                return replaceInRange(value,
                        middleIndex - maskedToTheLeft,
                        middleIndex + maskedToTheRight -1,
                        replacement);
            }
        }
    };

    private static String replaceInRange(String value, int start, int end, char replacement) {
        StringBuilder sb = new StringBuilder(value);

        int countToReplace = end - start + 1;
        if (countToReplace <= 0) {
            return value;
        }

        sb.delete(start, end + 1);

        sb.insert(start, new StringBuilder(countToReplace).repeat(replacement, countToReplace));

        return sb.toString();
    }

    @Override
    public UnaryOperator<String> masking() {
        return masking;
    }

    @Override
    public MaskedType type() {
        return MaskedType.DEFAULT;
    }
}
