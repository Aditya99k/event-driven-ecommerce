package com.ecommerce.order.config;

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
    NewTopic orderCreatedTopic() {
        return TopicBuilder.name(TopicNames.ORDER_CREATED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic orderStatusChangedTopic() {
        return TopicBuilder.name(TopicNames.ORDER_STATUS_CHANGED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentRequestedTopic() {
        return TopicBuilder.name(TopicNames.PAYMENT_REQUESTED).partitions(3).replicas(1).build();
    }
}
