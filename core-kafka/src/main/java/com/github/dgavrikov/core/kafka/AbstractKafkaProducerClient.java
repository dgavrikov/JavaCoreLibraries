package com.github.dgavrikov.core.kafka;

import com.github.dgavrikov.core.kafka.exception.ProducerKafkaException;
import com.github.dgavrikov.core.masking.MaskingMarker;
import com.github.dgavrikov.core.service.logging.MaskingLog;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public abstract class AbstractKafkaProducerClient<K, V> implements KafkaProducer<K, V> {
    protected final MaskingLog maskingLog;
    protected final Logger log;
    protected final KafkaTemplate<K, V> kafkaTemplate;

    public AbstractKafkaProducerClient(
            KafkaTemplate<K, V> kafkaTemplate,
            MaskingLog maskingLog,
            Logger log
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.maskingLog = maskingLog;
        this.log = log;
    }

    @Override
    public void sendMessage(String topics, K recordKey, V messageObject, Map<String, String> header)
            throws ProducerKafkaException {
        innerSend(topics, recordKey, messageObject, header, false);
    }

    @Override
    public void sendFastMessage(String topics, K recordKey, V messageObject, Map<String, String> header)
            throws ProducerKafkaException {
        innerSend(topics, recordKey, messageObject, header, true);
    }

    private void innerSend(String topics, K recordKey, V messageObject, Map<String, String> header, boolean isFast)
            throws ProducerKafkaException {
        try {
            maskingLog.info(log, "Sending message to system " + getSystemName()
                    + ", topic: " + topics + ", key: " + recordKey);
            maskingLog.debug(log, List.of(MaskingMarker.MASKING_JSON_MARKER, MaskingMarker.MASKING_MARKER), header, "headers:");

            if (messageObject instanceof byte[] bytes) {
                maskingLog.debug(log, "Message body (bytes size): " + bytes.length);
            } else {
                maskingLog.debug(log, messageObject, "Message body: ");
            }

            var topicArray = topics.split(";");
            List<CompletableFuture<SendResult<K, V>>> futures = new ArrayList<>(topicArray.length);

            for (var topic : topicArray) {
                var cleanTopic = topic.trim();

                var producerRecord = new ProducerRecord<>(cleanTopic, recordKey, messageObject);

                if (header != null) {
                    header.forEach((k, v) -> {
                        if (v != null)
                            producerRecord.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8)));
                    });
                }

                var future = kafkaTemplate.send(producerRecord);

                if (isFast) {
                    future.exceptionally(ex -> {
                        logErrorState(ex, cleanTopic, recordKey, messageObject, header);
                        return null;
                    });
                } else {
                    futures.add(future);
                }
            }

            if (!isFast && !futures.isEmpty()) {
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    for (var topic : topicArray)
                        maskingLog.trace(log, "Message successfully sent to topic " + topic + ", key: " + recordKey);
                } catch (Exception e) {
                    logErrorState(e, topics, recordKey, messageObject, header);
                    throw new ProducerKafkaException("Error sending message to topics " + topics
                            + ", key: " + recordKey
                            + ", headers: " + header, e);
                }
            }

        } catch (RuntimeException e) {
            if (e instanceof ProducerKafkaException) throw e;
            throw new ProducerKafkaException(e.getLocalizedMessage(), e);
        }
    }

    private void logErrorState(Throwable e,
                               String topic,
                               K recordKey,
                               V messageObject,
                               Map<String, String> headers) {
        if (messageObject instanceof byte[] bytes) {
            maskingLog.error(log, "Payload size: " + bytes.length + " bytes");
        } else {
            maskingLog.error(log, messageObject, "Message body: ");

        }
        maskingLog.error(log, List.of(MaskingMarker.MASKING_JSON_MARKER, MaskingMarker.MASKING_MARKER), headers, "Headers: ");
        maskingLog.error(log, e, "Error sending message to topic " + topic + ", key: " + recordKey);
    }

    protected abstract String getSystemName();
}
