package io.github.dgavrikov.core.xml.config;

import io.github.dgavrikov.core.xml.AutoConfigurationXml;
import io.github.dgavrikov.core.xml.properties.XmlProperties;
import jakarta.xml.bind.Marshaller;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;

import java.util.HashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@Import(AutoConfigurationXml.class)
public class MarshallerConfig {
    @Bean
    public Jaxb2Marshaller jaxb2Marshaller(XmlProperties xmlProperties){
        Map<String, Boolean> marshallerProperties = new HashMap<>();
        marshallerProperties.put(Marshaller.JAXB_FORMATTED_OUTPUT, true);

        var marshaller = new Jaxb2Marshaller();
        marshaller.setPackagesToScan(xmlProperties.getPackageToScan().toArray(new String[0]));
        marshaller.setMarshallerProperties(marshallerProperties);
        return marshaller;
    }
}
