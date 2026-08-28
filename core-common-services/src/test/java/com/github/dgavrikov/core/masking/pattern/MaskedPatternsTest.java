package com.github.dgavrikov.core.masking.pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for all PII masking patterns.
 * They pin down the current masking behavior so that future changes
 * to the patterns cannot silently weaken masking or corrupt log output.
 */
class MaskedPatternsTest {

    @Nested
    @DisplayName("MaskedPatternEmail")
    class EmailTest {

        @ParameterizedTest
        @CsvSource({
                "john@doe.com,                  j***@d**.com",
                "user@mail.example.com,         u***@m***.e******.com",
                "a@bc.de,                       a@b*.de",
        })
        void masksSingleEmail(String input, String expected) {
            assertThat(MaskedPatternEmail.masking.apply(input)).isEqualTo(expected);
        }

        @Test
        void masksEmailInsideText() {
            assertThat(MaskedPatternEmail.masking.apply("contact: john@doe.com now"))
                    .isEqualTo("contact: j***@d**.com now");
        }

        @Test
        void masksMultipleEmails() {
            assertThat(MaskedPatternEmail.masking.apply("john@doe.com, jane@doe.com"))
                    .isEqualTo("j***@d**.com, j***@d**.com");
        }

        @Test
        void leavesTextWithoutEmailUntouched() {
            assertThat(MaskedPatternEmail.masking.apply("no email here"))
                    .isEqualTo("no email here");
        }
    }

    @Nested
    @DisplayName("MaskedPatternPhone")
    class PhoneTest {

        @ParameterizedTest
        @CsvSource({
                "+79161234567, +7***67",   // >= 10 chars: keep first 2 and last 2
                "89161234567,  89***67",
                "1234567,      1***67",    // < 10 chars: keep first 1 and last 2
                "123,          1***23",
        })
        void masksPhone(String input, String expected) {
            assertThat(MaskedPatternPhone.masking.apply(input)).isEqualTo(expected);
        }

        @Test
        void leavesTooShortValueUntouched() {
            assertThat(MaskedPatternPhone.masking.apply("12")).isEqualTo("12");
        }
    }

    @Nested
    @DisplayName("MaskedPatternFio")
    class FioTest {

        @Test
        void masksEachWordOfFullName() {
            assertThat(MaskedPatternFio.masking.apply("Иванов Иван Иванович"))
                    .isEqualTo("И*** И*** И***");
        }

        @Test
        void masksWordsSeparatedByDash() {
            assertThat(MaskedPatternFio.masking.apply("Anna-Maria Smith"))
                    .isEqualTo("A***-M*** S***");
        }

        @Test
        void masksShortValueEntirely() {
            // Values shorter than 8 chars: first char + '*' for the rest
            assertThat(MaskedPatternFio.masking.apply("Иван")).isEqualTo("И***");
        }

        @Test
        void leavesLongSingleWordUntouched() {
            // >= 8 chars and no delimiter: current behavior is to keep the value as is
            assertThat(MaskedPatternFio.masking.apply("Longname")).isEqualTo("Longname");
        }
    }

    @Nested
    @DisplayName("MaskedPatternAccountNumber")
    class AccountNumberTest {

        @Test
        void masksMiddleDigitsOf20DigitAccount() {
            assertThat(MaskedPatternAccountNumber.masking.apply("12345678901234567890"))
                    .isEqualTo("1234567890******7890");
        }

        @ParameterizedTest
        @CsvSource({
                "1234567890123456789",   // 19 digits
                "123456789012345678901", // 21 digits
                "1234567890abcdefghij",  // not all digits
        })
        void leavesNonAccountValuesUntouched(String input) {
            assertThat(MaskedPatternAccountNumber.masking.apply(input)).isEqualTo(input);
        }
    }

    @Nested
    @DisplayName("MaskedPatternIDNumber")
    class IdNumberTest {

        @ParameterizedTest
        @CsvSource({
                "123456,  ***456",  // half masked from the start (rounded up)
                "1234567, ****567",
                "12,      *2",
        })
        void masksLeadingHalf(String input, String expected) {
            assertThat(MaskedPatternIDNumber.masking.apply(input)).isEqualTo(expected);
        }

        @Test
        void masksSingleCharCompletely() {
            assertThat(MaskedPatternIDNumber.masking.apply("1")).isEqualTo("*");
        }
    }

    @Nested
    @DisplayName("MaskedPatternIDSeries")
    class IdSeriesTest {

        @ParameterizedTest
        @CsvSource({
                "1234,  12**",  // half masked from the end (rounded up)
                "12345, 12***",
                "12,    1*",
        })
        void masksTrailingHalf(String input, String expected) {
            assertThat(MaskedPatternIDSeries.masking.apply(input)).isEqualTo(expected);
        }

        @Test
        void masksSingleCharCompletely() {
            assertThat(MaskedPatternIDSeries.masking.apply("1")).isEqualTo("*");
        }
    }

    @Nested
    @DisplayName("MaskedPatternDateMM_YYYY / MaskedPatternDateYYYY_MM")
    class DateTest {

        @Test
        void masksTrailingYear() {
            assertThat(MaskedPatternDateMM_YYYY.masking.apply("12.2024")).isEqualTo("12.****");
        }

        @Test
        void masksLeadingYear() {
            assertThat(MaskedPatternDateYYYY_MM.masking.apply("2024-12")).isEqualTo("****-12");
        }

        @Test
        void masksLeadingYearOfFullIsoDate() {
            assertThat(MaskedPatternDateYYYY_MM.masking.apply("1990-05-17")).isEqualTo("****-05-17");
        }

        @Test
        void masksShortValuesEntirely() {
            // Values not longer than the 4-char year are fully replaced by the mask
            assertThat(MaskedPatternDateMM_YYYY.masking.apply("12")).isEqualTo("****");
            assertThat(MaskedPatternDateYYYY_MM.masking.apply("12")).isEqualTo("****");
        }
    }

    @Nested
    @DisplayName("MaskedPatternString (DEFAULT)")
    class DefaultStringTest {

        @ParameterizedTest
        @CsvSource({
                "a,                 *",
                "ab,                a*",
                "abc,               a**",
                "abcd,              a***",
                "abcde,             a***e",
                "abcdefghi,         a******hi",
                "abcdefghij,        ab*******j",
                "abcdefghijklmnop,  abc**********nop",
                "abcdefghijklmnopq, ab***********nopq",
        })
        void masksAccordingToLengthRules(String input, String expected) {
            assertThat(MaskedPatternString.masking.apply(input)).isEqualTo(expected);
        }

        @Test
        void keepsOriginalLength() {
            for (var value : new String[]{"ab", "abcde", "abcdefghij", "abcdefghijklmnopqrstuvwxyz"}) {
                assertThat(MaskedPatternString.masking.apply(value)).hasSameSizeAs(value);
            }
        }
    }

    @Nested
    @DisplayName("MaskedPattern.shouldSkipMasking contract")
    class SkipMaskingTest {

        @Test
        void blankValuesAreReturnedUntouched() {
            assertThat(MaskedPatternEmail.masking.apply("")).isEmpty();
            assertThat(MaskedPatternPhone.masking.apply("   ")).isEqualTo("   ");
        }

        @Test
        void alreadyMaskedValuesAreNotMaskedTwice() {
            var alreadyMasked = "ab***cd";
            assertThat(MaskedPatternEmail.masking.apply(alreadyMasked)).isEqualTo(alreadyMasked);
            assertThat(MaskedPatternPhone.masking.apply(alreadyMasked)).isEqualTo(alreadyMasked);
            assertThat(MaskedPatternFio.masking.apply(alreadyMasked)).isEqualTo(alreadyMasked);
            assertThat(MaskedPatternString.masking.apply(alreadyMasked)).isEqualTo(alreadyMasked);
            assertThat(MaskedPatternIDNumber.masking.apply(alreadyMasked)).isEqualTo(alreadyMasked);
            assertThat(MaskedPatternIDSeries.masking.apply(alreadyMasked)).isEqualTo(alreadyMasked);
        }
    }
}
