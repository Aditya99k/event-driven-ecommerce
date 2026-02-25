package com.ecommerce.payment.service;

import com.ecommerce.events.PaymentCompletedEvent;
import com.ecommerce.events.PaymentFailedEvent;
import com.ecommerce.events.PaymentRequestedEvent;
import com.ecommerce.events.TopicNames;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PaymentSagaHandler {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentSagaHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = TopicNames.PAYMENT_REQUESTED, groupId = "payment-service")
    public void onPaymentRequested(PaymentRequestedEvent event) {
        if (event.amount() <= 0) {
            kafkaTemplate.send(
                    TopicNames.PAYMENT_FAILED,
                    event.orderId(),
                    new PaymentFailedEvent(event.orderId(), "Invalid payment amount")
            );
            return;
        }

        kafkaTemplate.send(
                TopicNames.PAYMENT_COMPLETED,
                event.orderId(),
                new PaymentCompletedEvent(event.orderId(), UUID.randomUUID().toString(), "APPROVED")
        );
    }
}
