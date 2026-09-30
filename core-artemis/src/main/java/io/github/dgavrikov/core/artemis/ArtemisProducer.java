package io.github.dgavrikov.core.artemis;

import io.github.dgavrikov.core.artemis.exception.ProducerArtemisException;

import java.util.Map;

public interface ArtemisProducer <V> {
    // Синхронная гарантированная отправка
    void send(String destination,
              V messageObject,
              Map<String, String> headers) throws ProducerArtemisException;

    // Асинхронный Fire-and-forget
    void sendAsync(String destination,
                   V messageObject,
                   Map<String, String> headers) throws ProducerArtemisException;
}
