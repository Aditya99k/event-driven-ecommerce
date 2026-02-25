package com.ecommerce.user.service;

import com.ecommerce.events.TopicNames;
import com.ecommerce.events.UserUpsertCommand;
import com.ecommerce.events.UserUpsertedEvent;
import com.ecommerce.user.domain.UserEntity;
import com.ecommerce.user.domain.UserRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

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
        UserUpsertCommand command = record.value();
        log.info("Kafka consumed: topic={} partition={} offset={} timestamp={} key={} payload={}",
                record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), command);

        UserEntity entity = new UserEntity();
        entity.setId(command.userId());
        entity.setName(command.name());
        entity.setEmail(command.email());
        userRepository.save(entity);

        UserUpsertedEvent event = new UserUpsertedEvent(entity.getId(), entity.getName(), entity.getEmail());
        userEventProducer.send(TopicNames.USER_UPSERTED, entity.getId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka produce failed: topic={} key={} payload={} error={}",
                                TopicNames.USER_UPSERTED, entity.getId(), event, ex.getMessage(), ex);
                        return;
                    }
                    var metadata = result.getRecordMetadata();
                    log.info("Kafka produced: topic={} partition={} offset={} timestamp={} key={} payload={}",
                            metadata.topic(), metadata.partition(), metadata.offset(), metadata.timestamp(),
                            entity.getId(), event);
                });
    }
}
