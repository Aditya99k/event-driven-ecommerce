package com.ecommerce.user.service;

import com.ecommerce.events.TopicNames;
import com.ecommerce.events.TraceHeaders;
import com.ecommerce.events.UserUpsertCommand;
import com.ecommerce.events.UserUpsertedEvent;
import com.ecommerce.user.domain.UserEntity;
import com.ecommerce.user.domain.UserRepository;
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
public class UserEventHandler {
    private static final Logger log = LoggerFactory.getLogger(UserEventHandler.class);

    private final UserRepository userRepository;
    private final KafkaTemplate<String, UserUpsertedEvent> userEventProducer;

    public UserEventHandler(UserRepository userRepository,
                            KafkaTemplate<String, UserUpsertedEvent> userEventProducer) {
        this.userRepository = userRepository;
        this.userEventProducer = userEventProducer;
    }

    @KafkaListener(topics = TopicNames.USER_UPSERT_COMMAND, groupId = "user-service")
    public void onUserUpsertCommand(ConsumerRecord<String, UserUpsertCommand> record) {
        String correlationId = extractCorrelationId(record.headers());
        MDC.put(TraceHeaders.CORRELATION_ID, correlationId);
        try {
            UserUpsertCommand command = record.value();
            log.info("Kafka consumed: correlationId={} topic={} partition={} offset={} timestamp={} key={} payload={}",
                    correlationId, record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), command);

            UserEntity entity = new UserEntity();
            entity.setId(command.userId());
            entity.setName(command.name());
            entity.setEmail(command.email());
            userRepository.save(entity);

            UserUpsertedEvent event = new UserUpsertedEvent(entity.getId(), entity.getName(), entity.getEmail());
            ProducerRecord<String, UserUpsertedEvent> producerRecord = new ProducerRecord<>(TopicNames.USER_UPSERTED, entity.getId(), event);
            producerRecord.headers().add(TraceHeaders.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));

            userEventProducer.send(producerRecord).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka produce failed: correlationId={} topic={} key={} payload={} error={}",
                            correlationId, TopicNames.USER_UPSERTED, entity.getId(), event, ex.getMessage(), ex);
                    return;
                }
                var metadata = result.getRecordMetadata();
                log.info("Kafka produced: correlationId={} topic={} partition={} offset={} timestamp={} key={} payload={}",
                        correlationId, metadata.topic(), metadata.partition(), metadata.offset(), metadata.timestamp(), entity.getId(), event);
            });
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
}
