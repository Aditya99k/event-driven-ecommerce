package com.ecommerce.payment.service;

import com.ecommerce.events.PaymentCompletedEvent;
import com.ecommerce.events.PaymentFailedEvent;
import com.ecommerce.events.PaymentRequestedEvent;
import com.ecommerce.events.TopicNames;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

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
        PaymentRequestedEvent event = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
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
    }

    private void sendEvent(String topic, String key, Object payload) {
        kafkaTemplate.send(topic, key, payload).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Kafka produce failed: topic={} key={} payload={} error={}",
                        topic, key, payload, ex.getMessage(), ex);
                return;
            }
            var metadata = result.getRecordMetadata();
            log.info("Kafka produced: topic={} partition={} offset={} timestamp={} key={} payload={}",
                    metadata.topic(), metadata.partition(), metadata.offset(), metadata.timestamp(), key, payload);
        });
    }

    private void logConsume(String topic, int partition, long offset, long timestamp, String key, Object payload) {
        log.info("Kafka consumed: topic={} partition={} offset={} timestamp={} key={} payload={}",
                topic, partition, offset, timestamp, key, payload);
    }
}
