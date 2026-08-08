package com.github.dgavrikov.core.masking;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dgavrikov.core.masking.annotation.MaskedAccountNumber;
import com.github.dgavrikov.core.masking.annotation.MaskedDate;
import com.github.dgavrikov.core.masking.annotation.MaskedEmail;
import com.github.dgavrikov.core.masking.annotation.MaskedFio;
import com.github.dgavrikov.core.masking.annotation.MaskedIDNumber;
import com.github.dgavrikov.core.masking.annotation.MaskedIDSeries;
import com.github.dgavrikov.core.masking.annotation.MaskedPhone;
import com.github.dgavrikov.core.masking.annotation.MaskedRegex;
import com.github.dgavrikov.core.masking.annotation.MaskedString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link MaskingAnnotationIntrospector} wires the masking
 * serializers for every masking annotation, mirroring the ObjectWriter setup
 * used by CoreLoggingConfiguration#maskingObjectMapper.
 */
class MaskingAnnotationSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setAnnotationIntrospector(new MaskingAnnotationIntrospector(new Class<?>[]{
                MaskedEmail.class,
                MaskedPhone.class,
                MaskedFio.class,
                MaskedString.class,
                MaskedAccountNumber.class,
                MaskedIDNumber.class,
                MaskedIDSeries.class,
                MaskedDate.class,
                MaskedRegex.class
        }));
    }

    static class Person {
        @MaskedEmail
        String email = "john@doe.com";
        @MaskedPhone
        String phone = "+79161234567";
        @MaskedFio
        String fio = "Иванов Иван Иванович";
        @MaskedString
        String secret = "abcde";
        @MaskedAccountNumber
        String account = "12345678901234567890";
        @MaskedIDNumber
        String idNumber = "123456";
        @MaskedIDSeries
        String idSeries = "1234";
        @MaskedDate
        LocalDate birthDate = LocalDate.of(1990, 5, 17);
        @MaskedDate(MaskedDateType.DATE_MM_YYYY)
        String cardExpiry = "05.2027";
        @MaskedRegex(pattern = "\\d", replacement = "#")
        String code = "a1b2";
        String plain = "visible";
    }

    @Test
    void masksEveryAnnotatedField() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(new Person()));

        assertThat(json.get("email").asText()).isEqualTo("j***@d**.com");
        assertThat(json.get("phone").asText()).isEqualTo("+7***67");
        assertThat(json.get("fio").asText()).isEqualTo("И*** И*** И***");
        assertThat(json.get("secret").asText()).isEqualTo("a***e");
        assertThat(json.get("account").asText()).isEqualTo("1234567890******7890");
        assertThat(json.get("idNumber").asText()).isEqualTo("***456");
        assertThat(json.get("idSeries").asText()).isEqualTo("12**");
        assertThat(json.get("code").asText()).isEqualTo("a#b#");
    }

    @Test
    void masksDatesAccordingToAnnotationValue() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(new Person()));

        // Default @MaskedDate hides the leading year of the ISO date
        assertThat(json.get("birthDate").asText()).isEqualTo("****-05-17");
        // DATE_MM_YYYY hides the trailing year
        assertThat(json.get("cardExpiry").asText()).isEqualTo("05.****");
    }

    @Test
    void leavesUnannotatedFieldsUntouched() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(new Person()));

        assertThat(json.get("plain").asText()).isEqualTo("visible");
    }

    static class Mailbox {
        @MaskedEmail
        List<String> recipients = List.of("john@doe.com", "jane@doe.com");
    }

    @Test
    void masksEveryElementOfAnnotatedCollection() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(new Mailbox()));

        assertThat(json.get("recipients").get(0).asText()).isEqualTo("j***@d**.com");
        assertThat(json.get("recipients").get(1).asText()).isEqualTo("j***@d**.com");
    }

    static class Inner {
        @MaskedPhone
        String phone = "+79161234567";
    }

    static class Outer {
        @MaskedString
        Inner inner = new Inner();
    }

    @Test
    void delegatesComplexObjectsToNestedFieldMasking() throws Exception {
        // A masking annotation on a complex field falls back to standard bean
        // serialization, so nested annotated fields are still masked
        JsonNode json = mapper.readTree(mapper.writeValueAsString(new Outer()));

        assertThat(json.get("inner").get("phone").asText()).isEqualTo("+7***67");
    }

    @Test
    void nullAnnotatedFieldIsSerializedAsNull() throws Exception {
        var person = new Person();
        person.email = null;

        JsonNode json = mapper.readTree(mapper.writeValueAsString(person));

        assertThat(json.get("email").isNull()).isTrue();
    }
}
