package io.github.dgavrikov.core.utils;

import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

@UtilityClass
public class StringUtils {
    private static final Pattern pattern =
            Pattern.compile("(com\\.github\\.dgavrikov.*?)(?=:)(.)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    public static String replaceException(String text) {
        return org.apache.commons.lang3.StringUtils.isEmpty(text) ? ""
                : org.apache.commons.lang3.StringUtils.stripStart(pattern.matcher(text).replaceAll(""), null);
    }

    public static String replaceEscapeCharacter(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        int len = value.length();
        // Initialize the buffer with a capacity based on the original string length
        StringBuilder sb = new StringBuilder(len);
        boolean lastWasSpace = false;

        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);

            // 1. Optimization for \\s+ (replace any consecutive whitespace/newline groups with a single space)
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }

            // 2. Processing escaped characters
            if (c == '\\' && i + 1 < len) {
                char next = value.charAt(i + 1);

                // If it is \\" -> transform into "
                if (next == '"') {
                    sb.append('"');
                    i++; // Skip the quote character
                    lastWasSpace = false;
                    continue;
                }

                // If it is \\r or \\n -> simply skip (remove from the string)
                if (next == 'r' || next == 'n') {
                    i++; // Skip the 'r' or 'n' letter
                    // If \\r is immediately followed by \\n, skip it as well
                    if (next == 'r' && i + 2 < len && value.charAt(i + 1) == '\\' && value.charAt(i + 2) == 'n') {
                        i += 2;
                    }
                    continue;
                }
            }

            // For all other characters
            sb.append(c);
            lastWasSpace = false;
        }

        return sb.toString();
    }

    public static String truncateRight(String s, int length) {
        if (s == null)
            return null;
        if (s.length() > length && s.length() < (length + 1000))
            return s;
        else if (s.length() > length)
            return s.substring(0, length);
        return s;
    }
}
