package com.ecommerce.graphql.config;

import com.ecommerce.events.TopicNames;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic orderRequestedTopic() {
        return TopicBuilder.name(TopicNames.ORDER_REQUESTED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic productUpsertCommandTopic() {
        return TopicBuilder.name(TopicNames.PRODUCT_UPSERT_COMMAND).partitions(3).replicas(1).build();
    }
}
