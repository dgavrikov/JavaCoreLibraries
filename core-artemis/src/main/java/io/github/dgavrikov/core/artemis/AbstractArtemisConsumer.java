package io.github.dgavrikov.core.artemis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dgavrikov.core.artemis.exception.ConsumerArtemisException;
import io.github.dgavrikov.core.masking.MaskingMarker;
import io.github.dgavrikov.core.service.logging.MaskingLog;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractArtemisConsumer<V> implements MessageListener {
    protected final MaskingLog maskingLog;
    protected final Logger log;
    protected final ObjectMapper objectMapper;
    protected final Class<V> payloadClass;

    protected AbstractArtemisConsumer(
            ObjectMapper objectMapper,
            MaskingLog maskingLog,
            Logger log,
            Class<V> payloadClass
    ) {
        this.objectMapper = objectMapper;
        this.maskingLog = maskingLog;
        this.log = log;
        this.payloadClass = payloadClass;
    }

    @Override
    public void onMessage(Message message) {
        Map<String, String> headers = extractHeaders(message);
        try {
            log.info("Received message from Artemis system: {}, destination: {}", getSystemName(), message.getJMSDestination());
            maskingLog.debug(log, List.of(MaskingMarker.MASKING_JSON_MARKER, MaskingMarker.MASKING_MARKER), headers, "headers:");

            if (!(message instanceof TextMessage textMessage)) {
                throw new ConsumerArtemisException("Unsupported JMS message type. Expected TextMessage, got: " + message.getClass().getName());
            }

            String jsonPayload = textMessage.getText();
            V payload = objectMapper.readValue(jsonPayload, payloadClass);

            // Вызов прикладного хэндлера домена
            process(payload, headers);

            // СТРОГО ПОСЛЕ успешной отработки домена делаем ACK.
            // Если домен бросит эксепшен — сообщение останется в очереди и уйдет на ретраи согласно настройкам Artemis
            message.acknowledge();
            log.trace("Message successfully acknowledged");

        } catch (Exception e) {
            logErrorState(e, headers);
            // Пробрасываем рантайм-эксепшен наружу, чтобы Spring JMS контейнер (DMLC) понял, что транзакция зафейлилась
            throw new ConsumerArtemisException("Failed to process Artemis message in system " + getSystemName(), e);
        }
    }

    // Чистый абстрактный метод для прикладных хэндлеров
    protected abstract void process(V payload, Map<String, String> headers);

    protected abstract String getSystemName();

    private Map<String, String> extractHeaders(Message message) {
        Map<String, String> metaData = new HashMap<>();
        try {
            var propertyNames = message.getPropertyNames();
            while (propertyNames.hasMoreElements()) {
                String key = (String) propertyNames.nextElement();
                metaData.put(key, message.getStringProperty(key));
            }
        } catch (Exception e) {
            log.warn("Failed to extract JMS headers", e);
        }
        return metaData;
    }

    private void logErrorState(Throwable e, Map<String, String> headers) {
        maskingLog.error(log, List.of(MaskingMarker.MASKING_JSON_MARKER, MaskingMarker.MASKING_MARKER), headers, "Headers: ");
        log.error("Error processing Artemis message in system: {}", getSystemName(), e);
    }
}
