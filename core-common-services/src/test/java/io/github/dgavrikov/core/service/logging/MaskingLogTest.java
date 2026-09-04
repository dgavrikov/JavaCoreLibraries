package io.github.dgavrikov.core.service.logging;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.github.dgavrikov.core.masking.MaskingAnnotationIntrospector;
import io.github.dgavrikov.core.masking.annotation.MaskedEmail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zalando.logbook.BodyFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MaskingLog} string/JSON masking utilities.
 * Filters are stubbed so the tests cover MaskingLog's own logic
 * (JSON detection, enabled flag, fallbacks) rather than Logbook internals.
 */
class MaskingLogTest {

    private static final BodyFilter UPPERCASING_FILTER = (contentType, body) -> body.toUpperCase();
    private static final BodyFilter NULL_FILTER = (contentType, body) -> null;

    private static ObjectWriter plainWriter() {
        var mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return mapper.writer();
    }

    private static ObjectWriter maskingWriter() {
        var mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setAnnotationIntrospector(new MaskingAnnotationIntrospector(new Class<?>[]{MaskedEmail.class}));
        return mapper.writer();
    }

    private static MaskingLog maskingLog(boolean enabled, BodyFilter bodyFilter) {
        return new MaskingLog(plainWriter(), maskingWriter(), bodyFilter, bodyFilter, enabled);
    }

    static class User {
        @MaskedEmail
        String email = "john@doe.com";
        String name = "John";
    }

    @Nested
    @DisplayName("maskingTwoChar")
    class MaskingTwoCharTest {

        @Test
        void keepsTwoLeadingAndTrailingChars() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.maskingTwoChar("abcdef")).isEqualTo("ab****ef");
            assertThat(log.maskingTwoChar("abcde")).isEqualTo("ab****de");
        }

        @Test
        void returnsNullForEmptyInput() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.maskingTwoChar(null)).isNull();
            assertThat(log.maskingTwoChar("")).isNull();
        }

        @Test
        void leavesTooShortValueUntouched() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.maskingTwoChar("abcd")).isEqualTo("abcd");
        }
    }

    @Nested
    @DisplayName("maskingObject")
    class MaskingObjectTest {

        @Test
        void returnsStringsAsIs() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.maskingObject("raw string")).isEqualTo("raw string");
        }

        @Test
        void returnsNullForNull() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.maskingObject(null)).isNull();
        }

        @Test
        void serializesObjectWithMaskingAnnotations() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.maskingObject(new User()))
                    .contains("\"email\":\"j***@d**.com\"")
                    .contains("\"name\":\"John\"");
        }
    }

    @Nested
    @DisplayName("maskingAllJsonObject")
    class MaskingAllJsonObjectTest {

        @Test
        void masksEveryStringValueOfJsonObject() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.maskingAllJsonObject("{\"name\":\"Ivanov\",\"city\":\"Moscow\"}"))
                    .isEqualTo("{\"name\": \"I***v\",\"city\": \"M***w\"}");
        }

        @Test
        void masksStringElementsOfJsonArray() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.maskingAllJsonObject("[\"Ivanov\",\"Petrov\"]"))
                    .isEqualTo("[\"I***v\",\"P***v\"]");
        }

        @Test
        void leavesNumericValuesUntouched() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.maskingAllJsonObject("{\"age\":42}")).isEqualTo("{\"age\":42}");
        }

        @Test
        void returnsDataUnmaskedWhenDisabled() {
            var log = maskingLog(false, UPPERCASING_FILTER);

            var json = "{\"name\":\"Ivanov\"}";
            assertThat(log.maskingAllJsonObject(json)).isEqualTo(json);
        }

        @Test
        void returnsNullForNullInput() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.maskingAllJsonObject(null)).isNull();
        }
    }

    @Nested
    @DisplayName("filterMasking / filterHeaderMasking")
    class FilterMaskingTest {

        @Test
        void appliesFilterToJsonBody() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.filterMasking("{\"key\":\"value\"}")).isEqualTo("{\"KEY\":\"VALUE\"}");
            assertThat(log.filterHeaderMasking("{\"key\":\"value\"}")).isEqualTo("{\"KEY\":\"VALUE\"}");
        }

        @Test
        void serializesObjectBeforeFiltering() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.filterMasking(new User())).contains("JOHN@DOE.COM");
        }

        @Test
        void skipsFilterForNonJsonInput() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.filterMasking("plain text body")).isEqualTo("plain text body");
        }

        @Test
        void skipsFilterWhenDisabled() {
            var log = maskingLog(false, UPPERCASING_FILTER);

            var json = "{\"key\":\"value\"}";
            assertThat(log.filterMasking(json)).isEqualTo(json);
        }

        @Test
        void fallsBackToOriginalWhenFilterReturnsNull() {
            var log = maskingLog(true, NULL_FILTER);

            var json = "{\"key\":\"value\"}";
            assertThat(log.filterMasking(json)).isEqualTo(json);
        }

        @Test
        void fallsBackToOriginalWhenFilterReturnsTooShortResult() {
            var log = maskingLog(true, (contentType, body) -> "{}");

            var json = "{\"key\":\"value\"}";
            assertThat(log.filterMasking(json)).isEqualTo(json);
        }

        @Test
        void returnsNullForNullInput() {
            var log = maskingLog(true, UPPERCASING_FILTER);

            assertThat(log.filterMasking(null)).isNull();
        }
    }
}
