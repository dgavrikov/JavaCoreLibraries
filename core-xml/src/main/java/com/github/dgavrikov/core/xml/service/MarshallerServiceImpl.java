package com.github.dgavrikov.core.xml.service;

import com.github.dgavrikov.core.service.logging.MaskingLog;
import jakarta.annotation.PostConstruct;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Service;
import org.springframework.xml.transform.StringSource;

import javax.xml.transform.Source;
import java.io.StringWriter;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarshallerServiceImpl implements MarshallerService {
    private final Jaxb2Marshaller jaxb2Marshaller;
    private final MaskingLog maskingLog;

    private Marshaller marshaller;
    private Unmarshaller unmarshaller;

    @PostConstruct
    private void init() {
        marshaller = jaxb2Marshaller.createMarshaller();
        unmarshaller = jaxb2Marshaller.createUnmarshaller();
    }

    @Override
    public <T> String marshal(T request) {
        return marshalContent(request);
    }

    @Override
    public <T> T unmarshal(String response, Class<T> clazz) {
        return unmarshalContent(new StringSource(response), clazz);
    }

    private <T> String marshalContent(T source) {
        try (var outputStream = new StringWriter()) {
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");

            marshaller.marshal(source, outputStream);

            var result = outputStream.toString();
            maskingLog.debug(log, "Marshaled XML payload generated for " + source.getClass().getSimpleName() + ": " + result);
            if (StringUtils.isBlank(result))
                throw new MarshallerException("XML marshalling resulted in an empty or blank string.");
            return result;
        } catch (Exception e) {
            throw new MarshallerException("Failed to marshal object of type: " + source.getClass().getSimpleName(), e);
        }
    }

    private <T> T unmarshalContent(Source source, Class<T> clazz) {
        try {
            maskingLog.debug(log, "Received source payload for unmarshalling into  " + clazz.getSimpleName() + ": " + source.toString());
            var result = unmarshaller.unmarshal(source, clazz).getValue();
            if (result == null)
                throw new MarshallerException("XML unmarshalling returned a null object reference.");
            return result;
        } catch (Exception e) {
            throw new MarshallerException("Failed to unmarshal XML source into target class: " + clazz.getSimpleName(), e);
        }
    }
}
