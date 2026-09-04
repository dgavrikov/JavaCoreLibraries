package io.github.dgavrikov.core.masking.pattern;

import io.github.dgavrikov.core.masking.MaskedPattern;
import io.github.dgavrikov.core.masking.MaskedType;
import io.github.dgavrikov.core.utils.Constants;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MaskedPatternEmail implements MaskedPattern {
    private static final String EMAIL_DOG = "@";
    private static final char EMAIL_DOG_CHAR = '@';
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?<email>[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,})");

    public static final UnaryOperator<String> masking = value -> {
        if (MaskedPattern.shouldSkipMasking(value)) return value;

        if(!value.contains(EMAIL_DOG)){
            return value;
        }

        Matcher matcher = EMAIL_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder(value.length());

        while (matcher.find()) {
            var emailAddress = matcher.group("email");

            StringBuilder maskedEmailBuf = new StringBuilder(emailAddress.length());
            maskSingleEmail(emailAddress, maskedEmailBuf);

            matcher.appendReplacement(result, maskedEmailBuf.toString());
        }
        matcher.appendTail(result);
        return result.toString();
    };

    /**
     * Optimized email parsing without using split() or regular expressions.
     * Writes the output directly into the provided StringBuilder.
     */
    private static void maskSingleEmail(String email, StringBuilder sb) {
        int dogIdx = email.indexOf(EMAIL_DOG_CHAR);
        if (dogIdx <= 0) {
            sb.append(email);
            return;
        }

        // 1. Mask the local part (keep the first character + mask)
        sb.append(email.charAt(0));
        if (dogIdx > 1) {
            sb.repeat(Constants.DEFAULT_REPLACEMENT_CHAR, dogIdx - 1); // Java 21 zero-allocation repeat
        }
        sb.append(EMAIL_DOG_CHAR);

        // 2. Locate the final dot (the top-level domain like .com, which must remain unmasked)
        int lastDotIdx = email.lastIndexOf('.');

        // Parse subdomains located between '@' and the final dot '.'
        int currentStart = dogIdx + 1;
        while (currentStart < lastDotIdx) {
            int nextDot = email.indexOf('.', currentStart);
            if (nextDot == -1 || nextDot > lastDotIdx) {
                break;
            }

            // Mask the subdomain: first character + mask
            sb.append(email.charAt(currentStart));
            int charsToMask = nextDot - currentStart - 1;
            if (charsToMask > 0) {
                sb.repeat(Constants.DEFAULT_REPLACEMENT_CHAR, charsToMask);
            }
            sb.append('.');
            currentStart = nextDot + 1;
        }

        // 3. Append the immutable tail of the domain (.com / .ru)
        sb.append(email, currentStart, email.length());
    }

    @Override
    public UnaryOperator<String> masking() {
        return masking;
    }

    @Override
    public MaskedType type() {
        return MaskedType.EMAIL;
    }
}
