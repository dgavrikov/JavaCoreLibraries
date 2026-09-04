package io.github.dgavrikov.core.kafka;

import io.github.dgavrikov.core.kafka.exception.ProducerKafkaException;
import io.github.dgavrikov.core.masking.MaskingMarker;
import io.github.dgavrikov.core.service.logging.MaskingLog;
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
            log.info("Sending message to system {}, topic: {}, key: {}", getSystemName(), topics, recordKey);
            maskingLog.debug(log, List.of(MaskingMarker.MASKING_JSON_MARKER, MaskingMarker.MASKING_MARKER), header, "headers:");

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
                        logErrorState(ex, cleanTopic, recordKey, header);
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
                        log.trace("Message successfully sent to topic {}, key: {}", topic, recordKey);
                } catch (Exception e) {
                    logErrorState(e, topics, recordKey, header);
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
                               Map<String, String> headers) {
        maskingLog.error(log, List.of(MaskingMarker.MASKING_JSON_MARKER, MaskingMarker.MASKING_MARKER), headers, "Headers: ");
        log.error("Error sending message to topic {}, key: {}", topic, recordKey, e);
    }

    protected abstract String getSystemName();
}
