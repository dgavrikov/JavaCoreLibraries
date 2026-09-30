package io.github.dgavrikov.core.artemis;

import jakarta.jms.Message;
import jakarta.jms.Session;

public interface ArtemisConsumer<V> {
    void onMessage(V payload, Message rawMessage, Session session);
}
