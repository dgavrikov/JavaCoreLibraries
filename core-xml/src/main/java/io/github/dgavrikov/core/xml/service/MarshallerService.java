package io.github.dgavrikov.core.xml.service;

public interface MarshallerService {
    <T> String marshal(T request);
    <T> T unmarshal(String response, Class<T> clazz);
}
