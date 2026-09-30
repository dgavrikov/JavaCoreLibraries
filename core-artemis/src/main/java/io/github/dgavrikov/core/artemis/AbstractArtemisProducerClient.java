package io.github.dgavrikov.core.artemis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dgavrikov.core.artemis.exception.ProducerArtemisException;
import io.github.dgavrikov.core.masking.MaskingMarker;
import io.github.dgavrikov.core.service.logging.MaskingLog;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.springframework.jms.core.JmsTemplate;

import java.util.List;
import java.util.Map;

public abstract class AbstractArtemisProducerClient<V> implements ArtemisProducer<V> {
    protected final MaskingLog maskingLog;
    protected final Logger log;
    protected final JmsTemplate jmsTemplate;
    protected final ObjectMapper objectMapper;

    protected AbstractArtemisProducerClient(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            MaskingLog maskingLog,
            Logger log
    ) {
        // Инициализируем JmsTemplate локально, избегаем глобальной магии бинов
        this.jmsTemplate = new JmsTemplate(connectionFactory);
        this.objectMapper = objectMapper;
        this.maskingLog = maskingLog;
        this.log = log;
    }

    @Override
    public void send(String destination, V messageObject, Map<String, String> headers) throws ProducerArtemisException {
        innerSend(destination, messageObject, headers, false);
    }

    @Override
    public void sendAsync(String destination, V messageObject, Map<String, String> headers) throws ProducerArtemisException {
        innerSend(destination, messageObject, headers, true);
    }

    private void innerSend(String destination, V messageObject, Map<String, String> headers, boolean isAsync)
            throws ProducerArtemisException {
        try {
            log.info("Sending message to system {}, destination: {}, async: {}", getSystemName(), destination, isAsync);
            maskingLog.debug(log, List.of(MaskingMarker.MASKING_JSON_MARKER, MaskingMarker.MASKING_MARKER), headers, "headers:");

            String jsonPayload = objectMapper.writeValueAsString(messageObject);

            if (isAsync) {
                // Для Artemis честный асинхронный режим на уровне JMS делается через запуск задачи в отдельном потоке execution-контекста
                // Либо через настройки самой сессии. Для сохранения KISS-модели запускаем отправку асинхронно
                Thread.ofVirtual().start(() -> executeSend(destination, jsonPayload, headers));
            } else {
                executeSend(destination, jsonPayload, headers);
            }

        } catch (JsonProcessingException e) {
            throw new ProducerArtemisException("Serialization failed for Artemis payload", e);
        } catch (Exception e) {
            logErrorState(e, destination, headers);
            throw new ProducerArtemisException("Error sending message to Artemis destination: " + destination, e);
        }
    }

    private void executeSend(String destination, String jsonPayload, Map<String, String> headers) {
        try {
            jmsTemplate.send(destination, session -> {
                TextMessage textMessage = session.createTextMessage(jsonPayload);
                if (headers != null) {
                    headers.forEach((k, v) -> {
                        if (v != null) {
                            try {
                                textMessage.setStringProperty(k, v);
                            } catch (JMSException e) {
                                throw new IllegalStateException("Failed to set JMS header " + k, e);
                            }
                        }
                    });
                }
                return textMessage;
            });
            log.trace("Message successfully sent to destination: {}", destination);
        } catch (Exception e) {
            logErrorState(e, destination, headers);
            throw new ProducerArtemisException("Async send failed for destination: " + destination, e);
        }
    }

    private void logErrorState(Throwable e, String destination, Map<String, String> headers) {
        maskingLog.error(log, List.of(MaskingMarker.MASKING_JSON_MARKER, MaskingMarker.MASKING_MARKER), headers, "Headers: ");
        log.error("Error sending message to Artemis destination {}", destination, e);
    }

    protected abstract String getSystemName();
}
