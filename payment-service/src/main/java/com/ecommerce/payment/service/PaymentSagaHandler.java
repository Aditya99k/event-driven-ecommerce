package com.ecommerce.payment.service;

import com.ecommerce.events.PaymentCompletedEvent;
import com.ecommerce.events.PaymentFailedEvent;
import com.ecommerce.events.PaymentRequestedEvent;
import com.ecommerce.events.TopicNames;
import com.ecommerce.events.TraceHeaders;
import com.ecommerce.payment.domain.PaymentProcessMarker;
import com.ecommerce.payment.domain.PaymentProcessMarkerRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentSagaHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentSagaHandler.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final PaymentProcessMarkerRepository markerRepository;

    public PaymentSagaHandler(KafkaTemplate<String, Object> kafkaTemplate,
                              PaymentProcessMarkerRepository markerRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.markerRepository = markerRepository;
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            include = {RuntimeException.class},
            autoCreateTopics = "true",
            retryTopicSuffix = "-retry",
            dltTopicSuffix = "-dlt"
    )
    @KafkaListener(topics = TopicNames.PAYMENT_REQUESTED, groupId = "payment-service")
    public void onPaymentRequested(ConsumerRecord<String, PaymentRequestedEvent> record) {
        withCorrelation(record, () -> {
            PaymentRequestedEvent event = record.value();
            String correlationId = currentCorrelationId();
            logConsume(correlationId, record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);

            if (alreadyProcessed(event.orderId())) {
                log.info("Idempotent skip: correlationId={} orderId={} already processed", correlationId, event.orderId());
                return;
            }

            if (event.amount() <= 0) {
                markTerminal(event.orderId(), "FAILED", "Invalid payment amount");
                sendEvent(
                        TopicNames.PAYMENT_FAILED,
                        event.orderId(),
                        new PaymentFailedEvent(event.orderId(), "Invalid payment amount")
                );
                return;
            }

            // Simulated transient error path to demonstrate retry and DLT handling.
            if (Math.abs(event.amount() - 777.77d) < 0.0001d) {
                throw new RuntimeException("Simulated transient payment gateway timeout");
            }

            markTerminal(event.orderId(), "COMPLETED", null);
            sendEvent(
                    TopicNames.PAYMENT_COMPLETED,
                    event.orderId(),
                    new PaymentCompletedEvent(event.orderId(), UUID.randomUUID().toString(), "APPROVED")
            );
        });
    }

    @DltHandler
    public void onPaymentRequestedDlt(ConsumerRecord<String, PaymentRequestedEvent> record) {
        withCorrelation(record, () -> {
            PaymentRequestedEvent event = record.value();
            String correlationId = currentCorrelationId();
            log.error("Kafka DLT consumed: correlationId={} topic={} partition={} offset={} key={} payload={}",
                    correlationId, record.topic(), record.partition(), record.offset(), record.key(), event);

            if (!alreadyProcessed(event.orderId())) {
                String reason = "Payment processing exhausted retries and moved to DLT";
                markTerminal(event.orderId(), "FAILED", reason);
                sendEvent(TopicNames.PAYMENT_FAILED, event.orderId(), new PaymentFailedEvent(event.orderId(), reason));
            }
        });
    }

    private boolean alreadyProcessed(String orderId) {
        return markerRepository.findById(orderId)
                .map(marker -> "COMPLETED".equals(marker.getStatus()) || "FAILED".equals(marker.getStatus()))
                .orElse(false);
    }

    private void markTerminal(String orderId, String status, String error) {
        PaymentProcessMarker marker = markerRepository.findById(orderId).orElseGet(PaymentProcessMarker::new);
        marker.setOrderId(orderId);
        marker.setStatus(status);
        marker.setLastError(error);
        marker.setUpdatedAt(Instant.now());
        markerRepository.save(marker);
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
