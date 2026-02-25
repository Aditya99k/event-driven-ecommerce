package com.ecommerce.inventory.config;

import com.ecommerce.events.TopicNames;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic inventoryReservedTopic() {
        return TopicBuilder.name(TopicNames.INVENTORY_RESERVED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic inventoryRejectedTopic() {
        return TopicBuilder.name(TopicNames.INVENTORY_REJECTED).partitions(3).replicas(1).build();
    }
}
