package com.github.dgavrikov.core.xml.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collection;

@Getter
@Setter
@ConfigurationProperties(prefix = "com.github.dgavrikov.core.xml.properties")
public class XmlProperties {
    private Collection<String> packageToScan;
}
