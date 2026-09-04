package io.github.dgavrikov.core.xml;

import io.github.dgavrikov.core.config.YamlPropertyLoaderFactory;
import io.github.dgavrikov.core.xml.properties.XmlProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(XmlProperties.class)
@PropertySource(value = "classpath:default_xml.yml", factory = YamlPropertyLoaderFactory.class)
public class AutoConfigurationXml {
}
