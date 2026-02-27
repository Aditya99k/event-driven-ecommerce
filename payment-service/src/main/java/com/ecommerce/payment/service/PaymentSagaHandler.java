package com.ecommerce.payment.service;

import com.ecommerce.events.PaymentCompletedEvent;
import com.ecommerce.events.PaymentFailedEvent;
import com.ecommerce.events.PaymentRequestedEvent;
import com.ecommerce.events.TopicNames;
import com.ecommerce.events.TraceHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class PaymentSagaHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentSagaHandler.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentSagaHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = TopicNames.PAYMENT_REQUESTED, groupId = "payment-service")
    public void onPaymentRequested(ConsumerRecord<String, PaymentRequestedEvent> record) {
        withCorrelation(record, () -> {
            PaymentRequestedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            if (event.amount() <= 0) {
                sendEvent(
                        TopicNames.PAYMENT_FAILED,
                        event.orderId(),
                        new PaymentFailedEvent(event.orderId(), "Invalid payment amount")
                );
                return;
            }

            sendEvent(
                    TopicNames.PAYMENT_COMPLETED,
                    event.orderId(),
                    new PaymentCompletedEvent(event.orderId(), UUID.randomUUID().toString(), "APPROVED")
            );
        });
    }

    private void sendEvent(String topic, String key, Object payload) {
        String correlationId = currentCorrelationId();
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, payload);
        record.headers().add(TraceHeaders.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Kafka produce failed: correlationId={} topic={} key={} payload={} error={}",
                        correlationId, topic, key, payload, ex.getMessage(), ex);
                return;
            }
            var metadata = result.getRecordMetadata();
            log.info("Kafka produced: correlationId={} topic={} partition={} offset={} timestamp={} key={} payload={}",
                    correlationId, metadata.topic(), metadata.partition(), metadata.offset(), metadata.timestamp(), key, payload);
        });
    }

    private void logConsume(String correlationId,
                            String topic,
                            int partition,
                            long offset,
                            long timestamp,
                            String key,
                            Object payload) {
        log.info("Kafka consumed: correlationId={} topic={} partition={} offset={} timestamp={} key={} payload={}",
                correlationId, topic, partition, offset, timestamp, key, payload);
    }

    private void withCorrelation(ConsumerRecord<?, ?> record, Runnable runnable) {
        String correlationId = extractCorrelationId(record.headers());
        MDC.put(TraceHeaders.CORRELATION_ID, correlationId);
        try {
            runnable.run();
        } finally {
            MDC.remove(TraceHeaders.CORRELATION_ID);
        }
    }

    private String extractCorrelationId(Headers headers) {
        Header header = headers.lastHeader(TraceHeaders.CORRELATION_ID);
        if (header == null || header.value() == null || header.value().length == 0) {
            return UUID.randomUUID().toString();
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private String currentCorrelationId() {
        String correlationId = MDC.get(TraceHeaders.CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
            MDC.put(TraceHeaders.CORRELATION_ID, correlationId);
        }
        return correlationId;
    }
}
