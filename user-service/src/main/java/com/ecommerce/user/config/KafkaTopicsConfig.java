package com.ecommerce.user.config;

import com.ecommerce.events.TopicNames;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic userUpsertCommandTopic() {
        return TopicBuilder.name(TopicNames.USER_UPSERT_COMMAND).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic userUpsertedTopic() {
        return TopicBuilder.name(TopicNames.USER_UPSERTED).partitions(3).replicas(1).build();
    }
}
